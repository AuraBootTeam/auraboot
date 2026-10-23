package com.auraboot.framework.scheduler.handler;

import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.DynamicDataQueryScope;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonParser;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** A fixed deployment delegation through the regular command pipeline, never a handler bypass. */
@Component("scheduledTaskCommandHandler")
@RequiredArgsConstructor
public class ScheduledTaskCommandHandler {
    private final ScheduledCommandProperties properties;
    private final ObjectMapper objectMapper;
    private final UserService users;
    private final TenantMemberService members;
    private final UserRoleService roles;
    private final CommandService commands;
    private final DynamicDataService data;
    private final CommandExecutor executor;

    public void execute(ScheduledTask task) {
        var binding = binding(task);
        if (DynamicDataQueryScope.isActive()) {
            throw denied();
        }
        var previous = MetaContext.snapshot();
        MetaContext.clear();
        MetaContext.setContext(binding.getTenantId(), null, null, "scheduled-command");
        try (var queries = DynamicDataQueryScope.open()) {
            var actor = users.findByPid(binding.getActorUserPid());
            if (actor == null || actor.getId() == null || actor.getId() <= 0
                    || !binding.getActorUserPid().equals(actor.getPid()) || Boolean.TRUE.equals(actor.getDeletedFlag())
                    || !actor.isEnabled() || !actor.isAccountNonExpired() || !actor.isAccountNonLocked()
                    || !actor.isCredentialsNonExpired()) {
                throw denied();
            }
            var member = members.findByTenantIdAndUserId(binding.getTenantId(), actor.getId());
            if (member == null || member.getId() == null || member.getId() <= 0
                    || !binding.getTenantId().equals(member.getTenantId()) || !actor.getId().equals(member.getUserId())
                    || Boolean.TRUE.equals(member.getDeletedFlag()) || !"active".equalsIgnoreCase(member.getStatus())) {
                throw denied();
            }
            var currentRoles = roles.getRoleIdsByMemberIdAndTenantId(member.getId(), binding.getTenantId());
            MetaContext.setContext(binding.getTenantId(), actor.getId(), actor.getPid(), actor.getPid(),
                    currentRoles == null ? Set.of() : new HashSet<>(currentRoles));
            MetaContext.setMemberId(member.getId());
            var definition = commands.findByCode(binding.getCommandCode());
            if (definition == null || !binding.getCommandCode().equals(definition.getCode())
                    || !"published".equals(definition.getStatus())
                    || !binding.getModelCode().equals(definition.getModelCode())) {
                throw denied();
            }
            var row = data.getById(binding.getModelCode(), binding.getTargetRecordPid());
            if (row == null || !binding.getTargetRecordPid().equals(row.get("pid"))
                    || !binding.getTenantId().toString().equals(String.valueOf(row.get("tenant_id")))) {
                throw denied();
            }
            int version;
            try {
                version = new BigDecimal(String.valueOf(row.get("row_version"))).intValueExact();
            } catch (IllegalArgumentException | ArithmeticException failure) {
                throw denied();
            }
            if (version < 0) {
                throw denied();
            }
            var request = new CommandExecuteRequest();
            request.setTargetRecordPid(binding.getTargetRecordPid());
            request.setExpectedVersion(version);
            request.setOperationType("update");
            request.setPayload(Map.of());
            request.setClientRequestId(UUID.randomUUID().toString());
            request.setAuditContext(Map.of("source", "scheduled-task", "taskPid", task.getPid(),
                    "bindingCode", binding.getCode()));
            var result = executor.execute(binding.getCommandCode(), request);
            if (result == null || !"completed".equals(result.getPhaseReached())) {
                throw new IllegalStateException("SCHEDULED_COMMAND_NOT_COMPLETED");
            }
        } finally {
            MetaContext.restore(previous);
        }
    }

    private ScheduledCommandProperties.Binding binding(ScheduledTask task) {
        if (!properties.isEnabled() || task == null || !"scheduledTaskCommandHandler".equals(task.getHandlerBean())
                || !"execute".equals(task.getHandlerMethod())
                || (task.getMaxRetries() != null && task.getMaxRetries() != 0)
                || task.getParams() == null || task.getParams().length() > 512) {
            throw denied();
        }
        JsonNode params;
        try {
            params = objectMapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(task.getParams());
        } catch (java.io.IOException failure) {
            throw denied();
        }
        if (params == null || !params.isObject() || params.size() != 1 || !params.path("bindingCode").isTextual()) {
            throw denied();
        }
        String selected = params.path("bindingCode").asText();
        List<ScheduledCommandProperties.Binding> bindings = properties.getBindings();
        if (bindings == null || bindings.isEmpty() || bindings.size() > 128) {
            throw denied();
        }
        Set<String> codes = new HashSet<>(), tasks = new HashSet<>();
        ScheduledCommandProperties.Binding match = null;
        for (var value : bindings) {
            if (value == null || !identifier(value.getCode(), "[a-z][a-z0-9-]{0,63}")
                    || !identifier(value.getTaskPid(), "[A-Za-z0-9_-]{1,100}")
                    || value.getTenantId() == null || value.getTenantId() <= 0
                    || !identifier(value.getActorUserPid(), "[A-Za-z0-9_-]{1,100}")
                    || !identifier(value.getCommandCode(), "[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+")
                    || value.getCommandCode().length() > 200
                    || !identifier(value.getModelCode(), "[a-z][a-z0-9_]{0,127}")
                    || !identifier(value.getTargetRecordPid(), "[A-Za-z0-9_-]{1,100}")
                    || !codes.add(value.getCode()) || !tasks.add(value.getTaskPid())) {
                throw denied();
            }
            if (value.getCode().equals(selected)) {
                match = value;
            }
        }
        if (match == null || !match.getTaskPid().equals(task.getPid())
                || !Objects.equals(match.getTenantId(), task.getTenantId())) {
            throw denied();
        }
        return match;
    }

    private static boolean identifier(String value, String pattern) {
        return value != null && value.matches(pattern);
    }

    private static SecurityException denied() {
        return new SecurityException("SCHEDULED_COMMAND_BINDING_DENIED");
    }
}
