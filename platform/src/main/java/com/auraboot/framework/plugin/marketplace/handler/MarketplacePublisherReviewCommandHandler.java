package com.auraboot.framework.plugin.marketplace.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.marketplace.entity.MarketplacePlugin;
import com.auraboot.framework.plugin.marketplace.entity.MarketplaceVersion;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplacePluginMapper;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplaceVersionMapper;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Marketplace review side effects that cannot be represented as a single
 * generic DSL state transition.
 */
@Component
@RequiredArgsConstructor
public class MarketplacePublisherReviewCommandHandler implements CommandHandlerExtension {

    private static final String APPROVE_PUBLISHER_APPLICATION = "mkt:approve_publisher_application";
    private static final String PUBLISH_PLUGIN_SUBMISSION = "mkt:publish_plugin_submission";

    private final DynamicDataMapper dynamicDataMapper;
    private final MarketplacePluginMapper pluginMapper;
    private final MarketplaceVersionMapper versionMapper;

    @Override
    public String getCommandType() {
        return APPROVE_PUBLISHER_APPLICATION;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of(APPROVE_PUBLISHER_APPLICATION, PUBLISH_PLUGIN_SUBMISSION);
    }

    @Override
    public boolean supports(String commandType) {
        return getSupportedCommandTypes().contains(commandType);
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Object execute(CommandContext context) {
        return switch (context.commandType()) {
            case APPROVE_PUBLISHER_APPLICATION -> approvePublisherApplication(context);
            case PUBLISH_PLUGIN_SUBMISSION -> publishPluginSubmission(context);
            default -> throw badParam("Unsupported marketplace review command: " + context.commandType());
        };
    }

    private Map<String, Object> approvePublisherApplication(CommandContext context) {
        String applicationPid = requireText(context.recordId(), "publisher application pid is required");
        Long tenantId = requireTenant(context);
        Map<String, Object> application = requireSingle(
                "SELECT * FROM mt_mkt_publisher_application WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}",
                Map.of("tenantId", tenantId, "pid", applicationPid),
                "Publisher application not found: " + applicationPid
        );

        String publisherName = firstText(application.get("mkt_pa_company_name"), application.get("mkt_pa_applicant_name"));
        String email = requireText(stringValue(application.get("mkt_pa_email")), "publisher application email is required");
        Map<String, Object> existing = findFirst(
                "SELECT * FROM mt_mkt_publisher WHERE tenant_id = #{params.tenantId} AND mkt_pub_email = #{params.email} ORDER BY created_at ASC LIMIT 1",
                Map.of("tenantId", tenantId, "email", email)
        );

        Instant now = Instant.now();
        String publisherPid;
        if (existing == null) {
            publisherPid = UniqueIdGenerator.generate();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pid", publisherPid);
            row.put("tenant_id", tenantId);
            row.put("created_at", now);
            row.put("updated_at", now);
            putActor(row);
            row.put("mkt_pub_code", "PUB-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "-" + publisherPid.substring(Math.max(0, publisherPid.length() - 6)));
            row.put("mkt_pub_name", publisherName);
            row.put("mkt_pub_description", stringValue(application.get("mkt_pa_description")));
            row.put("mkt_pub_website", stringValue(application.get("mkt_pa_website")));
            row.put("mkt_pub_email", email);
            row.put("mkt_pub_status", "active");
            row.put("mkt_pub_verified", Boolean.TRUE);
            dynamicDataMapper.insert("mt_mkt_publisher", row);
        } else {
            publisherPid = stringValue(existing.get("pid"));
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("updated_at", now);
            update.put("updated_by", currentUserId());
            update.put("mkt_pub_name", publisherName);
            update.put("mkt_pub_description", stringValue(application.get("mkt_pa_description")));
            update.put("mkt_pub_website", stringValue(application.get("mkt_pa_website")));
            update.put("mkt_pub_status", "active");
            update.put("mkt_pub_verified", Boolean.TRUE);
            dynamicDataMapper.update("mt_mkt_publisher", update, Map.of("tenant_id", tenantId, "pid", publisherPid));
        }

        return Map.of("publisherPid", publisherPid, "applicationPid", applicationPid);
    }

    private Map<String, Object> publishPluginSubmission(CommandContext context) {
        String submissionPid = requireText(context.recordId(), "plugin submission pid is required");
        Long tenantId = requireTenant(context);
        Map<String, Object> submission = requireSingle(
                "SELECT * FROM mt_mkt_plugin_submission WHERE tenant_id = #{params.tenantId} AND pid = #{params.pid}",
                Map.of("tenantId", tenantId, "pid", submissionPid),
                "Plugin submission not found: " + submissionPid
        );

        String pluginCode = requireText(stringValue(submission.get("mkt_ps_plugin_code")), "plugin code is required");
        String pluginName = requireText(stringValue(submission.get("mkt_ps_plugin_name")), "plugin name is required");
        String versionCode = requireText(stringValue(submission.get("mkt_ps_version_code")), "version code is required");
        Instant now = Instant.now();

        Map<String, Object> plugin = findFirst(
                "SELECT * FROM mt_mkt_plugin WHERE tenant_id = #{params.tenantId} AND mkt_plg_code = #{params.pluginCode} ORDER BY created_at ASC LIMIT 1",
                Map.of("tenantId", tenantId, "pluginCode", pluginCode)
        );
        String pluginPid = plugin != null ? stringValue(plugin.get("pid")) : UniqueIdGenerator.generate();
        upsertDynamicPlugin(tenantId, pluginPid, submission, now, plugin == null);

        Map<String, Object> version = findFirst(
                "SELECT * FROM mt_mkt_plugin_version WHERE tenant_id = #{params.tenantId} AND mkt_ver_plugin_pid = #{params.pluginPid} AND mkt_ver_version_code = #{params.versionCode} ORDER BY created_at ASC LIMIT 1",
                Map.of("tenantId", tenantId, "pluginPid", pluginPid, "versionCode", versionCode)
        );
        String versionPid = version != null ? stringValue(version.get("pid")) : UniqueIdGenerator.generate();
        upsertDynamicVersion(tenantId, pluginPid, versionPid, submission, now, version == null);

        Map<String, String> publicPids = SystemTenantContextExecutor.executeAsSystem(() ->
                upsertPublicCatalog(pluginPid, versionPid, pluginCode, pluginName, submission, now)
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pluginPid", pluginPid);
        result.put("versionPid", versionPid);
        result.put("publicPluginPid", publicPids.get("publicPluginPid"));
        result.put("publicVersionPid", publicPids.get("publicVersionPid"));
        return result;
    }

    private void upsertDynamicPlugin(Long tenantId, String pluginPid, Map<String, Object> submission,
                                     Instant now, boolean insert) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (insert) {
            row.put("pid", pluginPid);
            row.put("tenant_id", tenantId);
            row.put("created_at", now);
            putActor(row);
        }
        row.put("updated_at", now);
        row.put("updated_by", currentUserId());
        row.put("mkt_plg_name", stringValue(submission.get("mkt_ps_plugin_name")));
        row.put("mkt_plg_description", stringValue(submission.get("mkt_ps_description")));
        row.put("mkt_plg_publisher_id", firstText(submission.get("mkt_ps_publisher_id"), submission.get("mkt_ps_publisher_pid")));
        row.put("mkt_plg_publisher_pid", stringValue(submission.get("mkt_ps_publisher_pid")));
        row.put("mkt_plg_category", stringValue(submission.get("mkt_ps_category")));
        row.put("mkt_plg_latest_version", stringValue(submission.get("mkt_ps_version_code")));
        row.put("mkt_plg_status", "published");
        row.put("mkt_plg_total_installs", 0);
        row.put("mkt_plg_avg_rating", 0);
        row.put("mkt_plg_is_free", Boolean.FALSE);
        if (insert) {
            row.put("mkt_plg_code", stringValue(submission.get("mkt_ps_plugin_code")));
            dynamicDataMapper.insert("mt_mkt_plugin", row);
        } else {
            dynamicDataMapper.update("mt_mkt_plugin", row, Map.of("tenant_id", tenantId, "pid", pluginPid));
        }
    }

    private void upsertDynamicVersion(Long tenantId, String pluginPid, String versionPid, Map<String, Object> submission,
                                      Instant now, boolean insert) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (insert) {
            row.put("pid", versionPid);
            row.put("tenant_id", tenantId);
            row.put("created_at", now);
            putActor(row);
        }
        row.put("updated_at", now);
        row.put("updated_by", currentUserId());
        row.put("mkt_ver_plugin_id", pluginPid);
        row.put("mkt_ver_plugin_pid", pluginPid);
        row.put("mkt_ver_version_code", stringValue(submission.get("mkt_ps_version_code")));
        row.put("mkt_ver_release_notes", stringValue(submission.get("mkt_ps_release_notes")));
        row.put("mkt_ver_package_url", stringValue(submission.get("mkt_ps_package_url")));
        row.put("mkt_ver_status", "published");
        row.put("mkt_ver_published_at", now);
        if (insert) {
            dynamicDataMapper.insert("mt_mkt_plugin_version", row);
        } else {
            dynamicDataMapper.update("mt_mkt_plugin_version", row, Map.of("tenant_id", tenantId, "pid", versionPid));
        }
    }

    private Map<String, String> upsertPublicCatalog(String pluginPid, String versionPid, String pluginCode,
                                                    String pluginName, Map<String, Object> submission, Instant now) {
        MarketplacePlugin existingPlugin = pluginMapper.findByPluginId(pluginCode);
        String publicPluginPid = existingPlugin != null ? existingPlugin.getPid() : pluginPid;
        Map<String, Object> pluginRow = new LinkedHashMap<>();
        if (existingPlugin == null) {
            pluginRow.put("pid", publicPluginPid);
            pluginRow.put("tenant_id", SystemTenantContextExecutor.SYSTEM_TENANT_ID);
            pluginRow.put("created_at", now);
            pluginRow.put("install_count", 0);
            pluginRow.put("average_rating", 0);
            pluginRow.put("review_count", 0);
            pluginRow.put("featured", Boolean.FALSE);
            pluginRow.put("visibility", "public");
            pluginRow.put("plugin_type", "config");
        }
        pluginRow.put("plugin_id", pluginCode);
        pluginRow.put("namespace", namespaceFromPluginCode(pluginCode));
        pluginRow.put("display_name", pluginName);
        pluginRow.put("display_name_zh", pluginName);
        pluginRow.put("display_name_en", pluginName);
        pluginRow.put("summary", truncate(stringValue(submission.get("mkt_ps_description")), 500));
        pluginRow.put("description", stringValue(submission.get("mkt_ps_description")));
        pluginRow.put("author", firstText(submission.get("mkt_ps_publisher_pid"), submission.get("mkt_ps_publisher_id"), "Marketplace Publisher"));
        pluginRow.put("category_code", stringValue(submission.get("mkt_ps_category")));
        pluginRow.put("status", "published");
        pluginRow.put("latest_version", stringValue(submission.get("mkt_ps_version_code")));
        pluginRow.put("total_versions", 1);
        pluginRow.put("license_mode", "vendor");
        pluginRow.put("updated_at", now);
        pluginRow.put("published_at", now);
        if (existingPlugin == null) {
            pluginRow.put("tags", "[]");
            pluginRow.put("screenshots", "[]");
            dynamicDataMapper.insertWithJsonb("ab_marketplace_plugin", pluginRow, Set.of("tags", "screenshots"));
        } else {
            dynamicDataMapper.updateWithJsonb("ab_marketplace_plugin", pluginRow, Map.of("pid", publicPluginPid),
                    Set.of("tags", "screenshots"));
        }

        MarketplaceVersion existingVersion = versionMapper.findByPluginPidAndVersion(publicPluginPid, stringValue(submission.get("mkt_ps_version_code")));
        String publicVersionPid = existingVersion != null ? existingVersion.getPid() : versionPid;
        Map<String, Object> versionRow = new LinkedHashMap<>();
        if (existingVersion == null) {
            versionRow.put("pid", publicVersionPid);
            versionRow.put("tenant_id", SystemTenantContextExecutor.SYSTEM_TENANT_ID);
            versionRow.put("created_at", now);
            versionRow.put("install_count", 0);
        }
        versionRow.put("marketplace_plugin_pid", publicPluginPid);
        versionRow.put("version", stringValue(submission.get("mkt_ps_version_code")));
        int[] semver = parseVersion(stringValue(submission.get("mkt_ps_version_code")));
        versionRow.put("version_major", semver[0]);
        versionRow.put("version_minor", semver[1]);
        versionRow.put("version_patch", semver[2]);
        versionRow.put("changelog", stringValue(submission.get("mkt_ps_release_notes")));
        versionRow.put("dependencies", "[]");
        versionRow.put("manifest_snapshot", "{}");
        versionRow.put("resource_summary", "{}");
        versionRow.put("package_checksum", stringValue(submission.get("mkt_ps_checksum")));
        versionRow.put("status", "published");
        versionRow.put("validation_result", "{}");
        versionRow.put("updated_at", now);
        versionRow.put("published_at", now);
        if (existingVersion == null) {
            dynamicDataMapper.insertWithJsonb("ab_marketplace_version", versionRow,
                    Set.of("dependencies", "manifest_snapshot", "resource_summary", "validation_result"));
        } else {
            dynamicDataMapper.updateWithJsonb("ab_marketplace_version", versionRow, Map.of("pid", publicVersionPid),
                    Set.of("dependencies", "manifest_snapshot", "resource_summary", "validation_result"));
        }
        return Map.of("publicPluginPid", publicPluginPid, "publicVersionPid", publicVersionPid);
    }

    private Map<String, Object> requireSingle(String sql, Map<String, Object> params, String message) {
        Map<String, Object> row = findFirst(sql, params);
        if (row == null) {
            throw badParam(message);
        }
        return row;
    }

    private Map<String, Object> findFirst(String sql, Map<String, Object> params) {
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, params);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private Long requireTenant(CommandContext context) {
        if (context.tenantId() == null) {
            throw badParam("tenantId is required");
        }
        return context.tenantId();
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw badParam(message);
        }
        return value;
    }

    private BusinessException badParam(String message) {
        return new BusinessException(ResponseCode.BadParam, message);
    }

    private void putActor(Map<String, Object> row) {
        Long userId = currentUserId();
        row.put("created_by", userId);
        row.put("updated_by", userId);
    }

    private Long currentUserId() {
        return MetaContext.exists() ? MetaContext.getCurrentUserId() : null;
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String namespaceFromPluginCode(String pluginCode) {
        String normalized = pluginCode == null ? "" : pluginCode.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('-');
        if (separator > 0) {
            return normalized.substring(0, Math.min(separator, 64));
        }
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private int[] parseVersion(String version) {
        int[] parsed = new int[]{0, 0, 0};
        if (!StringUtils.hasText(version)) {
            return parsed;
        }
        String[] parts = version.split("\\.");
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try {
                parsed[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException ignored) {
                parsed[i] = 0;
            }
        }
        return parsed;
    }
}
