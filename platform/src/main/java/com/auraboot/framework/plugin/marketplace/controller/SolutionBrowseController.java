package com.auraboot.framework.plugin.marketplace.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.plugin.marketplace.dto.SolutionDTO;
import com.auraboot.framework.plugin.marketplace.dto.SolutionDetailDTO;
import com.auraboot.framework.plugin.marketplace.service.SolutionBrowseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/marketplace/solutions")
@RequiredArgsConstructor
@Tag(name = "Solution Marketplace", description = "Industry solution browsing APIs")
public class SolutionBrowseController {

    private final SolutionBrowseService browseService;

    // lgtm[java/spring-disabled-csrf-protection] Read-only JWT API; CSRF is disabled centrally for stateless bearer-token authentication.
    @GetMapping
    @Operation(summary = "Search solutions")
    public ApiResponse<List<SolutionDTO>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String industry,
            @RequestParam(defaultValue = "popular") String sort) {
        return ApiResponse.ok(browseService.search(keyword, industry, sort));
    }

    // lgtm[java/spring-disabled-csrf-protection] Read-only JWT API; CSRF is disabled centrally for stateless bearer-token authentication.
    @GetMapping("/{code}")
    @Operation(summary = "Get solution detail")
    public ApiResponse<SolutionDetailDTO> getDetail(@PathVariable String code) {
        return ApiResponse.ok(browseService.getDetail(code));
    }

    // lgtm[java/spring-disabled-csrf-protection] Read-only JWT API; CSRF is disabled centrally for stateless bearer-token authentication.
    @GetMapping("/featured")
    @Operation(summary = "Get featured solutions")
    public ApiResponse<List<SolutionDTO>> getFeatured() {
        return ApiResponse.ok(browseService.getFeatured());
    }

    // lgtm[java/spring-disabled-csrf-protection] Read-only JWT API; CSRF is disabled centrally for stateless bearer-token authentication.
    @GetMapping("/industries")
    @Operation(summary = "Get available industries")
    public ApiResponse<List<String>> getIndustries() {
        return ApiResponse.ok(browseService.getIndustries());
    }

    // lgtm[java/spring-disabled-csrf-protection] Read-only JWT API; CSRF is disabled centrally for stateless bearer-token authentication.
    @GetMapping("/installed")
    @Operation(summary = "Get installed solutions for current tenant")
    public ApiResponse<List<SolutionDTO>> getInstalled() {
        return ApiResponse.ok(browseService.getInstalled());
    }
}
