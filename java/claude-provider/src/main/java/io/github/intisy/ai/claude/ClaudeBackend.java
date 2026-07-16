package io.github.intisy.ai.claude;

import io.github.intisy.ai.jvm.backend.clock.SystemClock;
import io.github.intisy.ai.jvm.backend.http.UrlConnectionHttpClient;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.random.SecureRandomAdapter;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.manager.AccountManager;
import io.github.intisy.ai.shared.manager.ManagerOptions;
import io.github.intisy.ai.shared.oauth.OAuthConfig;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Random;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;

import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 6 self-assembled backend (mirrors antigravity-auth's {@code AntigravityBackend}): builds
 * the {@code :jvm} SPI implementations + a real {@code AccountManager} lazily from {@link
 * io.github.intisy.ai.shared.routing.HandlerCtx#configDir}, memoized per {@code configDir} so
 * repeated {@code handle()} calls on the same provider instance reuse one {@code Store}/
 * {@code AccountManager} rather than re-opening the accounts file store on every request.
 *
 * <p>Claude OAuth (see the phase-6 interface map's Part D1, from claude-code-auth's own
 * {@code src/constants.ts}): {@code clientId} is a PUBLIC PKCE installed-app id (safe to inline,
 * not a secret); Claude's public client uses NO client secret, unlike antigravity's Google OAuth
 * client.
 */
final class ClaudeBackend {

    static final String PROVIDER_ID = "claude";

    private static final String CLAUDE_TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
    private static final String CLAUDE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";

    private static final ConcurrentHashMap<String, ClaudeBackend> CACHE = new ConcurrentHashMap<>();

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
        FileStore store = (configDir != null && !configDir.trim().isEmpty())
                ? new FileStore(Paths.get(configDir))
                : FileStore.fromEnv();

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

    /** Test-only factory: a fresh (unmemoized) backend with an injected {@link HttpClient}. */
    static ClaudeBackend forTest(String configDir, HttpClient http) {
        return new ClaudeBackend(configDir, http);
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
}
