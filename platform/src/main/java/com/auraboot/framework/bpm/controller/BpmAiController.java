package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.service.BpmAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for BPM AI-assisted features.
 * Provides intelligent analysis based on historical process data.
 */
@RestController
@RequestMapping("/api/bpm/ai")
@RequiredArgsConstructor
public class BpmAiController {

    private final BpmAiService aiService;

    /**
     * Suggest assignees based on historical task completion data and workload.
     */
    @PostMapping("/suggest-assignee")
    public ResponseEntity<Map<String, Object>> suggestAssignee(@RequestBody Map<String, Object> context) {
        return ResponseEntity.ok(aiService.suggestAssignee(context));
    }

    /**
     * Analyze process bottlenecks using audit trail timing data.
     */
    @GetMapping("/bottleneck/{processKey}")
    public ResponseEntity<Map<String, Object>> analyzeBottleneck(@PathVariable String processKey) {
        return ResponseEntity.ok(aiService.analyzeBottleneck(processKey));
    }

    /**
     * Predict SLA risk for a running process instance.
     */
    @GetMapping("/sla-risk/{processInstanceId}")
    public ResponseEntity<Map<String, Object>> predictSlaRisk(@PathVariable String processInstanceId) {
        return ResponseEntity.ok(aiService.predictSlaRisk(processInstanceId));
    }

    /**
     * Generate a basic process definition from natural language description.
     */
    @PostMapping("/generate-process")
    public ResponseEntity<Map<String, Object>> generateProcess(@RequestBody Map<String, Object> request) {
        String description = (String) request.get("description");
        return ResponseEntity.ok(aiService.generateProcess(description));
    }
}
