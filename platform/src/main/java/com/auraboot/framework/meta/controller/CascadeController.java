package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.DictItemData;
import com.auraboot.framework.meta.service.DictCascadeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Cascade Select API Controller
 * Provides endpoints for loading cascading select options from hierarchical dictionary data
 *
 * @author AuraBoot Framework
 * @since 2.3.0
 */
@Tag(name = "级联数据", description = "级联选择数据加载")
@RestController
@RequestMapping("/api/meta/cascade")
@RequiredArgsConstructor
public class CascadeController {

    private final DictCascadeService dictCascadeService;

    @Operation(summary = "获取级联选项")
    @GetMapping("/options")
    public ApiResponse<List<CascadeOption>> getCascadeOptions(
            @Parameter(description = "字典编码") @RequestParam String dictCode,
            @Parameter(description = "父级值（为空则获取根节点）") @RequestParam(required = false) String parentValue,
            @Parameter(description = "层级（0表示根级）") @RequestParam(defaultValue = "0") int level) {

        List<DictItemData> items;
        if (parentValue == null || parentValue.isEmpty()) {
            // Get root items when parentValue is null or empty
            items = dictCascadeService.getCascadeRoots(dictCode);
        } else {
            // Get children items for the given parent
            items = dictCascadeService.getCascadeChildren(dictCode, parentValue);
        }

        // Convert to CascadeOption and determine if each item is a leaf
        List<CascadeOption> options = items.stream()
                .map(item -> {
                    // Check if this item has children
                    List<DictItemData> children = dictCascadeService.getCascadeChildren(dictCode, item.getValue());
                    boolean isLeaf = children == null || children.isEmpty();
                    return new CascadeOption(item.getValue(), item.getLabel(), isLeaf);
                })
                .collect(Collectors.toList());

        return ApiResponse.success(options);
    }

    @Operation(summary = "获取级联路径")
    @GetMapping("/path")
    public ApiResponse<List<CascadeOption>> getCascadePath(
            @Parameter(description = "字典编码") @RequestParam String dictCode,
            @Parameter(description = "目标值") @RequestParam String value) {

        // Get the full path from root to the target node
        List<DictItemData> pathItems = dictCascadeService.getNodePath(dictCode, value);

        // Convert to CascadeOption
        List<CascadeOption> path = pathItems.stream()
                .map(item -> {
                    // Check if this item has children to determine isLeaf
                    List<DictItemData> children = dictCascadeService.getCascadeChildren(dictCode, item.getValue());
                    boolean isLeaf = children == null || children.isEmpty();
                    return new CascadeOption(item.getValue(), item.getLabel(), isLeaf);
                })
                .collect(Collectors.toList());

        return ApiResponse.success(path);
    }

    /**
     * Cascade option response DTO
     */
    public record CascadeOption(
        String value,
        String label,
        Boolean isLeaf
    ) {}
}
