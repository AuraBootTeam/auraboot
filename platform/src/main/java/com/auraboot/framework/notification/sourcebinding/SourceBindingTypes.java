package com.auraboot.framework.notification.sourcebinding;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Host deployment descriptors. No imported package bytes or caller approval flags are accepted. */
public final class SourceBindingTypes {
    private SourceBindingTypes() {
    }
    static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("NOTIFICATION_SOURCE_BINDING_INVALID");
    }
    static String code(String value) {
        require(value != null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"));
        return value;
    }
    static String pid(String value) {
        require(value != null && value.matches("[A-Za-z0-9_-]{1,64}"));
        return value;
    }
    static String sha(String value) {
        require(value != null && value.matches("[a-f0-9]{64}"));
        return value;
    }
    public record Owner(String pid, String pluginId, String namespace, String version) {
        public Owner {
            SourceBindingTypes.pid(pid);
            require(pluginId != null && pluginId.matches("[A-Za-z0-9_.-]{1,128}"));
            require(namespace != null && namespace.matches("[A-Za-z0-9_.-]{1,128}"));
            require(version != null && !version.isBlank() && version.length() <= 64);
        }
    }
    public record Approval(String mode, String modelCode, String shapeSha256, List<Owner> owners, Set<String> nativeFieldPids) {
        public Approval {
            require("METADATA_SHAPE_V1".equals(mode));
            code(modelCode);
            sha(shapeSha256);
            owners = List.copyOf(owners);
            nativeFieldPids = java.util.Collections.unmodifiableSortedSet(
                    new java.util.TreeSet<>(nativeFieldPids == null ? Set.of() : nativeFieldPids));
            require(!owners.isEmpty() && owners.size() <= 16);
            require(owners.stream().map(Owner::pid).distinct().count() == owners.size());
            nativeFieldPids.forEach(SourceBindingTypes::pid);
        }
    }
    public enum ScalarType {
        STRING, BOOLEAN
    }
    public record Scalar(ScalarType type, String value) {
        public Scalar {
            require(type != null && value != null && value.length() <= 256);
            require(type == ScalarType.STRING || "true".equals(value) || "false".equals(value));
        }
        Object raw() {
            return type == ScalarType.BOOLEAN ? Boolean.valueOf(value) : value;
        }
    }
    public record Exact(String fieldCode, Scalar value) {
        @ConstructorBinding
        public Exact {
            code(fieldCode);
            Objects.requireNonNull(value);
        }
        public Exact(String fieldCode, Object value) {
            this(fieldCode, scalar(value));
        }
        private static Scalar scalar(Object v) {
            require(v instanceof String || v instanceof Boolean);
            return new Scalar(v instanceof Boolean ? ScalarType.BOOLEAN : ScalarType.STRING, v.toString());
        }
    }
    public record Entity(String modelCode, List<Exact> equals) {
        public Entity {
            code(modelCode);
            equals = exact(equals);
        }
    }
    /** Fixed host-clock relation; enabled only by an explicitly approved target descriptor. */
    public enum DeadlineRelation {
        AFTER_HOST_NOW
    }
    public record Deadline(String fieldCode, DeadlineRelation relation) {
        public Deadline {
            code(fieldCode);
            require(relation == DeadlineRelation.AFTER_HOST_NOW);
        }
    }
    /** Finite child eligibility only; counter values are never message variables or configuration versions. */
    public record RemainingChild(Entity entity, String parentField, String orderedField,
                                 String submittedField, int maxRows) {
        public RemainingChild {
            Objects.requireNonNull(entity);
            code(parentField);
            code(orderedField);
            code(submittedField);
            require(!orderedField.equals(submittedField) && !parentField.equals(orderedField)
                    && !parentField.equals(submittedField) && maxRows > 0 && maxRows <= 1000);
            require(entity.equals().stream().noneMatch(value -> value.fieldCode().equals(parentField)));
        }
    }
    public record Target(String listField, Entity entity, String ownerField,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Deadline deadline,
                         @JsonInclude(JsonInclude.Include.NON_NULL) RemainingChild remainingChild) {
        public Target(String listField, Entity entity, String ownerField, Deadline deadline) {
            this(listField, entity, ownerField, deadline, null);
        }
        public Target(String listField, Entity entity, String ownerField) {
            this(listField, entity, ownerField, null, null);
        }
        @ConstructorBinding
        public Target {
            code(listField);
            Objects.requireNonNull(entity);
            code(ownerField);
        }
    }
    public record Source(Entity entity, String identityField, String ownerField, String occurredAtField, Target targets) {
        public Source {
            Objects.requireNonNull(entity);
            code(identityField);
            code(ownerField);
            code(occurredAtField);
            require("pid".equals(identityField));
            Objects.requireNonNull(targets);
        }
    }
    public record Principal(Entity entity, String principalField, String ownerField, String verificationField) {
        public Principal {
            Objects.requireNonNull(entity);
            code(principalField);
            code(ownerField);
            code(verificationField);
        }
    }
    public record Verification(Entity entity, String ownerField) {
        public Verification {
            Objects.requireNonNull(entity);
            code(ownerField);
        }
    }
    public record Binding(int schemaVersion, String code, long tenantId, List<Approval> approvals, Source source, Entity owner, Principal principal, Verification verification, long ruleId, String templateCode, Map<String, String> variableToSourceField) {
        public Binding {
            require(schemaVersion == 1 && tenantId> 0 && ruleId> 0);
            SourceBindingTypes.code(code);
            approvals = List.copyOf(approvals);
            require(approvals.size() <= (source != null && source.targets().remainingChild() != null ? 6 : 5));
            Objects.requireNonNull(source);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(principal);
            Objects.requireNonNull(verification);
            SourceBindingTypes.code(templateCode);
            variableToSourceField = variableToSourceField == null ? Map.of() : Map.copyOf(variableToSourceField);
            require(variableToSourceField.size() <= 16);
            variableToSourceField.forEach((k, v) -> {
                SourceBindingTypes.code(k);
                SourceBindingTypes.code(v);
            });
            Set<String> models = new HashSet<>(List.of(source.entity.modelCode, source.targets.entity.modelCode,
                    owner.modelCode, principal.entity.modelCode, verification.entity.modelCode));
            if (source.targets.remainingChild != null) {
                require(!models.contains(source.targets.remainingChild.entity.modelCode));
                models.add(source.targets.remainingChild.entity.modelCode);
            }
            require(approvals.stream().map(Approval::modelCode).distinct().count() == approvals.size());
            require(models.equals(new HashSet<>(approvals.stream().map(Approval::modelCode).toList())));
        }
    }
    static List<Exact> exact(List<Exact> values) {
        var result = List.copyOf(values);
        require(!result.isEmpty() && result.size() <= 32 && result.stream().map(Exact::fieldCode).distinct().count() == result.size());
        return result;
    }
}
