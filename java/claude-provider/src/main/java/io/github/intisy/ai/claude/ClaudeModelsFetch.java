package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.LinkedHashMap;

/**
 * Model-map Task 1: {@code GET /v1/models} discovery fetch for the example-server dashboard.
 * Java port of the HOST-I/O half of claude-code-auth's TS {@code fetchModels} (see
 * {@code src/driver/index.ts:197-224}) -- the MAPPING half is already ported as {@link
 * ClaudeModelRouting#fetchModelsMapping}, which this class feeds with the raw upstream body.
 *
 * <p>Deliberately uses {@link io.github.intisy.ai.shared.manager.AccountManager#ensureAccess}
 * (no rotation/lane-claim side effects), matching the TS, which never calls {@code acquire} just
 * to list models. Never throws: every failure path (no account, refresh failure, network
 * failure, non-2xx upstream, empty mapping) folds into the same synthetic error shape the
 * messages path already uses.
 */
final class ClaudeModelsFetch {

    // Distinct wording from ClaudeHandleOrchestrator.NO_ACCOUNT_MESSAGE (this is a discovery
    // call, not a chat turn) but the same synthetic invalid_request_error/x-hub-chat-error shape.
    private static final String NO_ACCOUNT_MESSAGE = "No enabled claude account — seed one first.";
    private static final String MODELS_PATH = "/v1/models?limit=1000";

    private ClaudeModelsFetch() {
    }

    static HttpResponse fetch(ClaudeBackend backend, HandlerCtx ctx) {
        try {
            return doFetch(backend);
        } catch (Throwable e) {
            // Never throw out of a provider handle() path -- any unexpected failure folds into
            // the same api_error shape an upstream failure would.
            return ClaudeProvider.errorResponse(502, "api_error", "models fetch failed: " + e.getMessage());
        }
    }

    private static HttpResponse doFetch(ClaudeBackend backend) {
        Account account = firstEnabledAccount(backend);
        if (account == null) {
            return noAccountError();
        }

        String access;
        try {
            access = backend.accounts.ensureAccess(account.id);
        } catch (RuntimeException e) {
            // Never log the token/refresh error detail -- just the fact that refresh failed.
            return ClaudeProvider.errorResponse(502, "api_error", "token refresh failed");
        }
        if (access == null || access.trim().isEmpty()) {
            return ClaudeProvider.errorResponse(502, "api_error", "token refresh failed");
        }

        HttpResponse resp;
        try {
            resp = backend.http.send(buildRequest(access));
        } catch (RuntimeException e) {
            return ClaudeProvider.errorResponse(502, "api_error", "upstream models fetch failed");
        }

        if (resp.status / 100 != 2) {
            return ClaudeProvider.errorResponse(resp.status, "api_error", "/v1/models returned " + resp.status);
        }

        String catalog = ClaudeModelRouting.fetchModelsMapping(backend.json, resp.body);
        if (catalog == null) {
            return ClaudeProvider.errorResponse(502, "api_error", "no claude models in upstream response");
        }

        HttpResponse out = new HttpResponse();
        out.status = 200;
        out.headers = new LinkedHashMap<>();
        out.headers.put("content-type", "application/json");
        out.body = catalog;
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

    private static HttpResponse noAccountError() {
        HttpResponse response = ClaudeProvider.errorResponse(400, "invalid_request_error", NO_ACCOUNT_MESSAGE);
        response.headers.put("x-hub-chat-error", "1");
        return response;
    }
}
