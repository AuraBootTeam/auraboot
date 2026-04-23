package com.auraboot.framework.crm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Keeps CRM contacts aligned with the business rule that an account may have
 * at most one primary contact.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrmPrimaryContactService {

    private final JdbcTemplate jdbcTemplate;

    public void ensureSinglePrimaryContact(Long tenantId, String contactPid) {
        if (tenantId == null || !StringUtils.hasText(contactPid)) {
            return;
        }

        ContactState contactState = loadContactState(tenantId, contactPid);
        if (contactState == null || !contactState.primary() || !StringUtils.hasText(contactState.accountPid())) {
            return;
        }

        int demoted = jdbcTemplate.update("""
                UPDATE mt_crm_contact
                   SET crm_ct_is_primary = FALSE,
                       updated_at = NOW()
                 WHERE tenant_id = ?
                   AND crm_ct_account_id = ?
                   AND pid <> ?
                   AND COALESCE(crm_ct_is_primary, FALSE) = TRUE
                """, tenantId, contactState.accountPid(), contactPid);

        log.debug("Normalized primary contact for tenant={}, accountPid={}, contactPid={}, demoted={}",
                tenantId, contactState.accountPid(), contactPid, demoted);
    }

    private ContactState loadContactState(Long tenantId, String contactPid) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT crm_ct_account_id, crm_ct_is_primary
                  FROM mt_crm_contact
                 WHERE tenant_id = ?
                   AND pid = ?
                 LIMIT 1
                """, tenantId, contactPid);
        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> row = rows.getFirst();
        return new ContactState(
                row.get("crm_ct_account_id") != null ? row.get("crm_ct_account_id").toString() : null,
                toBoolean(row.get("crm_ct_is_primary"))
        );
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            return "true".equalsIgnoreCase(text) || "1".equals(text) || "t".equalsIgnoreCase(text);
        }
        return false;
    }

    private record ContactState(String accountPid, boolean primary) {}
}
