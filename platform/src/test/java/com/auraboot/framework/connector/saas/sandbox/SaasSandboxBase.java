package com.auraboot.framework.connector.saas.sandbox;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.connector.saas.ReadCursor;
import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.saas.http.SaasHttpClient;
import com.auraboot.framework.connector.saas.http.SaasRateLimiter;
import com.auraboot.framework.connector.saas.hubspot.HubspotConnectorAdapter;
import com.auraboot.framework.connector.saas.hubspot.HubspotTokenRefresher;
import com.auraboot.framework.connector.saas.oauth.OAuth2TokenStore;
import com.auraboot.framework.connector.saas.stripe.StripeConnectorAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Base class for SaaS sandbox contract tests.
 *
 * <p>Provides factory methods for constructing fully-wired
 * {@link HubspotConnectorAdapter} and {@link StripeConnectorAdapter} instances
 * backed by {@link SandboxHttpExecutor} (no real HTTP), a no-op
 * {@link SaasRateLimiter} (avoids sleeping in tests), and a Mockito-backed
 * {@link OAuth2TokenStore} that returns a deterministic access token.
 *
 * <h3>Fixture lifecycle</h3>
 * Each test that calls {@link #createHubspotAdapter()} or
 * {@link #createStripeAdapter()} gets a fresh fixture instance. The fixture
 * reference is kept here so subclasses can access observability methods
 * ({@link #hubspotFixture()}, {@link #stripeFixture()}).
 *
 * <h3>Rate limiter</h3>
 * The injected {@link SaasRateLimiter} uses a zero-cost clock and a no-op
 * sleeper so tests are not throttled. The limiter is still exercised so bucket
 * counting / request-count assertions work correctly.
 */
public abstract class SaasSandboxBase {

    // -- fixture defaults ---------------------------------------------------

    protected static final String HUBSPOT_CLIENT_ID     = "test-client-id";
    protected static final String HUBSPOT_CLIENT_SECRET = "test-client-secret";
    protected static final String HUBSPOT_REFRESH_TOKEN = "initial-rt";
    protected static final String HUBSPOT_ACCESS_TOKEN  = "mock-access-token";

    protected static final String STRIPE_API_KEY = "sk_test_sandbox_key_00000000";

    protected static final long TEST_TENANT_ID = 42L;

    // -- shared test infrastructure ----------------------------------------

    protected final ObjectMapper jsonMapper = new ObjectMapper();

    /** Fast rate limiter — zero-cost clock, no-op sleeper. */
    protected final SaasRateLimiter rateLimiter = new SaasRateLimiter(
            () -> System.currentTimeMillis(),
            ms -> {} /* no-op sleeper — tests run at full speed */);

    // -- per-test fixture state --------------------------------------------

    private HubspotSandboxFixture _hubspotFixture;
    private StripeSandboxFixture  _stripeFixture;

    // -- lifecycle ----------------------------------------------------------

    @BeforeEach
    void sandboxSetup() {
        MetaContext.setCurrentTenantId(TEST_TENANT_ID);
        MetaContext.setCurrentUserId(1L);
    }

    @AfterEach
    void sandboxTeardown() {
        MetaContext.clear();
        rateLimiter.reset();
    }

    // -- factory methods ----------------------------------------------------

    /**
     * Build a {@link HubspotConnectorAdapter} wired to a fresh
     * {@link HubspotSandboxFixture}.
     *
     * <p>The {@link OAuth2TokenStore} mock is pre-stubbed to return
     * {@value #HUBSPOT_ACCESS_TOKEN} for any call to
     * {@code getValidAccessToken}. Tests that want to exercise the real
     * OAuth refresh path should call
     * {@link #createHubspotAdapterWithRealTokenStore()} instead.
     */
    protected HubspotConnectorAdapter createHubspotAdapter() {
        _hubspotFixture = new HubspotSandboxFixture(
                HUBSPOT_CLIENT_ID, HUBSPOT_CLIENT_SECRET, HUBSPOT_REFRESH_TOKEN);
        SandboxHttpExecutor executor = _hubspotFixture.buildExecutor();
        // Use the public constructor — no-sleep sleeper is not needed here since the
        // sandbox executor never returns 429/5xx (faults are test-specific).
        SaasHttpClient http = buildHttpClient(executor);

        OAuth2TokenStore tokenStore = Mockito.mock(OAuth2TokenStore.class);
        when(tokenStore.getValidAccessToken(any(), any())).thenReturn(HUBSPOT_ACCESS_TOKEN);

        return new HubspotConnectorAdapter(http, tokenStore, jsonMapper);
    }

    /**
     * Build a {@link HubspotConnectorAdapter} where the OAuth token path is
     * exercised via a real {@link HubspotTokenRefresher} backed by the
     * sandbox fixture's {@code /oauth/v1/token} route.
     *
     * <p>The {@link OAuth2TokenStore} is still mocked at the persistence
     * layer, but {@code getValidAccessToken} is NOT stubbed — callers should
     * configure it with {@code thenAnswer} / {@code thenReturn} to model the
     * specific refresh scenario under test.
     *
     * @return an array: [adapter, tokenStoreMock, executor]
     */
    protected Object[] createHubspotAdapterWithRealTokenStore() {
        _hubspotFixture = new HubspotSandboxFixture(
                HUBSPOT_CLIENT_ID, HUBSPOT_CLIENT_SECRET, HUBSPOT_REFRESH_TOKEN);
        SandboxHttpExecutor executor = _hubspotFixture.buildExecutor();
        SaasHttpClient http = buildHttpClient(executor);

        OAuth2TokenStore tokenStore = Mockito.mock(OAuth2TokenStore.class);
        HubspotConnectorAdapter adapter = new HubspotConnectorAdapter(http, tokenStore, jsonMapper);
        return new Object[]{adapter, tokenStore, executor};
    }

    /**
     * Build a {@link StripeConnectorAdapter} wired to a fresh
     * {@link StripeSandboxFixture}.
     */
    protected StripeConnectorAdapter createStripeAdapter() {
        _stripeFixture = new StripeSandboxFixture();
        SandboxHttpExecutor executor = _stripeFixture.buildExecutor();
        SaasHttpClient http = buildHttpClient(executor);
        return new StripeConnectorAdapter(http, jsonMapper);
    }

    /**
     * Build a {@link SaasHttpClient} using the public constructor.
     * Tests that need to observe sleep durations should construct
     * {@link SaasHttpClient} directly in the test code using the
     * package-private test-seam constructor via the same package.
     */
    protected SaasHttpClient buildHttpClient(SandboxHttpExecutor executor) {
        // The public constructor uses Thread::sleep; this is fine for sandbox tests
        // since the no-op rate limiter and non-fault paths will not sleep.
        return new SaasHttpClient(executor, rateLimiter, jsonMapper);
    }

    /**
     * Returns the most recently created HubSpot fixture (after
     * {@link #createHubspotAdapter()} or
     * {@link #createHubspotAdapterWithRealTokenStore()}).
     */
    protected HubspotSandboxFixture hubspotFixture() {
        if (_hubspotFixture == null) throw new IllegalStateException(
                "Call createHubspotAdapter() first");
        return _hubspotFixture;
    }

    /** Returns the most recently created Stripe fixture. */
    protected StripeSandboxFixture stripeFixture() {
        if (_stripeFixture == null) throw new IllegalStateException(
                "Call createStripeAdapter() first");
        return _stripeFixture;
    }

    // -- shared config helpers ----------------------------------------------

    protected static SaasConnectorConfig hubspotConfig() {
        return new SaasConnectorConfig(
                "saas-hubspot", "oauth2",
                HUBSPOT_CLIENT_ID, HUBSPOT_CLIENT_SECRET,
                HUBSPOT_REFRESH_TOKEN,
                List.of("crm.objects.contacts.read"),
                HubspotSandboxFixture.API_BASE,
                null,
                Map.of());
    }

    protected static SaasConnectorConfig hubspotConfigWithBase(String apiBase) {
        return new SaasConnectorConfig(
                "saas-hubspot", "oauth2",
                HUBSPOT_CLIENT_ID, HUBSPOT_CLIENT_SECRET,
                HUBSPOT_REFRESH_TOKEN,
                List.of("crm.objects.contacts.read"),
                apiBase,
                null,
                Map.of());
    }

    protected static SaasConnectorConfig stripeConfig() {
        return new SaasConnectorConfig(
                "saas-stripe", "apikey",
                "not-used", STRIPE_API_KEY,
                null,
                List.of(),
                StripeSandboxFixture.API_BASE,
                null,
                Map.of());
    }

    protected static SaasConnectorConfig stripeConfigWithKey(String apiKey) {
        return new SaasConnectorConfig(
                "saas-stripe", "apikey",
                "not-used", apiKey,
                null,
                List.of(),
                StripeSandboxFixture.API_BASE,
                null,
                Map.of());
    }

    protected static ReadCursor emptyCursor() { return ReadCursor.empty(); }

    protected static ReadCursor sinceCursor(Instant since) {
        return new ReadCursor(since, null, Map.of());
    }
}
