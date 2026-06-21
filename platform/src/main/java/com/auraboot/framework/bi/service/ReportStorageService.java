package com.auraboot.framework.bi.service;

import com.auraboot.framework.bi.dao.entity.ReportEntity;
import com.auraboot.framework.bi.dao.mapper.ReportMapper;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Storage service for first-class low-code reports ({@code ab_report}, Phase 4 slice 1).
 *
 * <p>PURELY ADDITIVE: no controller/API wires this yet — that is a later slice. The report
 * designer continues to persist via {@code ab_page_schema} + {@code extension.reportDsl};
 * this service is the minimal CRUD spine for the eventual storage graduation.
 *
 * <p>Soft-delete uses the standard platform MyBatis-Plus logic-delete: the entity's
 * {@code @TableLogic deletedFlag} (BOOLEAN {@code deleted_flag}) is driven by the global
 * interceptor, so {@code deleteById} performs the soft delete and every finder
 * auto-excludes deleted rows (no explicit {@code deleted_flag} predicate). Finders remain
 * tenant-scoped.
 */
@Service
@RequiredArgsConstructor
public class ReportStorageService {

    private final ReportMapper reportMapper;

    /**
     * Persist a new report. Mints a ULID {@code pid} (if absent) and stamps audit/version/
     * status defaults. Returns the inserted entity (with generated {@code id} and {@code pid}).
     */
    @Transactional
    public ReportEntity create(ReportEntity report) {
        if (report.getPid() == null || report.getPid().isBlank()) {
            report.setPid(UniqueIdGenerator.generate());
        }
        if (report.getProfile() == null || report.getProfile().isBlank()) {
            report.setProfile("paged-media");
        }
        if (report.getStatus() == null || report.getStatus().isBlank()) {
            report.setStatus("draft");
        }
        if (report.getDsl() == null || report.getDsl().isBlank()) {
            report.setDsl("{}");
        }
        if (report.getVersion() == null) {
            report.setVersion(1);
        }
        // Stamp the live state so the returned (un-refreshed) entity mirrors the persisted row;
        // the @TableLogic interceptor leaves inserts alone, the DB default is also FALSE.
        report.setDeletedFlag(false);
        Instant now = Instant.now();
        report.setCreatedAt(now);
        report.setUpdatedAt(now);
        reportMapper.insert(report);
        return report;
    }

    /**
     * Look up a live report by its {@code pid}. Soft-deleted rows are auto-excluded by the
     * global {@code @TableLogic} interceptor.
     *
     * @return the report, or {@code null} if not found / soft-deleted
     */
    public ReportEntity findByPid(String pid) {
        return reportMapper.selectOne(new LambdaQueryWrapper<ReportEntity>()
                .eq(ReportEntity::getPid, pid));
    }

    /**
     * Update an existing (live) report identified by {@code pid}. Bumps {@code updated_at}.
     * Title / profile / dsl / status / version / updatedBy are written; pid / tenant / id are not.
     *
     * @return {@code true} if a live row was updated
     */
    @Transactional
    public boolean update(ReportEntity report) {
        // dsl is a String -> jsonb column. LambdaUpdateWrapper.set does NOT route through the
        // entity typeHandler, so write the whole entity via updateById (which does).
        ReportEntity existing = findByPid(report.getPid());
        if (existing == null) {
            return false;
        }
        existing.setTitle(report.getTitle());
        existing.setProfile(report.getProfile());
        existing.setStatus(report.getStatus());
        existing.setVersion(report.getVersion());
        existing.setUpdatedBy(report.getUpdatedBy());
        existing.setUpdatedAt(Instant.now());
        existing.setDsl(report.getDsl());
        return reportMapper.updateById(existing) > 0;
    }

    /**
     * Idempotent upsert keyed by the supplied {@code pid}: create a live row with the GIVEN
     * pid if none exists, else patch the existing live row's title/profile/dsl (+bump version).
     *
     * <p>This is the REST-idempotent semantics for {@code PUT /{pid}} — a {@code PUT} to a not-yet
     * existing resource id MAY create it, so a client that owns the id (here the page pid the report
     * designer already minted) can sync a shadow without first knowing whether the row exists. The
     * supplied {@code pid} is honored verbatim (never re-minted), which is what makes
     * {@code ab_report.pid == page.pid} hold for the Phase 4 transition dual-write.
     *
     * <p>{@code code} is required only on the create branch (it is tenant-unique and {@code NOT NULL});
     * it is ignored when the row already exists, since {@code code} is immutable on update.
     *
     * @param report carries the supplied {@code pid}, {@code tenantId}, {@code code} (create only),
     *               {@code title}, {@code profile}, {@code dsl}, and {@code createdBy/updatedBy}
     * @return the persisted entity (created or updated)
     */
    @Transactional
    public ReportEntity upsertByPid(ReportEntity report) {
        ReportEntity existing = findByPid(report.getPid());
        if (existing == null) {
            // CREATE branch: honor the supplied pid verbatim (create() only mints when absent).
            return create(report);
        }
        // UPDATE branch: code/tenant/id are immutable; patch the mutable fields + bump version.
        existing.setTitle(report.getTitle());
        existing.setProfile(report.getProfile());
        if (report.getStatus() != null) {
            existing.setStatus(report.getStatus());
        }
        existing.setDsl(report.getDsl());
        existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
        existing.setUpdatedBy(report.getUpdatedBy());
        existing.setUpdatedAt(Instant.now());
        reportMapper.updateById(existing);
        return existing;
    }

    /**
     * Soft-delete a report by {@code pid}: the standard MyBatis-Plus logic-delete sets
     * {@code deleted_flag = true} via {@code deleteById}. After this, {@link #findByPid} and
     * {@link #listByTenant} no longer return it (the {@code @TableLogic} interceptor excludes it).
     *
     * @return {@code true} if a live row was soft-deleted
     */
    @Transactional
    public boolean softDelete(String pid) {
        ReportEntity existing = findByPid(pid);
        if (existing == null) {
            return false;
        }
        return reportMapper.deleteById(existing.getId()) > 0;
    }

    /**
     * List all live reports for a tenant, newest first. Tenant-scoped; soft-deleted rows are
     * auto-excluded by the global {@code @TableLogic} interceptor.
     */
    public List<ReportEntity> listByTenant(Long tenantId) {
        return reportMapper.selectList(new LambdaQueryWrapper<ReportEntity>()
                .eq(ReportEntity::getTenantId, tenantId)
                .orderByDesc(ReportEntity::getCreatedAt));
    }
}
