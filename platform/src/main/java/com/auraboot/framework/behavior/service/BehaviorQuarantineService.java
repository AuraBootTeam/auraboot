package com.auraboot.framework.behavior.service;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.dto.BehaviorQuarantineReplayBatchResult;
import com.auraboot.framework.behavior.dto.BehaviorQuarantineReplayResult;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.auraboot.framework.behavior.entity.BehaviorQuarantine;
import com.auraboot.framework.behavior.ingest.BehaviorEventEntityFactory;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.behavior.mapper.BehaviorQuarantineMapper;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.PaginationSafetyUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BehaviorQuarantineService {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_REPLAYED = "replayed";
    public static final String STATUS_DUPLICATE = "duplicate";
    public static final String STATUS_FAILED = "failed";

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_REPLAY_LIMIT = 200;
    private static final int MAX_DETAIL = 500;

    private final BehaviorQuarantineMapper quarantineMapper;
    private final BehaviorEventMapper behaviorEventMapper;
    private final ObjectMapper objectMapper;

    public PageResult<BehaviorQuarantine> list(Long tenantId,
                                               String reason,
                                               String replayStatus,
                                               int page,
                                               int size) {
        int safePage = PaginationSafetyUtils.zeroBasedPageNumber(page);
        int safeSize = PaginationSafetyUtils.pageSize(size, MAX_PAGE_SIZE);

        LambdaQueryWrapper<BehaviorQuarantine> query = new LambdaQueryWrapper<BehaviorQuarantine>()
                .eq(BehaviorQuarantine::getTenantId, tenantId)
                .orderByDesc(BehaviorQuarantine::getQuarantinedAt)
                .orderByDesc(BehaviorQuarantine::getId);
        if (StringUtils.hasText(reason)) {
            query.eq(BehaviorQuarantine::getReason, reason.trim());
        }
        if (StringUtils.hasText(replayStatus)) {
            query.eq(BehaviorQuarantine::getReplayStatus, replayStatus.trim());
        }

        Page<BehaviorQuarantine> result = quarantineMapper.selectPage(new Page<>(safePage + 1L, safeSize), query);
        return PageResult.of(result);
    }

    public BehaviorQuarantineReplayResult replayOne(Long tenantId, Long quarantineId) {
        BehaviorQuarantine quarantine = quarantineMapper.selectById(quarantineId);
        if (quarantine == null || !tenantId.equals(quarantine.getTenantId())) {
            return new BehaviorQuarantineReplayResult(quarantineId, "not_found", null, null, "quarantine row not found");
        }
        if (!STATUS_PENDING.equals(nullToPending(quarantine.getReplayStatus()))) {
            return new BehaviorQuarantineReplayResult(
                    quarantine.getId(),
                    quarantine.getReplayStatus(),
                    quarantine.getEventId(),
                    quarantine.getReplayedBehaviorEventId(),
                    quarantine.getReplayDetail());
        }

        BehaviorEventInput input;
        try {
            input = readRawEvent(quarantine);
        } catch (JsonProcessingException ex) {
            return mark(quarantine, STATUS_FAILED, null, null, "raw_event is not valid JSON: " + ex.getOriginalMessage());
        }

        String validationError = validate(input);
        if (validationError != null) {
            return mark(quarantine, STATUS_FAILED, input == null ? null : input.getEventId(), null, validationError);
        }

        fillQuarantineSnapshot(quarantine, input);
        Long existing = behaviorEventMapper.findIdByTenantAndEventId(tenantId, input.getEventId());
        if (existing != null) {
            return mark(quarantine, STATUS_DUPLICATE, input.getEventId(), existing, null);
        }

        try {
            BehaviorEvent event = BehaviorEventEntityFactory.toEntity(input, tenantId, quarantine.getUserId(), objectMapper);
            behaviorEventMapper.insert(event);
            Long behaviorEventId = event.getId();
            if (behaviorEventId == null) {
                behaviorEventId = behaviorEventMapper.findIdByTenantAndEventId(tenantId, input.getEventId());
            }
            return mark(quarantine, STATUS_REPLAYED, input.getEventId(), behaviorEventId, null);
        } catch (DuplicateKeyException ex) {
            Long duplicateId = behaviorEventMapper.findIdByTenantAndEventId(tenantId, input.getEventId());
            return mark(quarantine, STATUS_DUPLICATE, input.getEventId(), duplicateId, null);
        } catch (DataIntegrityViolationException ex) {
            return mark(quarantine, STATUS_FAILED, input.getEventId(), null, mostSpecific(ex));
        }
    }

    public BehaviorQuarantineReplayBatchResult replayPending(Long tenantId, String reason, int limit) {
        int safeLimit = PaginationSafetyUtils.pageSize(limit, MAX_REPLAY_LIMIT);
        LambdaQueryWrapper<BehaviorQuarantine> query = new LambdaQueryWrapper<BehaviorQuarantine>()
                .select(BehaviorQuarantine::getId)
                .eq(BehaviorQuarantine::getTenantId, tenantId)
                .eq(BehaviorQuarantine::getReplayStatus, STATUS_PENDING)
                .orderByAsc(BehaviorQuarantine::getQuarantinedAt)
                .orderByAsc(BehaviorQuarantine::getId)
                .last("LIMIT " + safeLimit);
        if (StringUtils.hasText(reason)) {
            query.eq(BehaviorQuarantine::getReason, reason.trim());
        }

        List<BehaviorQuarantine> rows = quarantineMapper.selectList(query);
        List<BehaviorQuarantineReplayResult> results = new ArrayList<>();
        int replayed = 0;
        int duplicate = 0;
        int failed = 0;
        for (BehaviorQuarantine row : rows) {
            BehaviorQuarantineReplayResult result = replayOne(tenantId, row.getId());
            results.add(result);
            if (STATUS_REPLAYED.equals(result.status())) {
                replayed++;
            } else if (STATUS_DUPLICATE.equals(result.status())) {
                duplicate++;
            } else if (STATUS_FAILED.equals(result.status())) {
                failed++;
            }
        }
        return new BehaviorQuarantineReplayBatchResult(results.size(), replayed, duplicate, failed, results);
    }

    private BehaviorEventInput readRawEvent(BehaviorQuarantine quarantine) throws JsonProcessingException {
        if (!StringUtils.hasText(quarantine.getRawEvent())) {
            return null;
        }
        return objectMapper.readValue(quarantine.getRawEvent(), BehaviorEventInput.class);
    }

    private String validate(BehaviorEventInput input) {
        if (input == null) {
            return "raw_event is required";
        }
        if (!StringUtils.hasText(input.getEventId())) {
            return "event_id is required";
        }
        if (!StringUtils.hasText(input.getEventName())) {
            return "event_name is required";
        }
        return null;
    }

    private void fillQuarantineSnapshot(BehaviorQuarantine quarantine, BehaviorEventInput input) {
        if (!StringUtils.hasText(quarantine.getEventId())) {
            quarantine.setEventId(input.getEventId());
        }
        if (!StringUtils.hasText(quarantine.getEventName())) {
            quarantine.setEventName(input.getEventName());
        }
        if (!StringUtils.hasText(quarantine.getAnonId())) {
            quarantine.setAnonId(input.getAnonId());
        }
    }

    private BehaviorQuarantineReplayResult mark(BehaviorQuarantine quarantine,
                                                String status,
                                                String eventId,
                                                Long behaviorEventId,
                                                String detail) {
        quarantine.setReplayStatus(status);
        quarantine.setReplayDetail(truncate(detail));
        quarantine.setReplayedBehaviorEventId(behaviorEventId);
        quarantine.setReplayedAt(Instant.now());
        quarantineMapper.updateById(quarantine);
        return new BehaviorQuarantineReplayResult(
                quarantine.getId(),
                status,
                eventId != null ? eventId : quarantine.getEventId(),
                behaviorEventId,
                quarantine.getReplayDetail());
    }

    private String nullToPending(String replayStatus) {
        return StringUtils.hasText(replayStatus) ? replayStatus : STATUS_PENDING;
    }

    private String mostSpecific(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String msg = cause != null ? cause.getMessage() : ex.getMessage();
        if (msg == null) {
            return "constraint violation";
        }
        return truncate(msg.replaceAll("\\s+", " ").trim());
    }

    private String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
    }
}
