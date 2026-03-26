package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.ReconciliationItem;
import com.auraboot.framework.meta.entity.ReconciliationProfile;
import com.auraboot.framework.meta.entity.ReconciliationRun;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.ReconciliationItemMapper;
import com.auraboot.framework.meta.mapper.ReconciliationProfileMapper;
import com.auraboot.framework.meta.mapper.ReconciliationRunMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reconciliation service for automated matching of records between two data sources.
 * Implements a 3-pass matching algorithm:
 * <ol>
 *   <li>Pass 1: Exact reference number match</li>
 *   <li>Pass 2: Amount match within tolerance</li>
 *   <li>Pass 3: Fuzzy match (amount + date proximity) with confidence score</li>
 * </ol>
 *
 * Supports SUPPLIER (AP vs supplier invoices), BANK (transactions vs payments),
 * and INTERCOMPANY reconciliation types.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService extends BaseMetaService {

    private final ReconciliationProfileMapper profileMapper;
    private final ReconciliationRunMapper runMapper;
    private final ReconciliationItemMapper itemMapper;
    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metaModelService;

    // ==================== Profile CRUD ====================

    @Transactional
    public ReconciliationProfileDTO createProfile(ReconciliationProfileRequest request) {
        Long tenantId = getCurrentTenantId();
        validateProfileType(request.getProfileType());

        // Check duplicate profile code
        ReconciliationProfile existing = profileMapper.findByCode(request.getProfileCode());
        if (existing != null && tenantId.equals(existing.getTenantId())) {
            throw new MetaServiceException("Profile code already exists: " + request.getProfileCode());
        }

        ReconciliationProfile profile = new ReconciliationProfile();
        profile.setTenantId(tenantId);
        mapRequestToProfile(request, profile);
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        profile.setDeletedFlag(false);

        if (profile.getEnabled() == null) {
            profile.setEnabled(true);
        }
        if (profile.getAmountTolerance() == null) {
            profile.setAmountTolerance(new BigDecimal("0.01"));
        }
        if (profile.getDateToleranceDays() == null) {
            profile.setDateToleranceDays(3);
        }
        if (profile.getMatchByReference() == null) {
            profile.setMatchByReference(true);
        }
        if (profile.getMatchByAmount() == null) {
            profile.setMatchByAmount(true);
        }
        if (profile.getMatchByDate() == null) {
            profile.setMatchByDate(false);
        }

        profileMapper.insert(profile);
        log.info("Created reconciliation profile: {} (type={})", profile.getProfileCode(), profile.getProfileType());
        return toProfileDTO(profile);
    }

    @Transactional
    public ReconciliationProfileDTO updateProfile(Long id, ReconciliationProfileRequest request) {
        Long tenantId = getCurrentTenantId();
        ReconciliationProfile profile = profileMapper.selectById(id);
        if (profile == null || !tenantId.equals(profile.getTenantId())) {
            throw new MetaServiceException("Profile not found: " + id);
        }

        if (request.getProfileType() != null) {
            validateProfileType(request.getProfileType());
        }

        mapRequestToProfile(request, profile);
        profile.setUpdatedAt(Instant.now());
        profileMapper.updateById(profile);
        log.info("Updated reconciliation profile: {}", profile.getProfileCode());
        return toProfileDTO(profile);
    }

    @Transactional
    public void deleteProfile(Long id) {
        Long tenantId = getCurrentTenantId();
        ReconciliationProfile profile = profileMapper.selectById(id);
        if (profile == null || !tenantId.equals(profile.getTenantId())) {
            throw new MetaServiceException("Profile not found: " + id);
        }
        profileMapper.deleteById(id);
        log.info("Deleted reconciliation profile: {}", profile.getProfileCode());
    }

    public ReconciliationProfileDTO getProfile(Long id) {
        Long tenantId = getCurrentTenantId();
        ReconciliationProfile profile = profileMapper.selectById(id);
        if (profile == null || !tenantId.equals(profile.getTenantId())) {
            throw new MetaServiceException("Profile not found: " + id);
        }
        return toProfileDTO(profile);
    }

    public List<ReconciliationProfileDTO> listProfiles() {
        Long tenantId = getCurrentTenantId();
        QueryWrapper<ReconciliationProfile> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId);
        qw.orderByAsc("profile_name");
        List<ReconciliationProfile> profiles = profileMapper.selectList(qw);
        return profiles.stream().map(this::toProfileDTO).collect(Collectors.toList());
    }

    // ==================== Reconciliation Execution ====================

    /**
     * Start a reconciliation run.
     * Loads records from both sources, runs the 3-pass matching algorithm,
     * persists results, and returns the run code.
     */
    @Transactional
    public ReconciliationRunDTO startReconciliation(ReconciliationRunRequest request) {
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();

        ReconciliationProfile profile = profileMapper.selectById(request.getProfileId());
        if (profile == null || !tenantId.equals(profile.getTenantId())) {
            throw new MetaServiceException("Profile not found: " + request.getProfileId());
        }
        if (!Boolean.TRUE.equals(profile.getEnabled())) {
            throw new MetaServiceException("Profile is disabled: " + profile.getProfileCode());
        }

        // Create run record
        ReconciliationRun run = new ReconciliationRun();
        run.setTenantId(tenantId);
        run.setRunCode(generateRunCode(profile.getProfileCode()));
        run.setProfileId(profile.getId());
        run.setStatus(ReconciliationRun.STATUS_RUNNING);
        run.setPeriodStart(request.getPeriodStart());
        run.setPeriodEnd(request.getPeriodEnd());
        run.setStartedAt(Instant.now());
        run.setCreatedBy(userId);
        run.setCreatedAt(Instant.now());
        runMapper.insert(run);

        try {
            // Load source A records (internal)
            List<RecordEntry> sourceARecords = loadRecords(
                    tenantId, profile.getSourceAModel(),
                    profile.getSourceAAmountField(), profile.getSourceADateField(), profile.getSourceARefField(),
                    request.getPeriodStart(), request.getPeriodEnd());

            // Load source B records (external)
            List<RecordEntry> sourceBRecords = loadRecords(
                    tenantId, profile.getSourceBModel(),
                    profile.getSourceBAmountField(), profile.getSourceBDateField(), profile.getSourceBRefField(),
                    request.getPeriodStart(), request.getPeriodEnd());

            run.setTotalSourceA(sourceARecords.size());
            run.setTotalSourceB(sourceBRecords.size());

            // Run matching algorithm
            List<ReconciliationItem> items = runMatchingAlgorithm(
                    tenantId, run.getId(), profile, sourceARecords, sourceBRecords);

            // Persist items in batch
            for (ReconciliationItem item : items) {
                itemMapper.insert(item);
            }

            // Calculate statistics
            updateRunStatistics(run, items);
            run.setStatus(ReconciliationRun.STATUS_COMPLETED);
            run.setCompletedAt(Instant.now());
            runMapper.updateById(run);

            log.info("Reconciliation run completed: {} — matched={}, unmatchedA={}, unmatchedB={}, discrepancies={}",
                    run.getRunCode(), run.getMatchedCount(), run.getUnmatchedACount(),
                    run.getUnmatchedBCount(), run.getDiscrepancyCount());

        } catch (Exception e) {
            run.setStatus(ReconciliationRun.STATUS_FAILED);
            run.setErrorMessage(e.getMessage());
            run.setCompletedAt(Instant.now());
            runMapper.updateById(run);
            log.error("Reconciliation run failed: {}", run.getRunCode(), e);
            throw new MetaServiceException("Reconciliation failed: " + e.getMessage());
        }

        return toRunDTO(run, profile);
    }

    // ==================== Run Queries ====================

    public ReconciliationRunDTO getRunSummary(String runCode) {
        Long tenantId = getCurrentTenantId();
        ReconciliationRun run = runMapper.findByRunCode(runCode);
        if (run == null || !tenantId.equals(run.getTenantId())) {
            throw new MetaServiceException("Run not found: " + runCode);
        }
        ReconciliationProfile profile = profileMapper.selectById(run.getProfileId());
        return toRunDTO(run, profile);
    }

    public PaginationResult<ReconciliationRunDTO> listRuns(int pageNum, int pageSize) {
        Long tenantId = getCurrentTenantId();
        int offset = (pageNum - 1) * pageSize;

        QueryWrapper<ReconciliationRun> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId);
        qw.orderByDesc("created_at");
        long total = runMapper.selectCount(qw);

        qw.last("LIMIT " + pageSize + " OFFSET " + offset);
        List<ReconciliationRun> runs = runMapper.selectList(qw);

        // Batch load profiles
        Set<Long> profileIds = runs.stream().map(ReconciliationRun::getProfileId).collect(Collectors.toSet());
        Map<Long, ReconciliationProfile> profileMap = new HashMap<>();
        if (!profileIds.isEmpty()) {
            profileMapper.selectBatchIds(profileIds).forEach(p -> profileMap.put(p.getId(), p));
        }

        List<ReconciliationRunDTO> dtos = runs.stream()
                .map(r -> toRunDTO(r, profileMap.get(r.getProfileId())))
                .collect(Collectors.toList());

        return PaginationResult.of(dtos, total, pageNum, pageSize);
    }

    public PaginationResult<ReconciliationItemDTO> getRunItems(
            String runCode, String matchStatus, int pageNum, int pageSize) {
        Long tenantId = getCurrentTenantId();
        ReconciliationRun run = runMapper.findByRunCode(runCode);
        if (run == null || !tenantId.equals(run.getTenantId())) {
            throw new MetaServiceException("Run not found: " + runCode);
        }

        int offset = (pageNum - 1) * pageSize;
        List<ReconciliationItem> items;
        long total;

        if (matchStatus != null && !matchStatus.isBlank()) {
            items = itemMapper.findByRunIdAndStatus(run.getId(), matchStatus, pageSize, offset);
            total = itemMapper.countByRunIdAndStatus(run.getId(), matchStatus);
        } else {
            QueryWrapper<ReconciliationItem> qw = new QueryWrapper<>();
            qw.eq("run_id", run.getId());
            qw.orderByAsc("match_status", "id");
            total = itemMapper.selectCount(qw);
            qw.last("LIMIT " + pageSize + " OFFSET " + offset);
            items = itemMapper.selectList(qw);
        }

        List<ReconciliationItemDTO> dtos = items.stream()
                .map(this::toItemDTO)
                .collect(Collectors.toList());

        return PaginationResult.of(dtos, total, pageNum, pageSize);
    }

    // ==================== Item Resolution ====================

    @Transactional
    public ReconciliationItemDTO resolveItem(Long itemId, ReconciliationItemResolveRequest request) {
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();

        ReconciliationItem item = itemMapper.selectById(itemId);
        if (item == null || !tenantId.equals(item.getTenantId())) {
            throw new MetaServiceException("Item not found: " + itemId);
        }
        if (item.getResolution() != null) {
            throw new MetaServiceException("Item already resolved: " + itemId);
        }

        validateResolution(request.getResolution());

        item.setResolution(request.getResolution());
        item.setResolutionNotes(request.getNotes());
        item.setResolvedBy(userId);
        item.setResolvedAt(Instant.now());
        itemMapper.updateById(item);

        log.info("Resolved reconciliation item {} as {} by user {}", itemId, request.getResolution(), userId);
        return toItemDTO(item);
    }

    // ==================== Report ====================

    public ReconciliationReportDTO getReconciliationReport(String runCode) {
        Long tenantId = getCurrentTenantId();
        ReconciliationRun run = runMapper.findByRunCode(runCode);
        if (run == null || !tenantId.equals(run.getTenantId())) {
            throw new MetaServiceException("Run not found: " + runCode);
        }
        ReconciliationProfile profile = profileMapper.selectById(run.getProfileId());

        ReconciliationReportDTO report = new ReconciliationReportDTO();
        report.setRunCode(run.getRunCode());
        report.setProfileCode(profile != null ? profile.getProfileCode() : null);
        report.setProfileName(profile != null ? profile.getProfileName() : null);
        report.setProfileType(profile != null ? profile.getProfileType() : null);
        report.setStatus(run.getStatus());
        report.setPeriodStart(run.getPeriodStart());
        report.setPeriodEnd(run.getPeriodEnd());

        // Summary statistics
        report.setTotalSourceA(run.getTotalSourceA());
        report.setTotalSourceB(run.getTotalSourceB());
        report.setMatchedCount(run.getMatchedCount());
        report.setUnmatchedACount(run.getUnmatchedACount());
        report.setUnmatchedBCount(run.getUnmatchedBCount());
        report.setDiscrepancyCount(run.getDiscrepancyCount());
        report.setMatchedAmount(run.getMatchedAmount());
        report.setUnmatchedAAmount(run.getUnmatchedAAmount());
        report.setUnmatchedBAmount(run.getUnmatchedBAmount());

        // Match rate
        int total = Math.max(
                run.getTotalSourceA() != null ? run.getTotalSourceA() : 0,
                run.getTotalSourceB() != null ? run.getTotalSourceB() : 0);
        if (total > 0) {
            int matched = run.getMatchedCount() != null ? run.getMatchedCount() : 0;
            report.setMatchRate(BigDecimal.valueOf(matched * 100.0 / total)
                    .setScale(2, RoundingMode.HALF_UP));
        } else {
            report.setMatchRate(BigDecimal.ZERO);
        }

        // Resolution breakdown
        QueryWrapper<ReconciliationItem> qw = new QueryWrapper<>();
        qw.eq("run_id", run.getId());
        List<ReconciliationItem> allItems = itemMapper.selectList(qw);

        int resolvedCount = 0, pendingCount = 0, approvedCount = 0, adjustedCount = 0, writtenOffCount = 0;
        for (ReconciliationItem item : allItems) {
            if (item.getResolution() == null) {
                pendingCount++;
            } else {
                resolvedCount++;
                switch (item.getResolution()) {
                    case ReconciliationItem.RESOLUTION_APPROVED -> approvedCount++;
                    case ReconciliationItem.RESOLUTION_ADJUSTED -> adjustedCount++;
                    case ReconciliationItem.RESOLUTION_WRITTEN_OFF -> writtenOffCount++;
                }
            }
        }
        report.setResolvedCount(resolvedCount);
        report.setPendingCount(pendingCount);
        report.setApprovedCount(approvedCount);
        report.setAdjustedCount(adjustedCount);
        report.setWrittenOffCount(writtenOffCount);

        // Top discrepancies (largest amount_difference first, max 10)
        List<ReconciliationItemDTO> topDisc = allItems.stream()
                .filter(i -> ReconciliationItem.MATCH_DISCREPANCY.equals(i.getMatchStatus()))
                .sorted((a, b) -> {
                    BigDecimal absA = a.getAmountDifference() != null ? a.getAmountDifference().abs() : BigDecimal.ZERO;
                    BigDecimal absB = b.getAmountDifference() != null ? b.getAmountDifference().abs() : BigDecimal.ZERO;
                    return absB.compareTo(absA);
                })
                .limit(10)
                .map(this::toItemDTO)
                .collect(Collectors.toList());
        report.setTopDiscrepancies(topDisc);

        return report;
    }

    // ==================== Internal: Matching Algorithm ====================

    /**
     * Run the 3-pass matching algorithm.
     *
     * Pass 1: Exact reference number match (score 100).
     *   - If amounts differ within tolerance → MATCHED; outside → DISCREPANCY.
     *
     * Pass 2: Exact amount match within tolerance (for remaining unmatched).
     *   - Among candidates, prefer closest date. Score = 80-95.
     *
     * Pass 3: Fuzzy match using weighted (amount + date proximity) score.
     *   - Only consider candidates above a minimum score threshold (50).
     *   - Score = weighted combination of amount closeness + date closeness.
     *
     * All remaining unmatched records become UNMATCHED_A or UNMATCHED_B.
     */
    List<ReconciliationItem> runMatchingAlgorithm(
            Long tenantId, Long runId, ReconciliationProfile profile,
            List<RecordEntry> sourceA, List<RecordEntry> sourceB) {

        List<ReconciliationItem> results = new ArrayList<>();
        Set<Integer> matchedAIndices = new HashSet<>();
        Set<Integer> matchedBIndices = new HashSet<>();

        boolean useRef = Boolean.TRUE.equals(profile.getMatchByReference())
                && profile.getSourceARefField() != null && profile.getSourceBRefField() != null;
        boolean useAmount = Boolean.TRUE.equals(profile.getMatchByAmount());
        boolean useDate = Boolean.TRUE.equals(profile.getMatchByDate())
                && profile.getSourceADateField() != null && profile.getSourceBDateField() != null;

        BigDecimal amountTolerance = profile.getAmountTolerance() != null
                ? profile.getAmountTolerance() : new BigDecimal("0.01");
        int dateToleranceDays = profile.getDateToleranceDays() != null
                ? profile.getDateToleranceDays() : 3;

        // -- Pass 1: Exact reference match --
        if (useRef) {
            // Build index: ref → list of B indices
            Map<String, List<Integer>> refIndexB = new HashMap<>();
            for (int j = 0; j < sourceB.size(); j++) {
                String ref = sourceB.get(j).ref;
                if (ref != null && !ref.isBlank()) {
                    refIndexB.computeIfAbsent(ref.trim().toLowerCase(), k -> new ArrayList<>()).add(j);
                }
            }

            for (int i = 0; i < sourceA.size(); i++) {
                if (matchedAIndices.contains(i)) continue;
                String refA = sourceA.get(i).ref;
                if (refA == null || refA.isBlank()) continue;

                List<Integer> candidates = refIndexB.get(refA.trim().toLowerCase());
                if (candidates == null) continue;

                for (int j : candidates) {
                    if (matchedBIndices.contains(j)) continue;

                    RecordEntry a = sourceA.get(i);
                    RecordEntry b = sourceB.get(j);
                    BigDecimal amountDiff = calcAmountDifference(a.amount, b.amount);
                    int dateDiff = calcDateDifference(a.date, b.date);

                    ReconciliationItem item = createItem(tenantId, runId, a, b);
                    item.setAmountDifference(amountDiff);
                    item.setDateDifference(dateDiff);
                    item.setMatchScore(new BigDecimal("100.00"));

                    if (amountDiff.abs().compareTo(amountTolerance) <= 0) {
                        item.setMatchStatus(ReconciliationItem.MATCH_MATCHED);
                    } else {
                        item.setMatchStatus(ReconciliationItem.MATCH_DISCREPANCY);
                    }

                    results.add(item);
                    matchedAIndices.add(i);
                    matchedBIndices.add(j);
                    break;
                }
            }
        }

        // -- Pass 2: Amount match within tolerance --
        if (useAmount) {
            // Sort remaining B by amount for efficient search
            List<IndexedEntry> remainingB = new ArrayList<>();
            for (int j = 0; j < sourceB.size(); j++) {
                if (!matchedBIndices.contains(j) && sourceB.get(j).amount != null) {
                    remainingB.add(new IndexedEntry(j, sourceB.get(j)));
                }
            }
            remainingB.sort(Comparator.comparing(e -> e.entry.amount));

            for (int i = 0; i < sourceA.size(); i++) {
                if (matchedAIndices.contains(i)) continue;
                RecordEntry a = sourceA.get(i);
                if (a.amount == null) continue;

                // Binary search for amount range [a.amount - tolerance, a.amount + tolerance]
                BigDecimal lower = a.amount.subtract(amountTolerance);
                BigDecimal upper = a.amount.add(amountTolerance);

                IndexedEntry bestMatch = null;
                int bestDateDiff = Integer.MAX_VALUE;

                for (IndexedEntry be : remainingB) {
                    if (matchedBIndices.contains(be.index)) continue;
                    if (be.entry.amount.compareTo(lower) < 0) continue;
                    if (be.entry.amount.compareTo(upper) > 0) break;

                    // Within amount tolerance — check date closeness
                    int dateDiff = calcDateDifference(a.date, be.entry.date);
                    if (dateDiff < bestDateDiff) {
                        bestDateDiff = dateDiff;
                        bestMatch = be;
                    }
                }

                if (bestMatch != null) {
                    RecordEntry b = bestMatch.entry;
                    BigDecimal amountDiff = calcAmountDifference(a.amount, b.amount);

                    ReconciliationItem item = createItem(tenantId, runId, a, b);
                    item.setAmountDifference(amountDiff);
                    item.setDateDifference(bestDateDiff);

                    // Score: 80 base + up to 15 for date closeness
                    BigDecimal dateScore = BigDecimal.ZERO;
                    if (useDate && bestDateDiff <= dateToleranceDays) {
                        dateScore = BigDecimal.valueOf(15.0 * (1.0 - (double) bestDateDiff / Math.max(dateToleranceDays, 1)));
                    }
                    item.setMatchScore(new BigDecimal("80.00").add(dateScore).setScale(2, RoundingMode.HALF_UP));
                    item.setMatchStatus(ReconciliationItem.MATCH_MATCHED);

                    results.add(item);
                    matchedAIndices.add(i);
                    matchedBIndices.add(bestMatch.index);
                }
            }
        }

        // -- Pass 3: Fuzzy match (amount closeness + date proximity) --
        if (useAmount || useDate) {
            BigDecimal minScore = new BigDecimal("50.00");

            for (int i = 0; i < sourceA.size(); i++) {
                if (matchedAIndices.contains(i)) continue;
                RecordEntry a = sourceA.get(i);

                IndexedEntry bestCandidate = null;
                BigDecimal bestScore = BigDecimal.ZERO;

                for (int j = 0; j < sourceB.size(); j++) {
                    if (matchedBIndices.contains(j)) continue;
                    RecordEntry b = sourceB.get(j);

                    BigDecimal score = calculateFuzzyScore(a, b, amountTolerance, dateToleranceDays, useAmount, useDate);
                    if (score.compareTo(bestScore) > 0) {
                        bestScore = score;
                        bestCandidate = new IndexedEntry(j, b);
                    }
                }

                if (bestCandidate != null && bestScore.compareTo(minScore) >= 0) {
                    RecordEntry b = bestCandidate.entry;
                    BigDecimal amountDiff = calcAmountDifference(a.amount, b.amount);
                    int dateDiff = calcDateDifference(a.date, b.date);

                    ReconciliationItem item = createItem(tenantId, runId, a, b);
                    item.setAmountDifference(amountDiff);
                    item.setDateDifference(dateDiff);
                    item.setMatchScore(bestScore.setScale(2, RoundingMode.HALF_UP));

                    // Fuzzy matches with amount diff > tolerance are discrepancies
                    if (amountDiff.abs().compareTo(amountTolerance) <= 0) {
                        item.setMatchStatus(ReconciliationItem.MATCH_MATCHED);
                    } else {
                        item.setMatchStatus(ReconciliationItem.MATCH_DISCREPANCY);
                    }

                    results.add(item);
                    matchedAIndices.add(i);
                    matchedBIndices.add(bestCandidate.index);
                }
            }
        }

        // -- Remaining: unmatched records --
        for (int i = 0; i < sourceA.size(); i++) {
            if (matchedAIndices.contains(i)) continue;
            RecordEntry a = sourceA.get(i);
            ReconciliationItem item = new ReconciliationItem();
            item.setTenantId(tenantId);
            item.setRunId(runId);
            item.setMatchStatus(ReconciliationItem.MATCH_UNMATCHED_A);
            item.setSourceARecordId(a.recordId);
            item.setSourceARef(a.ref);
            item.setSourceAAmount(a.amount);
            item.setSourceADate(a.date);
            results.add(item);
        }

        for (int j = 0; j < sourceB.size(); j++) {
            if (matchedBIndices.contains(j)) continue;
            RecordEntry b = sourceB.get(j);
            ReconciliationItem item = new ReconciliationItem();
            item.setTenantId(tenantId);
            item.setRunId(runId);
            item.setMatchStatus(ReconciliationItem.MATCH_UNMATCHED_B);
            item.setSourceBRecordId(b.recordId);
            item.setSourceBRef(b.ref);
            item.setSourceBAmount(b.amount);
            item.setSourceBDate(b.date);
            results.add(item);
        }

        return results;
    }

    // ==================== Internal: Data Loading ====================

    /**
     * Load records from a model's dynamic table using DynamicDataMapper.
     * Resolves the table name from the model code via MetaModelService.
     */
    private List<RecordEntry> loadRecords(
            Long tenantId, String modelCode,
            String amountField, String dateField, String refField,
            LocalDate periodStart, LocalDate periodEnd) {

        String tableName = metaModelService.getTableName(modelCode);
        if (tableName == null || tableName.isBlank()) {
            throw new MetaServiceException("Cannot resolve table name for model: " + modelCode);
        }

        // Build SELECT columns
        List<String> columns = new ArrayList<>();
        columns.add("id");
        columns.add(amountField);
        if (dateField != null && !dateField.isBlank()) {
            columns.add(dateField);
        }
        if (refField != null && !refField.isBlank()) {
            columns.add(refField);
        }

        // Build WHERE clause
        StringBuilder where = new StringBuilder("tenant_id = " + tenantId);
        if (dateField != null && !dateField.isBlank()) {
            if (periodStart != null) {
                where.append(" AND ").append(dateField).append(" >= '").append(periodStart).append("'");
            }
            if (periodEnd != null) {
                where.append(" AND ").append(dateField).append(" <= '").append(periodEnd).append("'");
            }
        }

        List<Map<String, Object>> rows = dynamicDataMapper.queryList(
                tableName, columns, where.toString(), null, 50000, 0);

        // Convert to RecordEntry list
        return rows.stream()
                .map(row -> {
                    RecordEntry entry = new RecordEntry();
                    entry.recordId = toLong(row.get("id"));
                    entry.amount = toBigDecimal(row.get(amountField));
                    if (dateField != null) {
                        entry.date = toLocalDate(row.get(dateField));
                    }
                    if (refField != null) {
                        entry.ref = toString(row.get(refField));
                    }
                    return entry;
                })
                .filter(e -> e.recordId != null) // skip null IDs
                .collect(Collectors.toList());
    }

    // ==================== Internal: Score Calculation ====================

    /**
     * Calculate fuzzy match score (0-100) based on amount closeness and date proximity.
     * Amount weight: 60%, Date weight: 40% (when both are enabled).
     */
    private BigDecimal calculateFuzzyScore(
            RecordEntry a, RecordEntry b,
            BigDecimal amountTolerance, int dateToleranceDays,
            boolean useAmount, boolean useDate) {

        double amountScore = 0;
        double dateScore = 0;
        double amountWeight = 0;
        double dateWeight = 0;

        if (useAmount && a.amount != null && b.amount != null) {
            amountWeight = useDate ? 0.6 : 1.0;
            BigDecimal diff = a.amount.subtract(b.amount).abs();
            // Extend tolerance to 10x for fuzzy matching
            BigDecimal fuzzyTolerance = amountTolerance.multiply(BigDecimal.TEN);
            if (diff.compareTo(fuzzyTolerance) <= 0) {
                amountScore = 100.0 * (1.0 - diff.doubleValue() / fuzzyTolerance.doubleValue());
            }
        }

        if (useDate && a.date != null && b.date != null) {
            dateWeight = useAmount ? 0.4 : 1.0;
            long daysDiff = Math.abs(ChronoUnit.DAYS.between(a.date, b.date));
            // Extend tolerance to 3x for fuzzy matching
            int fuzzyDateTol = dateToleranceDays * 3;
            if (daysDiff <= fuzzyDateTol) {
                dateScore = 100.0 * (1.0 - (double) daysDiff / Math.max(fuzzyDateTol, 1));
            }
        }

        double totalWeight = amountWeight + dateWeight;
        if (totalWeight == 0) return BigDecimal.ZERO;

        double finalScore = (amountScore * amountWeight + dateScore * dateWeight) / totalWeight;
        return BigDecimal.valueOf(finalScore).setScale(2, RoundingMode.HALF_UP);
    }

    // ==================== Internal: Helpers ====================

    private String generateRunCode(String profileCode) {
        return "RUN-" + profileCode.toUpperCase() + "-" + System.currentTimeMillis();
    }

    private void updateRunStatistics(ReconciliationRun run, List<ReconciliationItem> items) {
        int matchedCount = 0, unmatchedACount = 0, unmatchedBCount = 0, discrepancyCount = 0;
        BigDecimal matchedAmount = BigDecimal.ZERO;
        BigDecimal unmatchedAAmount = BigDecimal.ZERO;
        BigDecimal unmatchedBAmount = BigDecimal.ZERO;

        for (ReconciliationItem item : items) {
            switch (item.getMatchStatus()) {
                case ReconciliationItem.MATCH_MATCHED -> {
                    matchedCount++;
                    if (item.getSourceAAmount() != null) {
                        matchedAmount = matchedAmount.add(item.getSourceAAmount());
                    }
                }
                case ReconciliationItem.MATCH_UNMATCHED_A -> {
                    unmatchedACount++;
                    if (item.getSourceAAmount() != null) {
                        unmatchedAAmount = unmatchedAAmount.add(item.getSourceAAmount());
                    }
                }
                case ReconciliationItem.MATCH_UNMATCHED_B -> {
                    unmatchedBCount++;
                    if (item.getSourceBAmount() != null) {
                        unmatchedBAmount = unmatchedBAmount.add(item.getSourceBAmount());
                    }
                }
                case ReconciliationItem.MATCH_DISCREPANCY -> {
                    discrepancyCount++;
                    if (item.getSourceAAmount() != null) {
                        matchedAmount = matchedAmount.add(item.getSourceAAmount());
                    }
                }
            }
        }

        run.setMatchedCount(matchedCount);
        run.setUnmatchedACount(unmatchedACount);
        run.setUnmatchedBCount(unmatchedBCount);
        run.setDiscrepancyCount(discrepancyCount);
        run.setMatchedAmount(matchedAmount);
        run.setUnmatchedAAmount(unmatchedAAmount);
        run.setUnmatchedBAmount(unmatchedBAmount);
    }

    private ReconciliationItem createItem(Long tenantId, Long runId, RecordEntry a, RecordEntry b) {
        ReconciliationItem item = new ReconciliationItem();
        item.setTenantId(tenantId);
        item.setRunId(runId);
        item.setSourceARecordId(a.recordId);
        item.setSourceARef(a.ref);
        item.setSourceAAmount(a.amount);
        item.setSourceADate(a.date);
        item.setSourceBRecordId(b.recordId);
        item.setSourceBRef(b.ref);
        item.setSourceBAmount(b.amount);
        item.setSourceBDate(b.date);
        return item;
    }

    private BigDecimal calcAmountDifference(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return BigDecimal.ZERO;
        if (a == null) return b.negate();
        if (b == null) return a;
        return a.subtract(b);
    }

    private int calcDateDifference(LocalDate a, LocalDate b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        return (int) Math.abs(ChronoUnit.DAYS.between(a, b));
    }

    private void validateProfileType(String type) {
        if (type == null) return;
        Set<String> valid = Set.of("supplier", "bank", "intercompany");
        if (!valid.contains(type.toUpperCase())) {
            throw new MetaServiceException("Invalid profile type: " + type + ". Must be one of: " + valid);
        }
    }

    private void validateResolution(String resolution) {
        Set<String> valid = Set.of(
                ReconciliationItem.RESOLUTION_APPROVED,
                ReconciliationItem.RESOLUTION_ADJUSTED,
                ReconciliationItem.RESOLUTION_WRITTEN_OFF);
        if (!valid.contains(resolution)) {
            throw new MetaServiceException("Invalid resolution: " + resolution + ". Must be one of: " + valid);
        }
    }

    private void mapRequestToProfile(ReconciliationProfileRequest request, ReconciliationProfile profile) {
        if (request.getProfileCode() != null) profile.setProfileCode(request.getProfileCode());
        if (request.getProfileName() != null) profile.setProfileName(request.getProfileName());
        if (request.getProfileType() != null) profile.setProfileType(request.getProfileType().toUpperCase());
        if (request.getDescription() != null) profile.setDescription(request.getDescription());
        if (request.getSourceAModel() != null) profile.setSourceAModel(request.getSourceAModel());
        if (request.getSourceAAmountField() != null) profile.setSourceAAmountField(request.getSourceAAmountField());
        if (request.getSourceADateField() != null) profile.setSourceADateField(request.getSourceADateField());
        if (request.getSourceARefField() != null) profile.setSourceARefField(request.getSourceARefField());
        if (request.getSourceBModel() != null) profile.setSourceBModel(request.getSourceBModel());
        if (request.getSourceBAmountField() != null) profile.setSourceBAmountField(request.getSourceBAmountField());
        if (request.getSourceBDateField() != null) profile.setSourceBDateField(request.getSourceBDateField());
        if (request.getSourceBRefField() != null) profile.setSourceBRefField(request.getSourceBRefField());
        if (request.getAmountTolerance() != null) profile.setAmountTolerance(request.getAmountTolerance());
        if (request.getDateToleranceDays() != null) profile.setDateToleranceDays(request.getDateToleranceDays());
        if (request.getMatchByReference() != null) profile.setMatchByReference(request.getMatchByReference());
        if (request.getMatchByAmount() != null) profile.setMatchByAmount(request.getMatchByAmount());
        if (request.getMatchByDate() != null) profile.setMatchByDate(request.getMatchByDate());
        if (request.getEnabled() != null) profile.setEnabled(request.getEnabled());
    }

    // ==================== DTO Mapping ====================

    private ReconciliationProfileDTO toProfileDTO(ReconciliationProfile entity) {
        ReconciliationProfileDTO dto = new ReconciliationProfileDTO();
        dto.setId(entity.getId());
        dto.setProfileCode(entity.getProfileCode());
        dto.setProfileName(entity.getProfileName());
        dto.setProfileType(entity.getProfileType());
        dto.setDescription(entity.getDescription());
        dto.setSourceAModel(entity.getSourceAModel());
        dto.setSourceAAmountField(entity.getSourceAAmountField());
        dto.setSourceADateField(entity.getSourceADateField());
        dto.setSourceARefField(entity.getSourceARefField());
        dto.setSourceBModel(entity.getSourceBModel());
        dto.setSourceBAmountField(entity.getSourceBAmountField());
        dto.setSourceBDateField(entity.getSourceBDateField());
        dto.setSourceBRefField(entity.getSourceBRefField());
        dto.setAmountTolerance(entity.getAmountTolerance());
        dto.setDateToleranceDays(entity.getDateToleranceDays());
        dto.setMatchByReference(entity.getMatchByReference());
        dto.setMatchByAmount(entity.getMatchByAmount());
        dto.setMatchByDate(entity.getMatchByDate());
        dto.setEnabled(entity.getEnabled());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private ReconciliationRunDTO toRunDTO(ReconciliationRun entity, ReconciliationProfile profile) {
        ReconciliationRunDTO dto = new ReconciliationRunDTO();
        dto.setId(entity.getId());
        dto.setRunCode(entity.getRunCode());
        dto.setProfileId(entity.getProfileId());
        dto.setProfileCode(profile != null ? profile.getProfileCode() : null);
        dto.setProfileName(profile != null ? profile.getProfileName() : null);
        dto.setStatus(entity.getStatus());
        dto.setPeriodStart(entity.getPeriodStart());
        dto.setPeriodEnd(entity.getPeriodEnd());
        dto.setTotalSourceA(entity.getTotalSourceA());
        dto.setTotalSourceB(entity.getTotalSourceB());
        dto.setMatchedCount(entity.getMatchedCount());
        dto.setUnmatchedACount(entity.getUnmatchedACount());
        dto.setUnmatchedBCount(entity.getUnmatchedBCount());
        dto.setDiscrepancyCount(entity.getDiscrepancyCount());
        dto.setMatchedAmount(entity.getMatchedAmount());
        dto.setUnmatchedAAmount(entity.getUnmatchedAAmount());
        dto.setUnmatchedBAmount(entity.getUnmatchedBAmount());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setStartedAt(entity.getStartedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private ReconciliationItemDTO toItemDTO(ReconciliationItem entity) {
        ReconciliationItemDTO dto = new ReconciliationItemDTO();
        dto.setId(entity.getId());
        dto.setRunId(entity.getRunId());
        dto.setMatchStatus(entity.getMatchStatus());
        dto.setSourceARecordId(entity.getSourceARecordId());
        dto.setSourceARef(entity.getSourceARef());
        dto.setSourceAAmount(entity.getSourceAAmount());
        dto.setSourceADate(entity.getSourceADate());
        dto.setSourceBRecordId(entity.getSourceBRecordId());
        dto.setSourceBRef(entity.getSourceBRef());
        dto.setSourceBAmount(entity.getSourceBAmount());
        dto.setSourceBDate(entity.getSourceBDate());
        dto.setAmountDifference(entity.getAmountDifference());
        dto.setDateDifference(entity.getDateDifference());
        dto.setMatchScore(entity.getMatchScore());
        dto.setResolution(entity.getResolution());
        dto.setResolutionNotes(entity.getResolutionNotes());
        dto.setResolvedBy(entity.getResolvedBy());
        dto.setResolvedAt(entity.getResolvedAt());
        return dto;
    }

    // ==================== Internal: Type Conversion ====================

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Long l) return l;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private LocalDate toLocalDate(Object val) {
        if (val == null) return null;
        if (val instanceof LocalDate ld) return ld;
        if (val instanceof java.sql.Date d) return d.toLocalDate();
        if (val instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        try { return LocalDate.parse(val.toString()); } catch (Exception e) { return null; }
    }

    private String toString(Object val) {
        return val != null ? val.toString() : null;
    }

    // ==================== Internal: Data Structures ====================

    /** Internal record representation for matching. */
    static class RecordEntry {
        Long recordId;
        BigDecimal amount;
        LocalDate date;
        String ref;
    }

    /** Indexed wrapper for sorted search. */
    private static class IndexedEntry {
        final int index;
        final RecordEntry entry;

        IndexedEntry(int index, RecordEntry entry) {
            this.index = index;
            this.entry = entry;
        }
    }
}
