package com.auraboot.framework.intent.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.intent.dto.*;
import com.auraboot.framework.intent.service.IntentAnalyzerService;
import com.auraboot.framework.intent.service.PluginDeployService;
import com.auraboot.framework.intent.service.PluginGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for Intent-Driven Development (GAP-106).
 *
 * Provides endpoints to analyze requirement documents, generate plugin
 * configurations, and deploy them to the platform.
 */
@RestController
@RequestMapping("/api/agent/intent")
public class IntentController {

    private static final Logger log = LoggerFactory.getLogger(IntentController.class);

    private final IntentAnalyzerService analyzerService;
    private final PluginGeneratorService generatorService;
    private final PluginDeployService deployService;

    public IntentController(IntentAnalyzerService analyzerService,
                            PluginGeneratorService generatorService,
                            PluginDeployService deployService) {
        this.analyzerService = analyzerService;
        this.generatorService = generatorService;
        this.deployService = deployService;
    }

    /**
     * Analyze a requirement document and extract entities, relationships,
     * state machines, and business rules.
     */
    @PostMapping("/analyze")
    public ApiResponse<IntentAnalysisResult> analyze(@RequestBody IntentAnalysisRequest request) {
        log.info("Intent analysis requested, format={}, contentLength={}",
                request.getFormat(), request.getContent() != null ? request.getContent().length() : 0);

        IntentAnalysisResult result = analyzerService.analyze(request.getContent(), request.getFormat());
        return ApiResponse.success(result);
    }

    /**
     * Generate a complete plugin configuration from an analysis result.
     */
    @PostMapping("/generate")
    public ApiResponse<PluginGenerateResult> generate(@RequestBody PluginGenerateRequest request) {
        log.info("Plugin generation requested, pluginCode={}", request.getPluginCode());

        PluginGenerateResult result = generatorService.generate(
                request.getAnalysis(),
                request.getPluginCode(),
                request.getPluginName()
        );
        return ApiResponse.success(result);
    }

    /**
     * Deploy a generated plugin configuration to the platform.
     */
    @PostMapping("/deploy")
    public ApiResponse<PluginDeployResult> deploy(@RequestBody PluginDeployRequest request) {
        log.info("Plugin deployment requested, pluginCode={}", request.getPluginCode());

        PluginDeployResult result = deployService.deploy(
                request.getPluginCode(),
                request.getPluginName(),
                request.getConfigs()
        );
        return ApiResponse.success(result);
    }
}
