package com.auraboot.framework.menu.service;

import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;

/** Keeps authoring-managed menu mounts aligned with environment-scoped pages. */
@Service
public class MenuEnvironmentScopeService {

    static final String MANAGED_KEY = "authoringManaged";
    static final String ENVIRONMENTS_KEY = "authoringEnvironmentIds";

    private final JdbcTemplate jdbcTemplate;

    public MenuEnvironmentScopeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Legacy/plugin menus without an authoring environment list remain globally visible. */
    public static boolean isVisibleIn(Menu menu, Long environmentId) {
        if (menu == null) {
            return false;
        }
        ExtensionBean extension = menu.getExtension();
        Object environments = extension == null ? null : extension.get(ENVIRONMENTS_KEY);
        if (!(environments instanceof Collection<?> values)) {
            return true;
        }
        if (environmentId == null) {
            return false;
        }
        return values.stream().anyMatch(value -> sameId(value, environmentId));
    }

    /** Makes an existing authoring-managed menu visible after its page reaches the target env. */
    public void includeEnvironment(long tenantId, String pageKey, long environmentId) {
        jdbcTemplate.update("""
                UPDATE ab_menu
                SET extension = jsonb_set(
                        COALESCE(extension, '{}'::jsonb),
                        '{authoringEnvironmentIds}',
                        CASE
                            WHEN COALESCE(extension -> 'authoringEnvironmentIds', '[]'::jsonb)
                                    @> jsonb_build_array(CAST(? AS BIGINT))
                                THEN COALESCE(extension -> 'authoringEnvironmentIds', '[]'::jsonb)
                            ELSE COALESCE(extension -> 'authoringEnvironmentIds', '[]'::jsonb)
                                    || jsonb_build_array(CAST(? AS BIGINT))
                        END,
                        TRUE),
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND page_key = ? AND deleted_flag = FALSE
                  AND COALESCE(extension ->> 'authoringManaged', 'false') = 'true'
                """, environmentId, environmentId, tenantId, pageKey);
    }

    private static boolean sameId(Object value, long environmentId) {
        if (value instanceof Number number) {
            return number.longValue() == environmentId;
        }
        return value != null && Long.toString(environmentId).equals(value.toString());
    }
}
