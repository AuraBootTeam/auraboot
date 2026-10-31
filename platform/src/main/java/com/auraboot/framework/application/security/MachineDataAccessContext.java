package com.auraboot.framework.application.security;

import org.springframework.security.access.AccessDeniedException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request-bound machine authority, independent of human membership and command permits. */
public final class MachineDataAccessContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private MachineDataAccessContext() { }

    public record Constraint(String field, String column, List<Object> values) {
        public Constraint {
            identifier(field);
            identifier(column);
            values = List.copyOf(values);
            if (values.isEmpty() || values.size() > 100) throw new IllegalArgumentException("Bounded machine constraint required");
            for (Object value : values) {
                if (!(value instanceof String || value instanceof Number || value instanceof Boolean
                        || value instanceof java.time.LocalDate || value instanceof java.time.Instant)) {
                    throw new IllegalArgumentException("Scalar machine constraint required");
                }
            }
        }
    }

    public record Decision(Long tenantId, String installationPid, String modelCode, Set<String> actions,
                           Set<String> readableFields, Set<String> writableFields, List<Constraint> constraints) {
        public Decision {
            identifier(modelCode);
            if (tenantId == null || installationPid == null || installationPid.isBlank()) {
                throw new IllegalArgumentException("Machine decision identity required");
            }
            actions = Set.copyOf(actions);
            readableFields = Set.copyOf(readableFields);
            writableFields = Set.copyOf(writableFields);
            constraints = List.copyOf(constraints);
            if (actions.isEmpty() || !Set.of("read", "create", "update", "delete").containsAll(actions)
                    || !readableFields.contains("pid")) throw new IllegalArgumentException("Invalid machine action/field authority");
            readableFields.forEach(MachineDataAccessContext::identifier);
            writableFields.forEach(MachineDataAccessContext::identifier);
        }

        public void requireAction(String action) {
            // CRUD implementations must read their own target/result inside the same authorization.
            if (!"read".equals(action) && !actions.contains(action)) {
                throw new AccessDeniedException("Machine operation is not authorized");
            }
        }

        public boolean permits(Map<String, Object> row) {
            if (row == null) return false;
            Object rowTenant = row.get("tenant_id");
            if (rowTenant != null && !tenantId.toString().equals(rowTenant.toString())) return false;
            return constraints.stream().allMatch(constraint -> {
                Object actual = row.containsKey(constraint.field()) ? row.get(constraint.field()) : row.get(constraint.column());
                return actual != null && constraint.values().stream().anyMatch(value -> scalarEquals(actual, value));
            });
        }
    }

    private record ExpectedVersion(String modelCode, String recordPid, long version) { }
    private record State(ExternalMachineAuthenticator.MachinePrincipal principal, Map<String, Decision> decisions,
                         ExpectedVersion expectedVersion, boolean requiredReferenceReads,Object authorityIdentity) {
        State(ExternalMachineAuthenticator.MachinePrincipal principal,Map<String,Decision> decisions,ExpectedVersion expectedVersion,boolean requiredReferenceReads) {
            this(principal,decisions,expectedVersion,requiredReferenceReads,new Object());
        }
    }

    public static Scope begin(ExternalMachineAuthenticator.MachinePrincipal principal) {
        if (principal == null || principal.tenantId() == null || principal.subjectPid() == null) {
            throw new AccessDeniedException("Machine principal required");
        }
        return enter(new State(principal, Map.of(), null, false));
    }

    public static Scope authorize(Map<String, Decision> decisions) {
        State state=CURRENT.get();
        return authorize(decisions,state!=null && state.requiredReferenceReads());
    }

    /** Host-only opt-in: every required record still needs its own explicit model/field/row decision. */
    public static Scope authorizeRequiredReferenceReads(Map<String, Decision> decisions) {
        return authorize(decisions,true);
    }
    /** Legacy authority never inherits another invocation's optional reference-read mode. */
    public static Scope authorizeWithoutRequiredReferenceReads(Map<String, Decision> decisions) {
        return authorize(decisions,false);
    }

    private static Scope authorize(Map<String, Decision> decisions,boolean requiredReferenceReads) {
        State state = CURRENT.get();
        if (state == null) throw new AccessDeniedException("Machine request context required");
        decisions.forEach((model, decision) -> {
            if (!model.equals(decision.modelCode()) || !state.principal().tenantId().equals(decision.tenantId())
                    || !java.util.Objects.equals(state.principal().installationPid(), decision.installationPid())) {
                throw new AccessDeniedException("Machine authority identity mismatch");
            }
        });
        return enter(new State(state.principal(), Map.copyOf(decisions), null, requiredReferenceReads));
    }

    /** Opaque, invocation-local authority identity; never persisted or obtained from request data. */
    public static Object requiredReferenceReadScope() {
        State state=CURRENT.get();return state!=null && state.requiredReferenceReads()?state.authorityIdentity():null;
    }

    public static boolean isActive() { return CURRENT.get() != null; }

    public static Decision requireDecision(String modelCode) {
        State state = CURRENT.get();
        Decision decision = state == null ? null : state.decisions().get(modelCode);
        if (decision == null) throw new AccessDeniedException("Explicit machine model authority required");
        return decision;
    }

    public static ExternalMachineAuthenticator.MachinePrincipal principal() {
        return CURRENT.get() == null ? null : CURRENT.get().principal();
    }

    public static Scope withExpectedVersion(String modelCode, String recordPid, long version) {
        requireDecision(modelCode);
        if (recordPid == null || recordPid.isBlank() || version < 1) throw new IllegalArgumentException("Public CAS target/version required");
        State state = CURRENT.get();
        return enter(new State(state.principal(), state.decisions(), new ExpectedVersion(modelCode, recordPid, version),state.requiredReferenceReads()));
    }

    public static Long expectedVersion(String modelCode, String recordPid) {
        State state = CURRENT.get();
        var expected = state == null ? null : state.expectedVersion();
        return expected != null && expected.modelCode().equals(modelCode) && expected.recordPid().equals(recordPid)
                ? expected.version() : null;
    }

    private static Scope enter(State state) {
        State previous = CURRENT.get();
        CURRENT.set(state);
        return new Scope(state, previous);
    }

    public static final class Scope implements AutoCloseable {
        private final State entered;
        private final State previous;
        private boolean closed;
        private Scope(State entered, State previous) { this.entered = entered; this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            if (CURRENT.get() != entered) throw new IllegalStateException("Machine scopes must close in order");
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            throw new IllegalArgumentException("Trusted machine model/field identifier required");
        }
    }

    private static boolean scalarEquals(Object actual, Object expected) {
        if (actual instanceof Number && expected instanceof Number) {
            return new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected.toString())) == 0;
        }
        if (actual instanceof java.sql.Timestamp timestamp && expected instanceof java.time.Instant instant) {
            return timestamp.toInstant().equals(instant);
        }
        return actual.toString().equals(expected.toString());
    }
}
