package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model-routing pure functions:
 * {@code isRateLimitStatus}, {@code resolveAutoModel}, {@code applyAssignedModel}, and the
 * MAPPING half of {@code fetchModels} (the actual {@code fetch("/v1/models")} call is host I/O
 * and is not implemented here). No mutable state; every method is static. Only the
 * {@link JsonCodec} SPI is used, no gson/java.net/java.nio/reflection/threads/System.getenv, so
 * this class is TeaVM-transpilable (see {@code :claude-teavm}).
 */
public final class ClaudeModelRouting {

    private static final String CLAUDE_CODE_PREFIX = "claude-code-";
    private static final String AUTO_ID = "claude-code-auto";

    private ClaudeModelRouting() {
    }

    public static boolean isRateLimitStatus(int status) {
        return status == 429 || status == 529;
    }

    // ---- resolveAutoModel ---------------------------------------------------------------------

    /**
     * If the effective requested model -- {@code body.model}, falling back to {@code ctxModel}
     * when {@code body.model} is falsy/absent (mirrors the TS {@code (obj && obj.model) || (ctx
     * && ctx.model) || ""} chain literally, including the JS truthy semantics) -- names the
     * generic Auto model ({@code "claude-code-auto"}, or any {@code "claude-code-"}-prefixed id
     * whose suffix after stripping that prefix is exactly {@code "auto"}), rewrites {@code
     * body.model} to {@code topAutoCandidate}. The TS instead reads {@code
     * getAutoCandidates("claude-code")[0]} live from the leaderboard, which stays TS; the
     * candidate is passed in here.
     *
     * <p>Returns {@code bodyText} UNCHANGED (verbatim, not re-stringified) in every one of these
     * cases, matching every TS early-return exactly:
     * <ul>
     *   <li>the request is not for the Auto model;</li>
     *   <li>{@code bodyText} fails to parse as JSON (TS {@code catch { return bodyText; }});</li>
     *   <li>the parsed body is JS-falsy (null, {@code 0}, {@code false}, {@code ""} -- TS
     *       {@code !obj}), including an absent/empty {@code bodyText};</li>
     *   <li>{@code topAutoCandidate} is {@code null}/empty ("no ranking yet").</li>
     * </ul>
     * A parsed body that is JS-truthy but NOT an object (e.g. a bare JSON number/string/boolean)
     * is an unreachable edge case for this provider (Anthropic Messages bodies are always JSON
     * objects); the real TS would attempt {@code obj.model = top} there, which in a strict-mode
     * JS context throws on a primitive. This method does NOT reproduce that crash: it safely
     * falls back to returning {@code bodyText} unchanged, a deliberate, documented safety
     * divergence from an unreachable TS bug rather than an "improvement" to any reachable
     * behavior.
     */
    @SuppressWarnings("unchecked")
    public static String resolveAutoModel(JsonCodec json, String bodyText, String ctxModel, String topAutoCandidate) {
        Object obj = tryParse(json, bodyText);
        String requested = requestedModel(obj, ctxModel);
        if (!isAutoModelId(requested) || !JsCoercion.isTruthy(obj)) return bodyText;
        if (JsCoercion.isFalsyString(topAutoCandidate)) return bodyText; // no ranking yet -- leave as-is
        if (!(obj instanceof Map)) return bodyText; // see javadoc: unreachable-in-practice TS crash path
        ((Map<String, Object>) obj).put("model", topAutoCandidate);
        return json.stringify(obj);
    }

    // ---- applyAssignedModel -------------------------------------------------------------------

