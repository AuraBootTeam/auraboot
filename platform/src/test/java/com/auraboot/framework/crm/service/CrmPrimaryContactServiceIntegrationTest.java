package com.auraboot.framework.crm.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mt_crm_contact is defined by the crm-starter plugin
 * (plugins/crm-starter/config/fields/crm_contact.json) and materialised
 * during oss-reset-and-init.sh's plugin-import phase, mirroring the
 * mt_crm_lead pattern. Per-test row isolation comes from
 * BaseIntegrationTest's @Transactional + @Rollback(true).
 */
class CrmPrimaryContactServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CrmPrimaryContactService crmPrimaryContactService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void ensureCrmContactTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS mt_crm_contact (
                    id BIGSERIAL PRIMARY KEY,
                    pid VARCHAR(64) UNIQUE NOT NULL,
                    tenant_id BIGINT NOT NULL,
                    crm_ct_account_id VARCHAR(128),
                    crm_ct_name VARCHAR(255),
                    crm_ct_email VARCHAR(255),
                    crm_ct_is_primary BOOLEAN DEFAULT FALSE,
                    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    deleted_flag BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
        jdbcTemplate.execute("ALTER TABLE mt_crm_contact ADD COLUMN IF NOT EXISTS crm_ct_account_id VARCHAR(128)");
        jdbcTemplate.execute("ALTER TABLE mt_crm_contact ADD COLUMN IF NOT EXISTS crm_ct_name VARCHAR(255)");
        jdbcTemplate.execute("ALTER TABLE mt_crm_contact ADD COLUMN IF NOT EXISTS crm_ct_email VARCHAR(255)");
        jdbcTemplate.execute("ALTER TABLE mt_crm_contact ADD COLUMN IF NOT EXISTS crm_ct_is_primary BOOLEAN DEFAULT FALSE");
        jdbcTemplate.execute("ALTER TABLE mt_crm_contact ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
        jdbcTemplate.execute("ALTER TABLE mt_crm_contact ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
        jdbcTemplate.execute("ALTER TABLE mt_crm_contact ADD COLUMN IF NOT EXISTS deleted_flag BOOLEAN NOT NULL DEFAULT FALSE");
    }

    @Test
    @DisplayName("CRM primary contact normalization demotes other primaries under the same account")
    void ensureSinglePrimaryContact_demotesOtherPrimaries() {
        Long tenantId = getTestTenant().getId();
        String accountPid = "acct-main";
        String otherAccountPid = "acct-other";

        String oldPrimaryPid = insertContact(tenantId, accountPid, "Old Primary", true);
        String newPrimaryPid = insertContact(tenantId, accountPid, "New Primary", true);
        String otherAccountPrimaryPid = insertContact(tenantId, otherAccountPid, "Other Account Primary", true);

        crmPrimaryContactService.ensureSinglePrimaryContact(tenantId, newPrimaryPid);

        assertThat(isPrimary(tenantId, newPrimaryPid)).isTrue();
        assertThat(isPrimary(tenantId, oldPrimaryPid)).isFalse();
        assertThat(isPrimary(tenantId, otherAccountPrimaryPid)).isTrue();
    }

    @Test
    @DisplayName("CRM primary contact normalization ignores non-primary contacts")
    void ensureSinglePrimaryContact_skipsNonPrimaryContact() {
        Long tenantId = getTestTenant().getId();
        String accountPid = "acct-secondary";

        String primaryPid = insertContact(tenantId, accountPid, "Primary", true);
        String secondaryPid = insertContact(tenantId, accountPid, "Secondary", false);

        crmPrimaryContactService.ensureSinglePrimaryContact(tenantId, secondaryPid);

        assertThat(isPrimary(tenantId, primaryPid)).isTrue();
        assertThat(isPrimary(tenantId, secondaryPid)).isFalse();
    }

    private String insertContact(Long tenantId, String accountPid, String name, boolean primary) {
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update("""
                INSERT INTO mt_crm_contact (
                    pid, tenant_id, crm_ct_account_id, crm_ct_name, crm_ct_email, crm_ct_is_primary, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, pid, tenantId, accountPid, name, pid + "@example.com", primary);
        return pid;
    }

    private boolean isPrimary(Long tenantId, String contactPid) {
        Boolean value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(crm_ct_is_primary, FALSE)
                  FROM mt_crm_contact
                 WHERE tenant_id = ?
                   AND pid = ?
                """, Boolean.class, tenantId, contactPid);
        return Boolean.TRUE.equals(value);
    }
}
