package io.github.intisy.ai.js;

import io.github.intisy.ai.claude.AnthropicRequestTranslator;
import io.github.intisy.ai.claude.ClaudeHandleOrchestrator;
import io.github.intisy.ai.shared.spi.JsonCodec;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSString;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * First of the two async JS bridges T6c1 proves compose inside one TeaVM CPS-transformed
 * orchestrator loop: a {@link ClaudeHandleOrchestrator.AttemptExecutor} (blocking-shaped --
 * {@code execute(accountId, prepared): AttemptResult}) whose implementation is actually a
 * JS-provided async function ({@code fetch}+IP-proxy-pool-backed in production; a delayed mock in
 * the node smoke), bridged via TeaVM's {@code @Async} native method + {@link AsyncCallback}
 * mechanism -- the EXACT shape of core-proxy's proven {@code JsHttpClientBridge}.
 *
 * <p>Mechanism (mirrors {@code JsHttpClientBridge}): {@link #execute} looks synchronous to
 * {@link ClaudeHandleOrchestrator#handle}, but internally suspends on {@link #awaitExecute}, a
 * native method marked {@code @Async}. TeaVM's whole-program CPS transform propagates "this call
 * graph suspends" up to the entrypoint ({@link ClaudeProviderJs#handleClaudeRequestAsync}'s
 * {@code JSPromise}-backing thread), so the suspend/resume surfaces to JS as the returned Promise
 * settling once the JS-side {@code execute} resolves. Because {@code handle} calls this inside a
 * loop that ALSO suspends on {@link JsAccountOpsBridge#acquire}, this is the second half of the
 * two-{@code @Async}-in-one-call-graph composition being de-risked.
 */
public final class JsAttemptExecutorBridge implements ClaudeHandleOrchestrator.AttemptExecutor {

    /**
     * JS-provided async attempt transport: {@code (accountId, preparedJson) => Promise<attemptResultJson>}.
     * Request and result cross as plain JSON strings (mirrors {@code JsHttpClientBridge}'s JSON
     * boundary) -- no per-field JSO overlay types. {@code preparedJson} is the serialized
     * {@link AnthropicRequestTranslator.PreparedRequest}; {@code attemptResultJson} is
     * {@code {status, headers, transportFailed, attemptRef}} (NO response body -- the host retains
     * the live {@code Response} keyed by its own opaque {@code attemptRef}).
     *
     * <p>Uses {@link JSString} (not {@code String}) at this generic {@link JSPromise} functor
     * boundary, per {@code JsHttpClientBridge.JsHttpSend}'s javadoc: TeaVM's automatic
     * String&lt;-&gt;native-JS-string conversion only fires at a DECLARED (non-generic) boundary, so
     * a value flowing through a generic JS-facing callback ({@code JSPromise<T>}'s mapping/consumer)
     * is type-erased and a raw native JS string would leak into Java code expecting a
     * {@code jl_String} wrapper. {@link JSString} overlays the native string directly; {@code String}
     * conversion happens only at the edges via {@code JSString.valueOf}/{@code .stringValue()}.
     */
    @JSFunctor
    public interface JsExecFn extends JSObject {
        JSPromise<JSString> execute(JSString accountId, JSString preparedJson);
    }

    private final JsExecFn jsExec;
    private final JsonCodec json;

    public JsAttemptExecutorBridge(JsExecFn jsExec, JsonCodec json) {
        this.jsExec = jsExec;
        this.json = json;
    }

    @Override
    public ClaudeHandleOrchestrator.AttemptResult execute(String accountId, AnthropicRequestTranslator.PreparedRequest prepared) {
        String preparedJson = json.stringify(preparedToMap(prepared));

        String resultJson = awaitExecute(jsExec, accountId, preparedJson); // <-- suspends; resumes on the JS Promise

        return parseAttemptResult(resultJson);
    }

    private static Map<String, Object> preparedToMap(AnthropicRequestTranslator.PreparedRequest prepared) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("request", prepared.request);
        m.put("method", prepared.method);
        m.put("headers", prepared.headers != null ? prepared.headers : new LinkedHashMap<String, String>());
        m.put("body", prepared.body); // may be null (stripped for GET/HEAD) -> JSON null
        m.put("streaming", prepared.streaming);
        return m;
    }

    @SuppressWarnings("unchecked")
    private ClaudeHandleOrchestrator.AttemptResult parseAttemptResult(String resultJson) {
        int status = 0;
        Map<String, String> headers = new LinkedHashMap<>();
        boolean transportFailed = false;
        Object attemptRef = null; // opaque: keep the JSON-decoded value (Long/Double/String/...) verbatim

        Object parsed = json.parse(resultJson);
        if (parsed instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) parsed;
            Object statusVal = m.get("status");
            if (statusVal instanceof Number) status = ((Number) statusVal).intValue();
            Object tfVal = m.get("transportFailed");
            if (tfVal instanceof Boolean) transportFailed = (Boolean) tfVal;
            attemptRef = m.get("attemptRef");
            Object headersVal = m.get("headers");
            if (headersVal instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<Object, Object>) headersVal).entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
            }
        }
        return new ClaudeHandleOrchestrator.AttemptResult(status, headers, transportFailed, attemptRef);
    }

    // -- @Async bridge ------------------------------------------------------------

    /** Blocking-looking native entrypoint; TeaVM's async transform makes every (transitive) caller
     *  suspend/resume instead of blocking. Same shape as {@code JsHttpClientBridge.awaitSend}. */
    @Async
    private static native String awaitExecute(JsExecFn fn, String accountId, String preparedJson);

    // Companion: same name, void return, trailing AsyncCallback<T> -- the exact pairing TeaVM's
    // async codegen looks for. `value` is a JSString straight from the JS Promise's resolve;
    // .stringValue() is the explicit String conversion (see JsExecFn's javadoc for why a plain
    // generic String parameter can't be used here).
    private static void awaitExecute(JsExecFn fn, String accountId, String preparedJson, AsyncCallback<String> callback) {
        fn.execute(JSString.valueOf(accountId), JSString.valueOf(preparedJson)).then(
                value -> {
                    callback.complete(value.stringValue());
                    return null;
                },
                error -> {
                    callback.error(new RuntimeException("attempt execute rejected: " + error));
                    return null;
                });
    }
}
