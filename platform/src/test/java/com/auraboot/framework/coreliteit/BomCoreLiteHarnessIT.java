package com.auraboot.framework.coreliteit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2 acceptance #1: bom-standardization end-to-end on the shared OSS-core-lite stack.
 * Proves import-directory-sync -> model-driven DDL builds the bom table -> the table is
 * queryable via the real DynamicController (GET). Env-gated; never fires in unit CI.
 *
 * Prereq (red line #1, real green):
 *   scripts/dev/core-lite-it.sh --slug=core-lite --jars-dir=/tmp/core-lite-jars \
 *       --plugin=/abs/.../auraboot-enterprise/plugins/bom-standardization
 *   CORE_LITE_IT=1 CORE_LITE_BE_PORT=<port> \
 *     platform/gradlew -p platform test --tests '*BomCoreLiteHarnessIT'
 */
@EnabledIfEnvironmentVariable(named = "CORE_LITE_IT", matches = "1")
class BomCoreLiteHarnessIT extends AbstractCoreLiteIT {

    private static final String BOM_LIST_PAGEKEY =
            System.getenv().getOrDefault("CORE_LITE_BOM_PAGEKEY", "bom_convert_task_list");
    private static final String BOM_CONTAINER_PATH =
            System.getenv().getOrDefault("CORE_LITE_BOM_CONTAINER_PATH", "/tmp/bom-standardization");

    @Test
    void bomModelTable_isQueryableViaDynamicController_afterImport() throws Exception {
        String jwt = adminLogin();
        HttpResponse<String> resp = dynamicList(jwt, BOM_LIST_PAGEKEY, 1, 20);
        assertThat(resp.statusCode())
                .as("bom model table created by model-driven DDL and queryable via DynamicController")
                .isEqualTo(200);
        assertThat(resp.body())
                .as("a real list envelope, not a 'table does not exist' error")
                .contains("\"records\"")
                .doesNotContain("does not exist")
                .doesNotContain("BadSqlGrammar");
    }

    @Test
    void reimport_isIdempotent_successTrue() throws Exception {
        String jwt = adminLogin();
        HttpResponse<String> resp = importPluginDir(jwt, BOM_CONTAINER_PATH);
        assertThat(resp.statusCode()).as("re-import HTTP 200").isEqualTo(200);
        assertThat(resp.body()).as("platform validator success:true (real gate, red line #2.2)")
                .contains("\"success\":true");
    }
}
