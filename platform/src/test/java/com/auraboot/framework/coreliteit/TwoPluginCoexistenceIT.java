package com.auraboot.framework.coreliteit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2 acceptance #2: two plugins coexist on ONE shared OSS-core-lite stack and are queried
 * concurrently without polluting each other. The freshly harness-imported enterprise hybrid
 * plugin (bom-standardization) and a separate plugin (crm) each expose their own model-driven
 * table; concurrent {@code DynamicController} list calls both succeed and neither leaks the
 * other's model. Env-gated (CORE_LITE_IT=1); never fires in unit CI.
 *
 * <p><b>Scope honesty:</b> this proves <i>per-plugin model/table coexistence</i> on a shared
 * stack (distinct pageKey -&gt; distinct table). True cross-<i>tenant</i> isolation (same table,
 * different tenant_id, row invisibility across tenants) needs a second-tenant provisioning
 * fixture; {@code TenantController} exposes no create endpoint today, so that variant is
 * deferred to W3 (tracked in the W2 harness doc). The platform scopes dynamic rows by
 * tenant_id from the JWT, which is the isolation mechanism this harness will exercise once a
 * tenant fixture exists.
 *
 * <p>Run (after the shared stack is up with bom imported, plus a seeded plugin like crm):
 * <pre>
 *   CORE_LITE_IT=1 CORE_LITE_BE_PORT=&lt;port&gt; \
 *     ./gradlew :test --tests 'com.auraboot.framework.coreliteit.TwoPluginCoexistenceIT'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "CORE_LITE_IT", matches = "1")
class TwoPluginCoexistenceIT extends AbstractCoreLiteIT {

    private static final String PLUGIN_A_PAGEKEY =
            System.getenv().getOrDefault("CORE_LITE_BOM_PAGEKEY", "bom_conversion_task_pcba_workbench_list");
    private static final String PLUGIN_B_PAGEKEY =
            System.getenv().getOrDefault("CORE_LITE_OTHER_PAGEKEY", "crm_account_list");

    @Test
    void twoPluginsListConcurrently_withoutCrossContamination() throws Exception {
        String jwt = adminLogin();

        CompletableFuture<HttpResponse<String>> a =
                CompletableFuture.supplyAsync(() -> safeList(jwt, PLUGIN_A_PAGEKEY));
        CompletableFuture<HttpResponse<String>> b =
                CompletableFuture.supplyAsync(() -> safeList(jwt, PLUGIN_B_PAGEKEY));

        HttpResponse<String> ra = a.get();
        HttpResponse<String> rb = b.get();

        assertThat(ra.statusCode()).as("plugin A (%s) list under concurrency", PLUGIN_A_PAGEKEY).isEqualTo(200);
        assertThat(rb.statusCode()).as("plugin B (%s) list under concurrency", PLUGIN_B_PAGEKEY).isEqualTo(200);

        // Each is a real list envelope from its own model-driven table — no SQL/table errors.
        for (HttpResponse<String> r : new HttpResponse[] {ra, rb}) {
            assertThat(r.body()).contains("\"records\"").contains("\"total\"")
                    .doesNotContain("does not exist").doesNotContain("BadSqlGrammar");
        }

        // No cross-contamination: plugin A's list must not surface plugin B's pageKey/model and vice versa.
        assertThat(ra.body()).doesNotContain(PLUGIN_B_PAGEKEY);
        assertThat(rb.body()).doesNotContain(PLUGIN_A_PAGEKEY);
    }

    private HttpResponse<String> safeList(String jwt, String pageKey) {
        try {
            return dynamicList(jwt, pageKey, 1, 20);
        } catch (Exception e) {
            throw new RuntimeException("list failed for " + pageKey, e);
        }
    }
}
