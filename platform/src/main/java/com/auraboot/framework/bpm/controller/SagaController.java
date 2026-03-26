package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.chain.CommandChainResult;
import com.auraboot.framework.bpm.chain.saga.SagaExecution;
import com.auraboot.framework.bpm.chain.saga.SagaExecutor;
import com.auraboot.framework.bpm.chain.saga.SagaStateManager;
import com.auraboot.framework.bpm.chain.saga.SagaStep;
import com.auraboot.framework.bpm.dto.SagaExecutionDTO;
import com.auraboot.framework.bpm.mapper.SagaExecutionMapper;
import com.auraboot.framework.common.dto.ApiResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/saga")
@RequiredArgsConstructor
@Tag(name = "Saga Orchestration", description = "SAGA mode chain execution management")
public class SagaController {

    private final SagaStateManager stateManager;
    private final SagaExecutor sagaExecutor;
    private final SagaExecutionMapper executionMapper;

    @GetMapping("/{sagaId}")
    @Operation(summary = "Get saga execution detail with steps")
    public ApiResponse<SagaExecutionDTO> getSagaDetail(@PathVariable String sagaId) {
        SagaExecution exec = stateManager.getSagaExecution(sagaId);
        if (exec == null) {
            return ApiResponse.error("Saga not found: " + sagaId);
        }
        List<SagaStep> steps = stateManager.getSteps(sagaId);
        return ApiResponse.success(SagaExecutionDTO.fromEntity(exec, steps));
    }

    @PostMapping("/{sagaId}/retry")
    @Operation(summary = "Retry a failed saga from the failed step")
    public ApiResponse<CommandChainResult> retrySaga(@PathVariable String sagaId) {
        try {
            CommandChainResult result = sagaExecutor.retryFromFailed(sagaId);
            return ApiResponse.success(result);
        } catch (IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping
    @Operation(summary = "List saga executions")
    public ApiResponse<Page<SagaExecutionDTO>> listSagas(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String chainCode) {
        QueryWrapper<SagaExecution> qw = new QueryWrapper<SagaExecution>()
                .orderByDesc("started_at");
        if (status != null && !status.isBlank()) {
            qw.eq("status", status);
        }
        if (chainCode != null && !chainCode.isBlank()) {
            qw.eq("chain_code", chainCode);
        }

        Page<SagaExecution> page = executionMapper.selectPage(
                new Page<>(pageNum, pageSize), qw);

        Page<SagaExecutionDTO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream()
                .map(exec -> SagaExecutionDTO.fromEntity(exec, null))
                .toList());
        return ApiResponse.success(result);
    }
}
