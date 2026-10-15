package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.meta.service.CommandService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Supplier;

/** Adapts the existing fixed tenant-wide publications to explicit machine data authority. */
@Service
@RequiredArgsConstructor
public class OpenApiLegacyAuthority {
    private final CommandService commands;
    private final ObjectMapper json;

    public <T> T read(OpenApiPublicationRegistry.ResourcePublication publication, Supplier<T> action) {
        if (!MachineDataAccessContext.isActive()) return action.get();
        return scoped(publication, Set.of("read"), Set.of(), List.of(), action);
    }

    public <T> T command(OpenApiPublicationRegistry.CommandPublication publication,
                         OpenApiPublicationRegistry.ResourcePublication resource, String pid, Supplier<T> action) {
        if (!MachineDataAccessContext.isActive()) return action.get();
        var definition = commands.findByCode(publication.commandCode());
        if (!resource.modelCode().equals(definition.getModelCode()) || !"published".equals(definition.getStatus())
                || !"state_transition".equals(definition.getType())) throw new AccessDeniedException("Published command source changed");
        Set<String> writable = new HashSet<>(publication.inputFields().values());
        try {
            var config = json.readTree(definition.getExecutionConfig());
            if (!config.path("permissions").isArray()
                    || java.util.stream.StreamSupport.stream(config.path("permissions").spliterator(), false)
                        .noneMatch(permission -> permission.isTextual() && publication.permission().equals(permission.asText()))) {
                throw new AccessDeniedException("Published command permission changed");
            }
            if (!config.path("stateField").isTextual()) throw new AccessDeniedException("Explicit command state field required");
            writable.add(config.path("stateField").asText());
            if (config.has("autoSetFields")) config.path("autoSetFields").fieldNames().forEachRemaining(writable::add);
        } catch (JsonProcessingException malformed) {
            throw new AccessDeniedException("Published command configuration is invalid", malformed);
        }
        return scoped(resource, Set.of("update"), writable,
                List.of(new MachineDataAccessContext.Constraint("pid", "pid", List.of(pid))), action);
    }

    private <T> T scoped(OpenApiPublicationRegistry.ResourcePublication publication, Set<String> actions,
                         Set<String> writable, List<MachineDataAccessContext.Constraint> constraints, Supplier<T> action) {
        var principal = MachineDataAccessContext.principal();
        Set<String> readable = new HashSet<>(publication.fields().values()); readable.addAll(writable);
        var decision = new MachineDataAccessContext.Decision(principal.tenantId(), principal.installationPid(),
                publication.modelCode(), actions, readable, writable, constraints);
        try (var scope = MachineDataAccessContext.authorize(Map.of(publication.modelCode(), decision))) { return action.get(); }
    }
}
