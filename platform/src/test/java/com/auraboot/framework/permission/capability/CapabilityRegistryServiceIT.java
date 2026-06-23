package com.auraboot.framework.permission.capability;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-stack IT for the capability registry: saveDefinition writes to ab_permission_capability and
 * listDeclarations reads it back (comma-separated includes round-trip), and re-import upserts by
 * (tenant, code) rather than duplicating. Proves the entity/table mapping against the real DB.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Capability registry IT — save/read round-trip + upsert")
class CapabilityRegistryServiceIT {

    private static final long TENANT_ID = 990_300_031L;

    @Autowired
    private CapabilityRegistryService capabilityRegistryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 990_000_031L, "cap-pid", "cap-user");
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_permission_capability WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private CapabilityDefinitionDTO find(List<CapabilityDefinitionDTO> decls, String code) {
        return decls.stream().filter(d -> code.equals(d.getCode())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("saveDefinition persists and listDeclarations reads it back (includes round-trip)")
    void savesAndReadsBackDeclaration() {
        capabilityRegistryService.saveDefinition(CapabilityDefinitionDTO.builder()
                .code("crm.cap.account").group("客户管理").nameZhCN("维护客户资料")
                .includes(List.of("crm.account.read", "crm.account.manage"))
                .tier("editor").sensitive(false).order(10).build());

        CapabilityDefinitionDTO read = find(capabilityRegistryService.listDeclarations(TENANT_ID), "crm.cap.account");
        assertThat(read.getGroup()).isEqualTo("客户管理");
        assertThat(read.getNameZhCN()).isEqualTo("维护客户资料");
        assertThat(read.getIncludes()).containsExactly("crm.account.read", "crm.account.manage");
        assertThat(read.getTier()).isEqualTo("editor");
    }

    @Test
    @DisplayName("re-import upserts by (tenant, code) instead of duplicating")
    void reimportUpsertsByCode() {
        capabilityRegistryService.saveDefinition(CapabilityDefinitionDTO.builder()
                .code("crm.cap.up").group("g1").includes(List.of("crm.account.read")).build());
        capabilityRegistryService.saveDefinition(CapabilityDefinitionDTO.builder()
                .code("crm.cap.up").group("g2-updated")
                .includes(List.of("crm.account.read", "crm.account.manage")).build());

        List<CapabilityDefinitionDTO> decls = capabilityRegistryService.listDeclarations(TENANT_ID);
        long count = decls.stream().filter(d -> "crm.cap.up".equals(d.getCode())).count();
        assertThat(count).isEqualTo(1);

        CapabilityDefinitionDTO read = find(decls, "crm.cap.up");
        assertThat(read.getGroup()).isEqualTo("g2-updated");
        assertThat(read.getIncludes()).containsExactly("crm.account.read", "crm.account.manage");
    }
}
