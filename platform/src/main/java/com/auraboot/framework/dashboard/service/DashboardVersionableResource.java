package com.auraboot.framework.dashboard.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.dashboard.entity.Dashboard;
import com.auraboot.framework.dashboard.mapper.DashboardMapper;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.versioning.VersionableResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Dashboard implementation of VersionableResource.
 * Creates and applies snapshots for dashboard version management.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardVersionableResource implements VersionableResource {

    private final DashboardMapper dashboardMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getResourceType() {
        return "dashboard";
    }

    @Override
    public JsonNode createSnapshot(String resourceId) {
        Dashboard dashboard = dashboardMapper.findByPid(resourceId);
        if (dashboard == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND,
                    "Dashboard not found: " + resourceId);
        }

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("title", dashboard.getTitle());
        snapshot.put("description", dashboard.getDescription());
        snapshot.put("scope", dashboard.getScope());
        snapshot.put("status", dashboard.getStatus());
        snapshot.put("code", dashboard.getCode());
        snapshot.set("layoutConfig", dashboard.getLayoutConfig());
        snapshot.set("widgets", dashboard.getWidgets());
        snapshot.set("extension", dashboard.getExtension());

        if (dashboard.getIsDefault() != null) {
            snapshot.put("isDefault", dashboard.getIsDefault());
        }
        if (dashboard.getSortOrder() != null) {
            snapshot.put("sortOrder", dashboard.getSortOrder());
        }

        return snapshot;
    }

    @Override
    public void applySnapshot(String resourceId, JsonNode snapshot) {
        Dashboard dashboard = dashboardMapper.findByPid(resourceId);
        if (dashboard == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND,
                    "Dashboard not found: " + resourceId);
        }

        if (snapshot.has("title")) {
            dashboard.setTitle(snapshot.get("title").asText());
        }
        if (snapshot.has("description")) {
            dashboard.setDescription(snapshot.path("description").asText(null));
        }
        if (snapshot.has("scope")) {
            dashboard.setScope(snapshot.get("scope").asText());
        }
        if (snapshot.has("status")) {
            dashboard.setStatus(snapshot.get("status").asText());
        }
        if (snapshot.has("layoutConfig")) {
            dashboard.setLayoutConfig(snapshot.get("layoutConfig"));
        }
        if (snapshot.has("widgets")) {
            dashboard.setWidgets(snapshot.get("widgets"));
        }
        if (snapshot.has("extension")) {
            dashboard.setExtension(snapshot.get("extension"));
        }

        dashboard.setUpdatedAt(Instant.now());
        dashboard.setUpdatedBy(MetaContext.getCurrentUserPid());

        dashboardMapper.updateDashboard(dashboard);

        log.info("Applied version snapshot to dashboard: {}", resourceId);
    }
}
