package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.formula.FormulaFunctionRegistry;
import com.auraboot.framework.meta.service.VirtualFieldEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Formula API Controller
 * Provides endpoints for formula functions and expression evaluation
 *
 * @author AuraBoot Framework
 * @since 2.3.0
 */
@Tag(name = "公式字段", description = "公式函数和表达式")
@RestController
@RequestMapping("/api/meta/formula")
@RequiredArgsConstructor
public class FormulaController {

    private final FormulaFunctionRegistry functionRegistry;
    private final VirtualFieldEngine virtualFieldEngine;
    private final com.auraboot.framework.meta.formula.CrossTableFormulaService crossTableFormulaService;

    @Operation(summary = "获取所有公式函数")
    @GetMapping("/functions")
    public ApiResponse<List<FormulaFunctionRegistry.FunctionInfo>> getAllFunctions() {
        List<FormulaFunctionRegistry.FunctionInfo> functions = new java.util.ArrayList<>(functionRegistry.getAllFunctions());
        // Add cross-table function metadata
        functions.add(new FormulaFunctionRegistry.FunctionInfo(
                "lookup", "Look up a value from another model",
                "cross-table", "#LOOKUP('targetModel', 'lookupField', lookupValue, 'returnField')",
                new Class<?>[]{String.class, String.class, Object.class, String.class}));
        functions.add(new FormulaFunctionRegistry.FunctionInfo(
                "vlookup", "Vertical lookup with exact or fuzzy match",
                "cross-table", "#VLOOKUP(lookupValue, 'targetModel', 'lookupField', 'returnField', true)",
                new Class<?>[]{Object.class, String.class, String.class, String.class, boolean.class}));
        functions.add(new FormulaFunctionRegistry.FunctionInfo(
                "related", "Get all related values from another model",
                "cross-table", "#RELATED('targetModel', 'foreignKey', currentId, 'returnField')",
                new Class<?>[]{String.class, String.class, Object.class, String.class}));
        functions.add(new FormulaFunctionRegistry.FunctionInfo(
                "countif", "Count records matching condition in another model",
                "cross-table", "#COUNTIF('targetModel', 'condField', condValue)",
                new Class<?>[]{String.class, String.class, Object.class}));
        functions.add(new FormulaFunctionRegistry.FunctionInfo(
                "sumif", "Sum values matching condition in another model",
                "cross-table", "#SUMIF('targetModel', 'condField', condValue, 'sumField')",
                new Class<?>[]{String.class, String.class, Object.class, String.class}));
        return ApiResponse.success(functions);
    }

    @Operation(summary = "按分类获取公式函数")
    @GetMapping("/functions/{category}")
    public ApiResponse<List<FormulaFunctionRegistry.FunctionInfo>> getFunctionsByCategory(
            @PathVariable String category) {
        return ApiResponse.success(functionRegistry.getFunctionsByCategory(category));
    }

    @Operation(summary = "预览公式结果")
    @PostMapping("/preview")
    public ApiResponse<Object> previewFormula(@RequestBody FormulaPreviewRequest request) {
        try {
            Object result = virtualFieldEngine.evaluate(request.expression(), request.context());
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error("Formula evaluation failed: " + e.getMessage());
        }
    }

    @Operation(summary = "验证公式语法")
    @PostMapping("/validate")
    public ApiResponse<ValidationResult> validateFormula(@RequestBody FormulaValidateRequest request) {
        try {
            // Try to evaluate with empty context to check syntax
            virtualFieldEngine.evaluate(request.expression(), Map.of());
            return ApiResponse.success(new ValidationResult(true, null));
        } catch (Exception e) {
            return ApiResponse.success(new ValidationResult(false, e.getMessage()));
        }
    }

    @Operation(summary = "执行跨表查询")
    @PostMapping("/cross-table/lookup")
    public ApiResponse<Object> crossTableLookup(@RequestBody CrossTableRequest request) {
        try {
            Object result = switch (request.function()) {
                case "lookup" -> crossTableFormulaService.lookup(
                        request.targetModel(), request.lookupField(), request.lookupValue(), request.returnField());
                case "vlookup" -> crossTableFormulaService.vlookup(
                        request.lookupValue(), request.targetModel(), request.lookupField(), request.returnField(),
                        request.exactMatch() != null ? request.exactMatch() : true);
                case "related" -> crossTableFormulaService.related(
                        request.targetModel(), request.lookupField(), request.lookupValue(), request.returnField());
                case "countif" -> crossTableFormulaService.countIf(
                        request.targetModel(), request.lookupField(), request.lookupValue());
                case "sumif" -> crossTableFormulaService.sumIf(
                        request.targetModel(), request.lookupField(), request.lookupValue(), request.returnField());
                default -> null;
            };
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error("Cross-table lookup failed: " + e.getMessage());
        }
    }

    public record CrossTableRequest(
        String function,
        String targetModel,
        String lookupField,
        Object lookupValue,
        String returnField,
        Boolean exactMatch
    ) {}

    public record FormulaPreviewRequest(
        String expression,
        Map<String, Object> context
    ) {}

    public record FormulaValidateRequest(
        String expression
    ) {}

    public record ValidationResult(
        boolean valid,
        String error
    ) {}
}
