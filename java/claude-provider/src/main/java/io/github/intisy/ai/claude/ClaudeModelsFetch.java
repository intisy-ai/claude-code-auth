package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.ModelInfo;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /v1/models} discovery fetch for the example-server dashboard. Java port of the
 * HOST-I/O half of claude-code-auth's TS {@code fetchModels}; the MAPPING half is implemented
 * as {@link ClaudeModelRouting#fetchModelsMapping}, which this class feeds with the raw upstream
 * body.
 *
 * <p>Deliberately uses {@link io.github.intisy.ai.shared.manager.AccountManager#ensureAccess}
 * (no rotation/lane-claim side effects), matching the TS, which never calls {@code acquire} just
 * to list models. {@link #models} never throws: every failure path (no account, refresh failure,
 * network failure, non-2xx upstream, empty mapping) folds into an empty list; the typed {@link
 * io.github.intisy.ai.shared.routing.ModelCatalogProvider#models} contract has no error shape to
 * carry a reason.
 */
final class ClaudeModelsFetch {

    private static final String MODELS_PATH = "/v1/models?limit=1000";

    private ClaudeModelsFetch() {
    }

    /** {@link io.github.intisy.ai.shared.routing.ModelCatalogProvider#models}. */
    static List<ModelInfo> models(ClaudeBackend backend) {
        try {
            return doModels(backend);
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    private static List<ModelInfo> doModels(ClaudeBackend backend) {
        Account account = firstEnabledAccount(backend);
        if (account == null) {
            return Collections.emptyList();
        }

        String access;
        try {
            access = backend.accounts.ensureAccess(account.id);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        if (access == null || access.trim().isEmpty()) {
            return Collections.emptyList();
        }

        HttpResponse resp;
        try {
            resp = backend.http.send(buildRequest(access));
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }

        if (resp.status / 100 != 2) {
            return Collections.emptyList();
        }

        // fetchModelsMapping stays untouched (its JSON-string return shape is TeaVM-exported to
        // JS via ClaudeProviderJs); re-parse its output here rather than change its signature.
        String catalog = ClaudeModelRouting.fetchModelsMapping(backend.json, resp.body);
        if (catalog == null) {
            return Collections.emptyList();
        }

        return toModelInfoList(backend.json, catalog);
    }

    @SuppressWarnings("unchecked")
    private static List<ModelInfo> toModelInfoList(JsonCodec json, String catalogJson) {
        Object parsed = json.parse(catalogJson);
        if (!(parsed instanceof Map)) return Collections.emptyList();
        Object modelsObj = ((Map<String, Object>) parsed).get("models");
        if (!(modelsObj instanceof Map)) return Collections.emptyList();

        List<ModelInfo> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) modelsObj).entrySet()) {
            String id = e.getKey();
            String name = id;
            Object entryObj = e.getValue();
            if (entryObj instanceof Map) {
                Object n = ((Map<String, Object>) entryObj).get("name");
                if (n instanceof String) name = (String) n;
            }
            // context/output are not part of fetchModelsMapping's wire shape (it only ever sets
            // "name"); 0 is not a loss, just an absent upstream value.
            out.add(new ModelInfo(id, name, 0, 0));
        }
        return out;
    }

    private static HttpRequest buildRequest(String access) {
        HttpRequest req = new HttpRequest();
        req.method = "GET";
        req.url = AnthropicRequestTranslator.ANTHROPIC_API_BASE + MODELS_PATH;
        req.headers = new LinkedHashMap<>();
        req.headers.put("authorization", "Bearer " + access);
        req.headers.put("anthropic-version", AnthropicRequestTranslator.ANTHROPIC_VERSION);
        req.headers.put("anthropic-beta", AnthropicRequestTranslator.ANTHROPIC_OAUTH_BETA);
        return req;
    }

    private static Account firstEnabledAccount(ClaudeBackend backend) {
        for (Account a : backend.accountStore.list(ClaudeBackend.PROVIDER_ID)) {
            if (a.enabled != Boolean.FALSE) {
                return a;
            }
        }
        return null;
    }
}
