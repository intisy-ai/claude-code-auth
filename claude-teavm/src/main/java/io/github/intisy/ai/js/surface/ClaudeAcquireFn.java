package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsFn;
import io.github.intisy.ai.tsemit.TsUnion;

import java.util.concurrent.CompletionStage;

/** The host's account rotation, which answers with the account it picked or with nothing. */
@TsFn
public interface ClaudeAcquireFn {

    /**
     * Takes the next account that can serve one lane.
     *
     * @param lane the lane to serve on
     * @return the account it picked as JSON, or null when none is free
     */
    @TsUnion({"string", "null"})
    CompletionStage<String> acquire(String lane);
}