    /**
     * The router's assigned model ({@code ctxModel}) is authoritative for this request: if the
     * body still carries a different id, rewrites {@code body.model} to {@code ctxModel} and logs
     * {@code "model rewrite: X -> Y (tier assignment)"} (exact TS wording).
     *
     * <p>No-ops (returns {@code bodyText} verbatim) when: {@code ctxModel} is empty or names the
     * Auto model (same {@code isAutoModelId} check as {@link #resolveAutoModel}); {@code bodyText}
     * fails to parse; the parsed body is JS-falsy or not an object (so has no {@code .model}
     * property to compare, matching the TS {@code !obj || !obj.model} short-circuit); {@code
     * body.model} is falsy; or {@code body.model} already equals {@code ctxModel} (TS {@code
     * obj.model === assigned}, a strict/typed equality -- a non-string {@code body.model} that
     * happens to have the same textual value as {@code ctxModel} would NOT match this port's
     * {@code String}-typed {@code assigned.equals(currentModel)} either, since {@code equals}
     * across differing types is also always {@code false} -- so this matches the TS strict
     * comparison's behavior for every JSON value type, not just strings).
     */
    @SuppressWarnings("unchecked")
    public static String applyAssignedModel(JsonCodec json, String bodyText, String ctxModel, Logger log) {
        String assigned = ctxModel == null ? "" : ctxModel;
        if (assigned.isEmpty() || isAutoModelId(assigned)) return bodyText;
        Object obj = tryParse(json, bodyText);
        if (!(obj instanceof Map)) return bodyText;
        Map<String, Object> map = (Map<String, Object>) obj;
        Object currentModel = map.get("model");
        if (!JsCoercion.isTruthy(currentModel) || assigned.equals(currentModel)) return bodyText;
        if (log != null) log.log("model rewrite: " + currentModel + " -> " + assigned + " (tier assignment)");
        map.put("model", assigned);
        return json.stringify(map);
    }

    // ---- fetchModels MAPPING half ---------------------------------------------------------------

    /**
     * Maps a raw {@code GET /v1/models} response body JSON (already fetched by the host) to the
     * {@code {models: {id: {name}}}}
     * shape core-auth's static-fallback merge expects: keeps only ids starting with {@code
     * "claude-"}, names each {@code (display_name || id) + " (Claude Code)"}. Returns {@code null}
     * (TS: {@code null}) when {@code modelsJson} fails to parse, has no usable top-level {@code
     * data} array, or the filtered result is empty -- matches every TS early-return in
     * {@code fetchModels}'s mapping half exactly.
     */
    @SuppressWarnings("unchecked")
    public static String fetchModelsMapping(JsonCodec json, String modelsJson) {
        Object data = tryParse(json, modelsJson);
        List<?> list = Collections.emptyList();
        if (data instanceof Map) {
            Object d = ((Map<String, Object>) data).get("data");
            if (d instanceof List) list = (List<?>) d;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object modelObj : list) {
            if (!(modelObj instanceof Map)) continue; // matches TS `!model` (covers null/non-object entries)
            Map<String, Object> model = (Map<String, Object>) modelObj;
            Object id = model.get("id");
            if (!JsCoercion.isTruthy(id)) continue;
            String idStr = String.valueOf(id);
            if (!idStr.startsWith("claude-")) continue;
            Object displayName = model.get("display_name");
            Object nameSource = JsCoercion.isTruthy(displayName) ? displayName : id;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", nameSource + " (Claude Code)");
            out.put(idStr, entry);
        }
        if (out.isEmpty()) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("models", out);
        return json.stringify(result);
    }

    // ---- shared helpers -------------------------------------------------------------------------

    private static Object tryParse(JsonCodec json, String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            return json.parse(text);
        } catch (RuntimeException e) {
            return null; // matches every TS `try { obj = JSON.parse(...) } catch { ... }`
        }
    }

    @SuppressWarnings("unchecked")
    private static String requestedModel(Object obj, String ctxModel) {
        Object fromBody = null;
        if (obj instanceof Map) {
            Object m = ((Map<String, Object>) obj).get("model");
            if (JsCoercion.isTruthy(m)) fromBody = m;
        }
        Object chosen = fromBody != null ? fromBody : (JsCoercion.isFalsyString(ctxModel) ? null : ctxModel);
        return chosen == null ? "" : String.valueOf(chosen);
    }

    private static boolean isAutoModelId(String id) {
        if (AUTO_ID.equals(id)) return true;
        String stripped = id.startsWith(CLAUDE_CODE_PREFIX) ? id.substring(CLAUDE_CODE_PREFIX.length()) : id;
        return "auto".equals(stripped);
    }
}
