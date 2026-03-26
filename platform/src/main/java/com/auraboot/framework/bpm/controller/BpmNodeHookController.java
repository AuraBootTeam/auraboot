package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.service.BpmNodeHookService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bpm/node-hooks")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.BPM_HOOK_MANAGE)
public class BpmNodeHookController {

    private final BpmNodeHookService hookService;

    @PostMapping
    public ResponseEntity<BpmNodeHook> create(@RequestBody BpmNodeHook hook) {
        return ResponseEntity.ok(hookService.createHook(hook));
    }

    @GetMapping
    public ResponseEntity<List<BpmNodeHook>> list(@RequestParam String processKey) {
        return ResponseEntity.ok(hookService.getHooksByProcessKey(processKey));
    }

    @PutMapping("/{pid}")
    public ResponseEntity<BpmNodeHook> update(@PathVariable String pid, @RequestBody BpmNodeHook hook) {
        return ResponseEntity.ok(hookService.updateHook(pid, hook));
    }

    @DeleteMapping("/{pid}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String pid) {
        hookService.deleteHook(pid);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
