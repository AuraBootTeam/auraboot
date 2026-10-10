package com.auraboot.framework.openplatform.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An explicit installation grant can narrow a publication, never expand it. */
public record OpenApiInstallationPolicy(Set<OpenApiResourceContract.Operation> operations,
                                        Set<String> readableFields, Set<String> writableFields,
                                        List<RowConstraint> rowConstraints) {
    public record RowConstraint(String field, List<String> values) {
        public RowConstraint {
            if (field == null || !field.matches("[A-Za-z][A-Za-z0-9]{0,99}")) {
                throw new IllegalArgumentException("Public constraint field required");
            }
            values = List.copyOf(values == null ? List.of() : values);
            if (values.isEmpty() || values.size() > 100 || values.stream().anyMatch(value -> value.length() > 1024)) {
                throw new IllegalArgumentException("Constraint requires between 1 and 100 bounded values");
            }
        }
    }

    public OpenApiInstallationPolicy {
        operations = Set.copyOf(operations == null ? Set.of() : operations);
        readableFields = Set.copyOf(readableFields == null ? Set.of() : readableFields);
        writableFields = Set.copyOf(writableFields == null ? Set.of() : writableFields);
        rowConstraints = List.copyOf(rowConstraints == null ? List.of() : rowConstraints);
        require(!operations.isEmpty() && readableFields.contains("pid"), "Operations and readable PID required");
        require(rowConstraints.size() <= 16, "Row constraint budget exceeded");
        require(rowConstraints.stream().map(RowConstraint::field).distinct().count() == rowConstraints.size(),
                "Duplicate policy constraint");
    }

    public void validate(OpenApiResourceContract contract) {
        require(contract.operations().containsAll(operations), "Grant exceeds published operations");
        readableFields.forEach(alias -> require(contract.fields().containsKey(alias)
                && contract.fields().get(alias).readable(), "Grant exceeds readable fields"));
        writableFields.forEach(alias -> require(contract.fields().containsKey(alias)
                && contract.fields().get(alias).writable(), "Grant exceeds writable fields"));
        require(contract.dataPolicy() != OpenApiResourceContract.DataPolicy.INSTALLATION_FILTER
                || !rowConstraints.isEmpty(), "Installation row policy is required");
        for (RowConstraint constraint : rowConstraints) {
            var field = contract.fields().get(constraint.field());
            require(field != null && field.readable() && !field.operators().isEmpty(), "Policy field is not filterable");
            require(Set.of(OpenApiResourceContract.Type.STRING, OpenApiResourceContract.Type.PID,
                    OpenApiResourceContract.Type.INTEGER, OpenApiResourceContract.Type.BOOLEAN,
                    OpenApiResourceContract.Type.DECIMAL, OpenApiResourceContract.Type.DATE,
                    OpenApiResourceContract.Type.DATETIME).contains(field.type()), "Scalar policy field required");
            constraint.values().forEach(value -> validateScalar(field.type(), value));
        }
        if (operations.contains(OpenApiResourceContract.Operation.CREATE)) {
            contract.fields().forEach((alias, field) -> require(!field.writable() || !field.required()
                    || writableFields.contains(alias), "Create grant hides a required input"));
        }
    }

    public Map<String, Object> project(OpenApiResourceContract contract, Map<String, Object> record) {
        Map<String, Object> result = new LinkedHashMap<>(contract.project(record));
        result.keySet().retainAll(readableFields);
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> mapWrite(OpenApiResourceContract contract, Map<String, Object> input, boolean create) {
        require(input != null && writableFields.containsAll(input.keySet()), "Input exceeds installation field policy");
        return contract.mapWrite(input, create);
    }

    public boolean permitsRow(OpenApiResourceContract contract, Map<String, Object> row) {
        return rowConstraints.stream().allMatch(constraint -> {
            Object value = row.get(contract.fields().get(constraint.field()).source());
            return value != null && constraint.values().stream().anyMatch(expected ->
                    scalarEquals(contract.fields().get(constraint.field()).type(), value, expected));
        });
    }

    private static boolean scalarEquals(OpenApiResourceContract.Type type, Object actual, String expected) {
        return switch (type) {
            case INTEGER, DECIMAL -> new java.math.BigDecimal(actual.toString()).compareTo(new java.math.BigDecimal(expected)) == 0;
            default -> actual.toString().equals(expected);
        };
    }

    private static void validateScalar(OpenApiResourceContract.Type type, String value) {
        switch (type) {
            case INTEGER -> new java.math.BigDecimal(value).toBigIntegerExact();
            case DECIMAL -> new java.math.BigDecimal(value);
            case BOOLEAN -> require(Set.of("true", "false").contains(value), "Boolean policy value required");
            case DATE -> java.time.LocalDate.parse(value);
            case DATETIME -> java.time.Instant.parse(value);
            default -> { }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
