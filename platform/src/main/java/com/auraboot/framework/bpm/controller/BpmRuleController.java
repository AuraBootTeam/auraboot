package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.entity.BpmRule;
import com.auraboot.framework.bpm.rule.DroolsRuleService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bpm/rules")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.BPM_RULE_MANAGE)
public class BpmRuleController {

    private final DroolsRuleService ruleService;

    @PostMapping
    public ResponseEntity<BpmRule> create(@RequestBody BpmRule rule) {
        return ResponseEntity.ok(ruleService.createRule(rule));
    }

    @GetMapping
    public ResponseEntity<List<BpmRule>> list(@RequestParam(required = false) String type) {
        if (type != null) {
            return ResponseEntity.ok(ruleService.listRulesByType(type));
        }
        return ResponseEntity.ok(ruleService.listRules());
    }

    @GetMapping("/{pid}")
    public ResponseEntity<BpmRule> get(@PathVariable String pid) {
        return ResponseEntity.ok(ruleService.getRule(pid));
    }

    @PutMapping("/{pid}")
    public ResponseEntity<BpmRule> update(@PathVariable String pid, @RequestBody BpmRule rule) {
        return ResponseEntity.ok(ruleService.updateRule(pid, rule));
    }

    @DeleteMapping("/{pid}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String pid) {
        ruleService.deleteRule(pid);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, String> request) {
        String drlContent = request.get("drlContent");
        List<String> errors = ruleService.validateDrl(drlContent);
        return ResponseEntity.ok(Map.of("valid", errors.isEmpty(), "errors", errors));
    }

    @PostMapping("/{pid}/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(
            @PathVariable String pid,
            @RequestBody Map<String, Object> facts) {
        return ResponseEntity.ok(ruleService.evaluateRule(pid, facts));
    }
}
