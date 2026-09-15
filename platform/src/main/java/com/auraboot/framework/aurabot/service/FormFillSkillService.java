package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.FormFillRequest;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.service.AgentSkillService;
import com.auraboot.framework.agent.util.JsonbColumns;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Loads a governed playbook without opening a second agent execution path. */
@Service
@RequiredArgsConstructor
public class FormFillSkillService {
    public static final String SKILL_CODE = "form_draft_fill";
    private final AgentSkillService skills;
    private final ObjectMapper mapper;

    public ResolvedSkill resolve(Long tenantId, LlmProvider provider, String model) {
        if (provider == null || !provider.modelCapabilities(model).toolCalling()) {
            throw new IllegalStateException("Form fill requires a model with tool calling support");
        }
        Map<String, Object> skill = skills.loadSkill(tenantId, SKILL_CODE);
        if (skill == null) {
            throw new IllegalStateException("Form fill skill is not installed or active: " + SKILL_CODE);
        }
        String version = requiredText(skill, "skill_version");
        String prompt = requiredText(skill, "prompt_template");
        if (!"read_only".equals(skill.get("actionability"))) {
            throw new IllegalStateException("Form fill skill must be read_only");
        }
        try {
            String json = JsonbColumns.toJsonText(skill.get("skill_tools"), mapper);
            if (json == null || !mapper.readValue(json, List.class).equals(List.of("platform.fill_form"))) {
                throw new IllegalStateException("Form fill skill may only declare platform.fill_form");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Invalid form fill skill tool declaration", e);
        }
        return new ResolvedSkill(version, prompt, sha256(prompt));
    }

    private static String requiredText(Map<String, Object> skill, String key) {
        if (!(skill.get(key) instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("Missing form fill skill " + key);
        }
        return value;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record ResolvedSkill(String version, String prompt, String promptHash) {
        public String instructions(FormFillRequest request) {
            return "\nRuntime skill: " + SKILL_CODE + "@" + version + "\n" + prompt
                    + "\nForm context: currentDate=" + request.currentDate()
                    + "; timeZone=" + request.timeZone();
        }

        public Map<String, Object> traceMetadata() {
            return Map.of("code", SKILL_CODE, "version", version, "prompt_sha256", promptHash);
        }
    }
}
