package io.github.intisy.ai.claude;

import io.github.intisy.ai.jvm.backend.clock.SystemClock;
import io.github.intisy.ai.jvm.backend.http.UrlConnectionHttpClient;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.random.SecureRandomAdapter;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.manager.AccountManager;
import io.github.intisy.ai.shared.manager.ManagerOptions;
import io.github.intisy.ai.shared.oauth.OAuthConfig;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Random;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;

import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-assembled backend (mirrors antigravity-auth's {@code AntigravityBackend}): builds
 * the {@code :jvm} SPI implementations + a real {@code AccountManager} lazily from {@link
 * io.github.intisy.ai.shared.routing.HandlerCtx#configDir}, memoized per {@code configDir} so
 * repeated {@code handle()} calls on the same provider instance reuse one {@code Store}/
 * {@code AccountManager} rather than re-opening the accounts file store on every request.
 *
 * <p>Claude OAuth: {@code clientId} is a PUBLIC PKCE installed-app id (safe to inline, not a
 * secret); Claude's public client uses NO client secret, unlike antigravity's Google OAuth
 * client.
 */
final class ClaudeBackend {

    static final String PROVIDER_ID = "claude-code-auth";

    private static final String CLAUDE_TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
    private static final String CLAUDE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";

    private static final ConcurrentHashMap<String, ClaudeBackend> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Store, ClaudeBackend> STORE_CACHE = new ConcurrentHashMap<>();

    final JsonCodec json;
    final Clock clock;
    final Random random;
    final HttpClient http;
    final Store store;
    final AccountStore accountStore;
    final AccountManager accounts;

    private ClaudeBackend(String configDir) {
        this(configDir, new UrlConnectionHttpClient());
    }

    /**
     * Testability seam (mirrors {@code AntigravityBackend}'s equivalent constructor): lets a test
     * inject a scripted {@link HttpClient} while everything else self-assembles exactly like the
     * production path (real {@code FileStore}, so a test can seed {@code accounts.json} under a
     * temp {@code configDir} via {@link #accountStore}). NOT memoized in {@link #CACHE} -- every
     * call builds a fresh instance; production call sites never see this constructor
     * (package-private, only {@link #forConfigDir} is public API).
     */
    ClaudeBackend(String configDir, HttpClient http) {
        this(configFileStore(configDir), http);
    }

    private static Store configFileStore(String configDir) {
        return (configDir != null && !configDir.trim().isEmpty())
                ? new FileStore(Paths.get(configDir))
                : FileStore.fromEnv();
    }

    /** Assembly ctor: takes the {@link Store} directly (no self-assembled {@code FileStore}). */
    private ClaudeBackend(Store store, HttpClient http) {
        this.json = new GsonJsonCodec();
        this.clock = new SystemClock();
        this.random = new SecureRandomAdapter();
        this.http = http;
        this.store = store;

        this.accountStore = new AccountStore(store, json);

        OAuthConfig oauth = new OAuthConfig();
        oauth.tokenUrl = CLAUDE_TOKEN_URL;
        oauth.clientId = CLAUDE_CLIENT_ID;
        oauth.clientSecret = null; // Claude's public PKCE client uses no client secret

        ManagerOptions opts = new ManagerOptions();
        opts.oauth = oauth;

        this.accounts = new AccountManager(PROVIDER_ID, accountStore, http, clock, random, json, opts);
    }

    /** Memoized per {@code configDir} (empty/null folds to the same {@code FileStore.fromEnv()} key). */
    static ClaudeBackend forConfigDir(String configDir) {
        String key = configDir != null ? configDir : "";
        return CACHE.computeIfAbsent(key, ClaudeBackend::new);
    }

    /** Memoized per {@link Store} identity: the server injects one store per host, so this keeps
     *  one backend/{@code AccountManager} per store, mirroring {@link #forConfigDir}'s memoization. */
    static ClaudeBackend forStore(Store store) {
        return STORE_CACHE.computeIfAbsent(store, s -> new ClaudeBackend(s, new UrlConnectionHttpClient()));
    }

    /** Serving entry point: prefer the server's injected store; fall back to a FileStore only for a
     *  legacy/store-less host (ctx.store == null) -- behavior-neutral there. Never forces a store. */
    static ClaudeBackend forCtx(HandlerCtx ctx) {
        if (ctx != null && ctx.store != null) return forStore(ctx.store);
        return forConfigDir(ctx != null ? ctx.configDir : null);
    }

    /** Test-only factory: a fresh (unmemoized) backend with an injected {@link HttpClient}. */
    static ClaudeBackend forTest(String configDir, HttpClient http) {
        return new ClaudeBackend(configDir, http);
    }

    /** Test-only factory: a fresh (unmemoized) backend built directly from an injected {@link Store}. */
    static ClaudeBackend forTest(Store store, HttpClient http) {
        return new ClaudeBackend(store, http);
    }

    /**
     * Test-only: pre-seeds {@link #CACHE} so a subsequent {@link #forConfigDir} call for this
     * {@code configDir} (e.g. from inside {@code ClaudeProvider#handle}) resolves to the given
     * (already-built, presumably {@link #forTest}) backend instead of self-assembling a
     * production one.
     */
    static void registerForTest(String configDir, ClaudeBackend backend) {
        CACHE.put(configDir != null ? configDir : "", backend);
    }

    /** Test-only: pre-seeds {@link #STORE_CACHE} so a subsequent {@link #forCtx}/{@link #forStore}
     *  call for this store resolves to the given (already-built, presumably {@link #forTest})
     *  backend instead of self-assembling a production one. */
    static void registerForTest(Store store, ClaudeBackend backend) {
        STORE_CACHE.put(store, backend);
    }
}
