package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsModule;
import io.github.intisy.ai.tsemit.TsRaw;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * claude-code-auth's JavaScript module surface, typed for a TypeScript consumer.
 *
 * @implNote Declares the shape {@code ClaudeProviderJs} actually exports; it is never implemented,
 * only emitted. The export class speaks JSPromise, JSString and three JSO functors, none of which
 * mean anything to a TypeScript caller. Values cross as JSON text, because every one of them is
 * already serialised on at least one side of the boundary.
 */
@TsModule
public interface ClaudeProviderSurface {

    /**
     * The beta header this provider must send, merged with whatever the caller already set.
     *
     * @param existing the caller's own beta header, which may be empty
     * @return the merged header value
     */
    String mergeBeta(String existing);

    /**
     * The request body with this provider's own system prompt in place.
     *
     * @param bodyJson the request body, as JSON
     * @return the body with the system prompt applied, as JSON
     */
    String ensureClaudeCodeSystemJson(String bodyJson);

    /**
     * What a surface shows for one quota bucket.
     *
     * @param bucket the bucket's id
     * @return its display label
     */
    String poolLabel(String bucket);

    /**
     * Whether an account has quota left to serve a request.
     *
     * @param accountJson the account, as JSON
     * @return true when it can serve
     */
    boolean accountHasQuota(String accountJson);

    /**
     * Which quota bucket a rate-limit response belongs to.
     *
     * @param limitJson the limit the upstream reported, as JSON
     * @return the bucket's id
     */
    String bucketOfLimit(String limitJson);

    /**
     * Whether a status means the upstream rate-limited the request.
     *
     * @param status the HTTP status
     * @return true when it is a rate limit rather than another failure
     */
    boolean isRateLimitStatus(int status);

    /**
     * Which model an automatic selection resolves to.
     *
     * @param bodyJson the request body, as JSON
     * @param ctxModel the model the handler context named, or empty
     * @param topAutoCandidate the leaderboard's first choice, which stays the host's to rank
     * @return the resolved model id
     */
    String resolveAutoModel(String bodyJson, String ctxModel, String topAutoCandidate);

    /**
     * The request body with the resolved model written into it.
     *
     * @param bodyJson the request body, as JSON
     * @param ctxModel the model the handler context named, or empty
     * @return the body naming the model it will be served as, as JSON
     */
    String applyAssignedModel(String bodyJson, String ctxModel);

    /**
     * The upstream model list mapped into the shape a surface lists.
     *
     * @param modelsJson what the upstream answered, as JSON
     * @return the models, as a JSON object keyed by model id
     */
    String fetchModelsMapping(String modelsJson);

    /**
     * The answer this provider gives when no account is configured at all.
     *
     * @return the synthetic response, as JSON
     */
    String handleNoAccountSmokeTest();

    /**
     * Runs the whole attempt loop: acquire an account, try it, report what happened, and either
     * serve or move on.
     *
     * @param inputsJson the request's url, method, headers, body and model, as a JSON object
     * @param configJson the attempt limit and the cooldown pair, as a JSON object
     * @param jsExec the host's transport, taking an account id and a prepared request and answering
     *               with the attempt's outcome as JSON
     * @param jsAcquire the host's account rotation, taking a lane and answering with the account it
     *                  picked as JSON, or null when none is free
     * @param jsReports what the loop tells the host about each attempt
     * @return the decision as JSON: which attempt to serve, or a synthetic response to answer with
     */
    CompletionStage<String> handleClaudeRequestAsync(
            String inputsJson,
            String configJson,
            BiFunction<String, String, CompletionStage<String>> jsExec,
            // The one escape here: the bridge documents that a JS null means "no account free" and
            // collapses it, and the processor has no way to make a type argument nullable inside a
            // functional-interface parameter. Emitting `Promise<string>` would be a lie the host
            // then has to suppress.
            @TsRaw("((lane: string) => Promise<string | null>)")
            Function<String, CompletionStage<String>> jsAcquire,
            ClaudeReportsShape jsReports);
}
