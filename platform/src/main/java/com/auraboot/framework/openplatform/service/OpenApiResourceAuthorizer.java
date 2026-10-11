package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.meta.dto.ModelDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves current publication + installation grants before every call, including replay. */
@Service
@RequiredArgsConstructor
public class OpenApiResourceAuthorizer {
    private final OpenApiPublicationService publications;
    private final OpenApiPublicationValidator validator;

    public record Authorization(OpenApiResourceContract contract, OpenApiInstallationPolicy policy,
                                MachineDataAccessContext.Decision decision, Long installationId,
                                Map<String, MachineDataAccessContext.Decision> decisions,
                                Map<String, Object> sourceAuthorities, OpenApiResourceSourceBinding sourceBinding) {
        public MachineDataAccessContext.Scope openScope() {
            return MachineDataAccessContext.authorize(decisions);
        }
    }

    public Authorization authorize(MachinePrincipal principal, String code, OpenApiResourceContract.Operation operation) {
        if (principal == null || principal.tenantId() == null || principal.installationPid() == null) {
            throw new AccessDeniedException("Installed machine principal required");
        }
        var authority = publications.currentAuthority(principal, code, operation).orElseThrow(() -> {
            if (publications.active(principal.tenantId(), code).isEmpty()) {
                return new ResponseStatusException(HttpStatus.NOT_FOUND, "open_api_resource_not_published");
            }
            return new AccessDeniedException("Current token, scope and resource installation grant required");
        });
        var contract = authority.contract();
        if (!contract.operations().contains(operation)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "open_api_operation_not_published");
        if (!principal.scopes().contains(contract.scopes().get(operation))) throw new AccessDeniedException("Published operation scope required");
        ModelDefinition model = validator.resolveModel(contract);
        var sourceBinding = publications.requireSourceBinding(principal.tenantId(), contract, model);
        try {
            validator.validate(contract, model);
        } catch (IllegalArgumentException unavailable) {
            // CATCH: published virtual source/grant withdrawal is an authorization denial, not caller input.
            if (!"namedQuery".equals(model.getSourceType())) throw unavailable;
            throw new AccessDeniedException("Published virtual resource source is unavailable", unavailable);
        }
        var policy = authority.policy();
        policy.validate(contract);
        if (!policy.operations().contains(operation)) throw new AccessDeniedException("Installation operation grant required");
        List<MachineDataAccessContext.Constraint> constraints = policy.rowConstraints().stream().map(constraint -> {
            var field = contract.fields().get(constraint.field());
            String column = model.getFields().stream().filter(source -> source.getCode().equals(field.source()))
                    .map(source -> source.getColumnName() == null || source.getColumnName().isBlank() ? source.getCode() : source.getColumnName())
                    .findFirst().orElseThrow(() -> new IllegalStateException("Published policy column unavailable"));
            return new MachineDataAccessContext.Constraint(field.source(), column,
                    constraint.values().stream().map(value -> scalar(field.type(), value)).toList());
        }).toList();
        Set<String> readable = policy.readableFields().stream().map(alias -> contract.fields().get(alias).source()).collect(Collectors.toSet());
        Set<String> writable = policy.writableFields().stream().map(alias -> contract.fields().get(alias).source()).collect(Collectors.toSet());
        String action = switch (operation) {
            case LIST, GET -> "read";
            case CREATE -> "create";
            case UPDATE -> "update";
            case DELETE -> "delete";
        };
        var decision = new MachineDataAccessContext.Decision(principal.tenantId(), principal.installationPid(),
                contract.modelCode(), Set.of(action), readable, writable, constraints);
        Map<String, MachineDataAccessContext.Decision> decisions = new java.util.LinkedHashMap<>();
        Map<String, Object> sourceAuthorities = new java.util.LinkedHashMap<>();
        decisions.put(contract.modelCode(), decision);
        for (var entry : contract.sourceResources().entrySet()) {
            var source = authorize(principal, entry.getValue(), OpenApiResourceContract.Operation.LIST);
            if (!entry.getKey().equals(source.contract().modelCode()) || !"physical".equals(source.sourceBinding().sourceType())) {
                throw new AccessDeniedException("Virtual query requires its declared physical source grant");
            }
            decisions.put(entry.getKey(), source.decision());
            sourceAuthorities.put(entry.getKey(), Map.of("contract", source.contract(), "policy", source.policy(),
                    "installation", source.installationId()));
        }
        return new Authorization(contract, policy, decision, authority.installationId(), Map.copyOf(decisions),
                Map.copyOf(sourceAuthorities), sourceBinding);
    }

    private Object scalar(OpenApiResourceContract.Type type, String value) {
        return switch (type) {
            case INTEGER -> new BigDecimal(value).toBigIntegerExact();
            case DECIMAL -> new BigDecimal(value);
            case BOOLEAN -> Boolean.valueOf(value);
            case DATE -> LocalDate.parse(value);
            case DATETIME -> Instant.parse(value);
            case STRING, PID -> value;
            default -> throw new IllegalArgumentException("Scalar row policy required");
        };
    }

    public Authorization authorizeReference(MachinePrincipal principal, OpenApiResourceContract owner,
                                            OpenApiResourceContract.Field field) {
        var target = authorize(principal, field.referenceResource(), OpenApiResourceContract.Operation.GET);
        if (!target.contract().modelCode().equals(validator.referenceTargetModel(owner, field))) {
            throw new AccessDeniedException("Published reference target model mismatch");
        }
        return target;
    }
}
