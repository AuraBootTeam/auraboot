package com.auraboot.framework.bi.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.dao.entity.ReportEntity;
import com.auraboot.framework.bi.dao.mapper.ReportMapper;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.versioning.VersionableResource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** Report definitions reuse the platform snapshot and rollback engine. */
@Component
@RequiredArgsConstructor
public class ReportVersionableResource implements VersionableResource {
    private final ReportMapper mapper;
    private final ObjectMapper json;

    @Override public String getResourceType() { return "report"; }

    private ReportEntity owned(String pid) {
        ReportEntity report = mapper.selectOne(new LambdaQueryWrapper<ReportEntity>()
                .eq(ReportEntity::getPid, pid).eq(ReportEntity::getTenantId, MetaContext.getCurrentTenantId()));
        if (report == null) throw new ValidationException(ResponseCode.NOT_FOUND, "Report not found");
        return report;
    }

    @Override public JsonNode createSnapshot(String pid) {
        ReportEntity report = owned(pid);
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("title", report.getTitle());
        snapshot.put("profile", report.getProfile());
        snapshot.put("status", report.getStatus());
        try {
            snapshot.set("dsl", json.readTree(report.getDsl()));
        } catch (JsonProcessingException e) {
            throw new ValidationException(ResponseCode.SystemError, "Invalid report definition");
        }
        return snapshot;
    }

    @Override public void applySnapshot(String pid, JsonNode snapshot) {
        ReportEntity report = owned(pid);
        if (!snapshot.path("dsl").isObject()) {
            throw new ValidationException(ResponseCode.BadParam, "Invalid report version");
        }
        report.setTitle(snapshot.path("title").asText());
        report.setProfile(snapshot.path("profile").asText());
        report.setStatus(snapshot.path("status").asText());
        report.setDsl(snapshot.get("dsl").toString());
        report.setVersion(report.getVersion() == null ? 1 : report.getVersion() + 1);
        report.setUpdatedBy(MetaContext.getCurrentUserId());
        report.setUpdatedAt(Instant.now());
        if (mapper.updateById(report) != 1) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Report no longer available");
        }
    }
}
