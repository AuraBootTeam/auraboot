package com.auraboot.framework.plugin.marketplace.handler;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplacePluginMapper;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplaceVersionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplacePublisherReviewCommandHandlerTest {

    @Test
    void publishSubmissionCreatesPublicCatalogListingAndVersion() throws Exception {
        DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);
        MarketplacePluginMapper pluginMapper = mock(MarketplacePluginMapper.class);
        MarketplaceVersionMapper versionMapper = mock(MarketplaceVersionMapper.class);
        MarketplacePublisherReviewCommandHandler handler =
                new MarketplacePublisherReviewCommandHandler(dynamicDataMapper, pluginMapper, versionMapper);

        when(dynamicDataMapper.selectByQuery(
                argThat(sql -> sql != null && sql.contains("mt_mkt_plugin_submission")),
                anyMap()
        )).thenReturn(List.of(Map.of(
                "pid", "SUB-PID",
                "mkt_ps_status", "published",
                "mkt_ps_publisher_pid", "PUB-PID",
                "mkt_ps_plugin_name", "Approved Plugin",
                "mkt_ps_plugin_code", "approved-plugin",
                "mkt_ps_description", "Visible after review",
                "mkt_ps_category", "utility",
                "mkt_ps_package_url", "https://example.test/plugin.zip",
                "mkt_ps_version_code", "1.2.3",
                "mkt_ps_release_notes", "Initial public release"
        )));
        when(dynamicDataMapper.selectByQuery(
                argThat(sql -> sql != null && sql.contains("mt_mkt_plugin ") && sql.contains("mkt_plg_code")),
                anyMap()
        )).thenReturn(List.of());
        when(dynamicDataMapper.selectByQuery(
                argThat(sql -> sql != null && sql.contains("mt_mkt_plugin_version")),
                anyMap()
        )).thenReturn(List.of());
        when(pluginMapper.findByPluginId("approved-plugin")).thenReturn(null);

        Object result = handler.execute(new CommandContext(
                42L,
                "com.auraboot.marketplace-server",
                "mkt",
                "mkt:publish_plugin_submission",
                "mkt_plugin_submission",
                "SUB-PID",
                Map.of(),
                Map.of(),
                false
        ));

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap).containsKeys("pluginPid", "versionPid", "publicPluginPid", "publicVersionPid");
        verify(dynamicDataMapper).insert(eq("mt_mkt_plugin"), argThat(row ->
                "approved-plugin".equals(row.get("mkt_plg_code"))
                        && "published".equals(row.get("mkt_plg_status"))
                        && "1.2.3".equals(row.get("mkt_plg_latest_version"))
        ));
        verify(dynamicDataMapper).insertWithJsonb(eq("ab_marketplace_plugin"), argThat(row ->
                "approved-plugin".equals(row.get("plugin_id"))
                        && "published".equals(row.get("status"))
                        && "1.2.3".equals(row.get("latest_version"))
        ), anySet());
        verify(dynamicDataMapper).insertWithJsonb(eq("ab_marketplace_version"), argThat(row ->
                "1.2.3".equals(row.get("version"))
                        && "published".equals(row.get("status"))
                        && "Initial public release".equals(row.get("changelog"))
        ), anySet());
    }

    @Test
    void approvePublisherApplicationCreatesVerifiedPublisher() throws Exception {
        DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);
        MarketplacePluginMapper pluginMapper = mock(MarketplacePluginMapper.class);
        MarketplaceVersionMapper versionMapper = mock(MarketplaceVersionMapper.class);
        MarketplacePublisherReviewCommandHandler handler =
                new MarketplacePublisherReviewCommandHandler(dynamicDataMapper, pluginMapper, versionMapper);

        when(dynamicDataMapper.selectByQuery(
                argThat(sql -> sql != null && sql.contains("mt_mkt_publisher_application")),
                anyMap()
        )).thenReturn(List.of(Map.of(
                "pid", "APP-PID",
                "mkt_pa_applicant_name", "Ada Reviewer",
                "mkt_pa_company_name", "Ada Review Labs",
                "mkt_pa_email", "ada@example.test",
                "mkt_pa_website", "https://ada.example.test",
                "mkt_pa_description", "Verified marketplace publisher"
        )));
        when(dynamicDataMapper.selectByQuery(
                argThat(sql -> sql != null && sql.contains("mt_mkt_publisher ") && sql.contains("mkt_pub_email")),
                anyMap()
        )).thenReturn(List.of());

        Object result = handler.execute(new CommandContext(
                42L,
                "com.auraboot.marketplace-server",
                "mkt",
                "mkt:approve_publisher_application",
                "mkt_publisher_application",
                "APP-PID",
                Map.of(),
                Map.of(),
                false
        ));

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap).containsEntry("applicationPid", "APP-PID");
        assertThat(resultMap.get("publisherPid")).isInstanceOf(String.class);
        verify(dynamicDataMapper).insert(eq("mt_mkt_publisher"), argThat(row ->
                "Ada Review Labs".equals(row.get("mkt_pub_name"))
                        && "ada@example.test".equals(row.get("mkt_pub_email"))
                        && "active".equals(row.get("mkt_pub_status"))
                        && Boolean.TRUE.equals(row.get("mkt_pub_verified"))
        ));
    }
}
