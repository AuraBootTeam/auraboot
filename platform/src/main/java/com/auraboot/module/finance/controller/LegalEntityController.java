package com.auraboot.module.finance.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.module.finance.dto.IntercompanyTxnCreateRequest;
import com.auraboot.module.finance.dto.LegalEntityCreateRequest;
import com.auraboot.module.finance.dto.LegalEntityTree;
import com.auraboot.module.finance.entity.IntercompanyTxn;
import com.auraboot.module.finance.entity.LegalEntity;
import com.auraboot.module.finance.mapper.IntercompanyTxnMapper;
import com.auraboot.module.finance.service.LegalEntityService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * REST controller for legal entity management and intercompany transactions.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /api/finance/legal-entities}           — list all entities for the current tenant</li>
 *   <li>{@code POST   /api/finance/legal-entities}           — create an entity</li>
 *   <li>{@code GET    /api/finance/legal-entities/tree}      — entity hierarchy tree</li>
 *   <li>{@code GET    /api/finance/legal-entities/{id}}      — get single entity</li>
 *   <li>{@code PUT    /api/finance/legal-entities/{id}}      — update an entity</li>
 *   <li>{@code DELETE /api/finance/legal-entities/{id}}      — delete an entity</li>
 *   <li>{@code GET    /api/finance/intercompany-txns}        — list intercompany transactions</li>
 *   <li>{@code POST   /api/finance/intercompany-txns}        — record an intercompany transaction</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Legal Entity", description = "Legal entity hierarchy and intercompany transactions for consolidated reporting")
public class LegalEntityController {

    private final LegalEntityService legalEntityService;
    private final IntercompanyTxnMapper intercompanyTxnMapper;

    // ==================== Legal Entity ====================

    @GetMapping("/api/finance/legal-entities")
    @Operation(summary = "List all legal entities for the current tenant")
    public ApiResponse<List<LegalEntity>> list() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(legalEntityService.findAll(tenantId));
    }

    @PostMapping("/api/finance/legal-entities")
    @Operation(summary = "Create a legal entity")
    public ApiResponse<LegalEntity> create(@Valid @RequestBody LegalEntityCreateRequest req) {
        try {
            return ApiResponse.success(legalEntityService.create(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/api/finance/legal-entities/tree")
    @Operation(summary = "Get entity hierarchy tree for the current tenant")
    public ApiResponse<List<LegalEntityTree>> tree() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(legalEntityService.buildHierarchy(tenantId));
    }

    @GetMapping("/api/finance/legal-entities/{id}")
    @Operation(summary = "Get a legal entity by id")
    public ApiResponse<LegalEntity> getById(@PathVariable Long id) {
        try {
            return ApiResponse.success(legalEntityService.findById(id));
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PutMapping("/api/finance/legal-entities/{id}")
    @Operation(summary = "Update a legal entity")
    public ApiResponse<LegalEntity> update(@PathVariable Long id,
                                           @Valid @RequestBody LegalEntityCreateRequest req) {
        try {
            return ApiResponse.success(legalEntityService.update(id, req));
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/api/finance/legal-entities/{id}")
    @Operation(summary = "Delete a legal entity (must have no children)")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            legalEntityService.delete(id);
            return ApiResponse.success(null);
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== Intercompany Transactions ====================

    @PostMapping("/api/finance/intercompany-txns")
    @Operation(summary = "Record an intercompany transaction")
    public ApiResponse<IntercompanyTxn> createTxn(@Valid @RequestBody IntercompanyTxnCreateRequest req) {
        Long tenantId = MetaContext.getCurrentTenantId();

        IntercompanyTxn txn = new IntercompanyTxn();
        txn.setId(IdWorker.getId());
        txn.setPid(UniqueIdGenerator.generate());
        txn.setTenantId(tenantId);
        txn.setFromEntityId(req.getFromEntityId());
        txn.setToEntityId(req.getToEntityId());
        txn.setTxnDate(req.getTxnDate());
        txn.setTxnType(req.getTxnType());
        txn.setAmount(req.getAmount());
        txn.setCurrency(req.getCurrency());
        txn.setDescription(req.getDescription());
        txn.setIsEliminated(false);
        txn.setCreatedAt(Instant.now());

        intercompanyTxnMapper.insert(txn);
        return ApiResponse.success(txn);
    }

    @GetMapping("/api/finance/intercompany-txns")
    @Operation(summary = "List intercompany transactions for the current tenant",
               description = "Pass pendingOnly=true to return only non-eliminated transactions")
    public ApiResponse<List<IntercompanyTxn>> listTxns(
            @RequestParam(defaultValue = "false") boolean pendingOnly) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (pendingOnly) {
            return ApiResponse.success(intercompanyTxnMapper.findPendingEliminations(tenantId));
        }
        List<IntercompanyTxn> all = intercompanyTxnMapper.selectList(
                new LambdaQueryWrapper<IntercompanyTxn>()
                        .eq(IntercompanyTxn::getTenantId, tenantId)
                        .orderByDesc(IntercompanyTxn::getTxnDate)
        );
        return ApiResponse.success(all);
    }
}
