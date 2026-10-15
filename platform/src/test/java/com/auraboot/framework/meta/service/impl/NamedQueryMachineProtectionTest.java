package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NamedQueryMachineProtectionTest {
    private final NamedQuerySourceModels sources = mock(NamedQuerySourceModels.class);
    private final MetaModelService models = mock(MetaModelService.class);
    private final NamedQueryFieldProtection protection = new NamedQueryFieldProtection(mock(DataPermissionEngine.class),
            mock(FieldMaskService.class), models, sources, mock(DataDomainService.class), mock(PermissionEvaluator.class));
    private final MachinePrincipal principal = new MachinePrincipal(7L, "token", "app", Set.of(), "app", "install", "staging", "token");
    private final MachineDataAccessContext.Decision decision = new MachineDataAccessContext.Decision(7L, "install", "orders",
            Set.of("read"), Set.of("pid", "name"), Set.of(),
            List.of(new MachineDataAccessContext.Constraint("zone", "zone", List.of("x' OR TRUE --"))));

    @AfterEach void cleanup() { MetaContext.clear(); }

    private NamedQuery query(String sql, List<NamedQueryField> fields) {
        MetaContext.setContext(7L, null, "token", "machine");
        NamedQuery query = new NamedQuery();
        query.setFromSql(sql);
        when(sources.resolvePlan(7L, sql, fields)).thenReturn(new NamedQuerySourceModels.Sources(Map.of("\"public\".\"orders\"", "orders"), Map.of()));
        when(models.getColumnName(eq("orders"), anyString())).thenAnswer(call -> call.getArgument(1));
        return query;
    }

    private NamedQueryField field() {
        NamedQueryField field = new NamedQueryField();
        field.setFieldCode("name"); field.setColumnExpr("name");
        return field;
    }

    @Test void rowPolicyIsBoundAndAppliedAtPhysicalSource() {
        var fields = List.of(field()); var query = query("orders", fields);
        try (var request = MachineDataAccessContext.begin(principal);
             var permit = MachineDataAccessContext.authorize(Map.of("orders", decision))) {
            var plan = protection.prepare(query, fields, "list");
            assertThat(plan.sourceScopes().get("\"public\".\"orders\"")).contains("tenant_id = #{params.machine_nq_0_tenant}")
                    .contains("zone IN (#{params.machine_nq_0_0_0})").doesNotContain("OR TRUE");
            assertThat(plan.parameters()).containsEntry("machine_nq_0_tenant", 7L).containsEntry("machine_nq_0_0_0", "x' OR TRUE --");
            assertThat(protection.rewrite(plan, "SELECT orders.name FROM orders")).contains("machine_nq_0_tenant");
        }
    }

    @Test void hiddenPredicateAndGroupColumnsCannotInferProtectedValues() {
        var fields = List.of(field());
        for (String sql : List.of("SELECT orders.name FROM orders WHERE orders.secret = #{params.value}",
                "SELECT orders.name FROM orders GROUP BY orders.name, orders.secret")) {
            var query = query(sql, fields);
            try (var request = MachineDataAccessContext.begin(principal);
                 var permit = MachineDataAccessContext.authorize(Map.of("orders", decision))) {
                assertThatThrownBy(() -> protection.prepare(query, fields, "list"))
                        .isInstanceOf(AccessDeniedException.class).hasMessageContaining("non-public");
            }
        }
    }

    @Test void ungrantedJoinedModelFailsBeforeExecution() {
        var fields = List.of(field()); var query = query("orders JOIN customers ON orders.pid = customers.pid", fields);
        when(sources.resolvePlan(7L, query.getFromSql(), fields)).thenReturn(new NamedQuerySourceModels.Sources(
                Map.of("orders", "orders", "customers", "customers"), Map.of()));
        try (var request = MachineDataAccessContext.begin(principal);
             var permit = MachineDataAccessContext.authorize(Map.of("orders", decision))) {
            assertThatThrownBy(() -> protection.prepare(query, fields, "list")).isInstanceOf(AccessDeniedException.class);
        }
    }
}
