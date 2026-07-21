package io.github.intisy.ai.js;

import io.github.intisy.ai.claude.ClaudeHandleOrchestrator;
import io.github.intisy.ai.shared.spi.JsonCodec;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSObjects;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSString;

import java.util.Map;

/**
 * One of two async JS bridges composing in the same call graph: a
 * {@link ClaudeHandleOrchestrator.AccountOps} whose ONE async operation ({@code
 * manager.acquire(lane)}) is bridged with a {@code @Async} native method + {@link AsyncCallback},
 * distinct from {@link JsAttemptExecutorBridge}'s, but in the SAME CPS-transformed call graph
 * (the orchestrator's retry loop does {@code acquire} then {@code execute} then {@code acquire}
 * then {@code execute}...). The SYNCHRONOUS ops ({@code reportError}/{@code reportRateLimit}/
 * {@code reportSuccess}/{@code disable}/{@code listEnabledCount}/{@code captureQuota}) return
 * void/int synchronously in TS, so they call plain {@code @JSFunctor}/JSObject-method JS
 * callbacks DIRECTLY, no {@code @Async}.
 *
 * <p>The {@link #awaitAcquire} bridge mirrors {@code JsHttpClientBridge} precisely (see
 * {@link JsAttemptExecutorBridge} for the {@link JSString}-at-generic-boundaries rationale).
 */
public final class JsAccountOpsBridge implements ClaudeHandleOrchestrator.AccountOps {

    /**
     * JS-provided async account acquisition: {@code (lane) => Promise<acquiredJson | null>}.
     * Resolves with a JSON string {@code {"accountId","access"}}, or a JSON {@code "null"} / an
     * actual JS {@code null}/{@code undefined} / empty string when no account is free -- ALL of
     * which the bridge collapses to Java {@code null}, matching the TS {@code !acquired ||
     * !acquired.account}. {@link JSString} at the generic {@link JSPromise} boundary, per the rule.
     */
    @JSFunctor
    public interface JsAcquireFn extends JSObject {
        JSPromise<JSString> acquire(JSString lane);
    }

    /**
     * The synchronous {@link ClaudeHandleOrchestrator.AccountOps} callbacks, grouped in ONE JS
     * object (a non-functor JSObject overlay -- each method is invoked on the underlying JS object
     * by name, exactly like {@code JsStoreBridge.JsStore}'s {@code get}/{@code put}/etc). All
     * values cross as {@link JSString}/{@code int} (declared, non-generic method boundaries, but
     * kept as {@link JSString} for consistency with the async bridges above). Nullable {@code Long}
     * ({@code resetMs}) and the {@code Map} ({@code captureQuota} headers) are JSON-encoded to a
     * {@link JSString} so the JS side reads them with {@code JSON.parse} -- {@code "null"} for a
     * missing reset.
     */
    public interface JsReportFns extends JSObject {
        void reportError(JSString accountId, int attempt, JSString message);

        void reportRateLimit(JSString accountId, JSString lane, JSString resetMsJson);

        void reportSuccess(JSString accountId);

        void disable(JSString accountId, JSString reason);

        int listEnabledCount();

        void captureQuota(JSString accountId, JSString headersJson);
    }

    private final JsAcquireFn jsAcquire;
    private final JsReportFns jsReports;
    private final JsonCodec json;

    public JsAccountOpsBridge(JsAcquireFn jsAcquire, JsReportFns jsReports, JsonCodec json) {
        this.jsAcquire = jsAcquire;
        this.jsReports = jsReports;
        this.json = json;
    }

    @Override
    public ClaudeHandleOrchestrator.Acquired acquire(String lane) {
        String acquiredJson = awaitAcquire(jsAcquire, lane); // <-- suspends; resumes on the JS Promise
        if (acquiredJson == null || acquiredJson.isEmpty()) return null;
        Object parsed = json.parse(acquiredJson);
        if (!(parsed instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) parsed;
        Object id = m.get("accountId");
        if (!(id instanceof String) || ((String) id).isEmpty()) return null;
        Object access = m.get("access");
        return new ClaudeHandleOrchestrator.Acquired((String) id, access instanceof String ? (String) access : null);
    }

    @Override
    public void reportError(String accountId, int attempt, String message) {
        jsReports.reportError(JSString.valueOf(accountId), attempt, JSString.valueOf(message));
    }

    @Override
    public void reportRateLimit(String accountId, String lane, Long resetMs) {
        // resetMs may be null -> JSON "null"; otherwise the epoch-ms number as a JSON string.
        jsReports.reportRateLimit(JSString.valueOf(accountId), JSString.valueOf(lane), JSString.valueOf(json.stringify(resetMs)));
    }

    @Override
    public void reportSuccess(String accountId) {
        jsReports.reportSuccess(JSString.valueOf(accountId));
    }

    @Override
    public void disable(String accountId, String reason) {
        jsReports.disable(JSString.valueOf(accountId), JSString.valueOf(reason));
    }

    @Override
    public int listEnabledCount() {
        return jsReports.listEnabledCount();
    }

    @Override
    public void captureQuota(String accountId, Map<String, String> headers) {
        jsReports.captureQuota(JSString.valueOf(accountId), JSString.valueOf(json.stringify(headers)));
    }

    // -- @Async bridge (the SECOND distinct @Async in the composed call graph) ---------------------

    @Async
    private static native String awaitAcquire(JsAcquireFn fn, String lane);

    private static void awaitAcquire(JsAcquireFn fn, String lane, AsyncCallback<String> callback) {
        fn.acquire(JSString.valueOf(lane)).then(
                value -> {
                    // A JS null/undefined resolve (no account free) -> Java null; else the JSON string.
                    callback.complete(value == null || JSObjects.isUndefined(value) ? null : value.stringValue());
                    return null;
                },
                error -> {
                    callback.error(new RuntimeException("account acquire rejected: " + error));
                    return null;
                });
    }
}
