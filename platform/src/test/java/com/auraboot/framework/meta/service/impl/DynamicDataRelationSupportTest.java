package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Relation isolation, fail-secure authorization and idempotent mutation contracts. */
class DynamicDataRelationSupportTest {
    final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    final DataPermissionEngine permissions = mock(DataPermissionEngine.class);
    final DataDomainService domains = mock(DataDomainService.class);
    final DynamicDataRelationSupport support = new DynamicDataRelationSupport(mapper, permissions, domains, code -> null);
    RelationDefinition relation(boolean many) {
        return RelationDefinition.builder().targetModel("invoice").targetTable("ab_invoice")
                .sourceField("owner_id").targetField("invoice_id").joinTable("ab_owner_invoice")
                .relationType(many ? RelationDefinition.RelationType.MANY_TO_MANY : RelationDefinition.RelationType.ONE_TO_MANY).build();
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void queriesBindTenantAndRecordAndApplyAllAuthorizationLayers(boolean many) {
        var rows = List.<Map<String,Object>>of(new HashMap<>(Map.of("id", "i-1", "amount", 50)));
        var masked = List.<Map<String,Object>>of(new HashMap<>(Map.of("id", "i-1", "amount", "***")));
        if (many) when(mapper.selectByQuery(contains("FROM ab_owner_invoice"), anyMap())).thenReturn(List.of(Map.of("invoice_id", "i-1"), Map.of("invoice_id", "i-2")));
        when(permissions.buildRowFilter(42L, "invoice", 43L)).thenReturn("AND owner_id = 43");
        when(domains.buildDomainFilter("invoice", 43L)).thenReturn("AND domain_id = 7");
        var rule = mock(FieldMaskRule.class);
        when(permissions.getFieldMaskRules(42L,"invoice",43L)).thenReturn(List.of(rule));
        when(mapper.selectByQuery(contains("FROM ab_invoice "),anyMap())).thenAnswer(call -> {
            String sql = call.getArgument(0); Map<String,Object> params = call.getArgument(1);
            assertThat(sql).contains("tenant_id = #{params.tenantId}", "AND owner_id = 43", "AND domain_id = 7");
            assertThat(params).containsEntry("tenantId",42L);
            if (many) { assertThat(sql).contains("#{params.id_0},#{params.id_1}"); assertThat(params).containsEntry("id_0","i-1").containsEntry("id_1","i-2"); }
            else { assertThat(sql).endsWith("LIMIT 5"); assertThat(params).containsEntry("recordId","o-1"); }
            return rows;
        });
        when(permissions.applyFieldMasking(rows,List.of(rule))).thenReturn(masked);
        assertThat(support.getRelationData(relation(many),"o-1",Map.of("limit",5),42L,43L)).isEqualTo(masked);
        if (many) verify(mapper).selectByQuery(contains("FROM ab_owner_invoice"),argThat(p -> p.get("tenantId").equals(42L) && p.get("recordId").equals("o-1")));
    }
    @ParameterizedTest @ValueSource(strings = {"row-direct", "domain-direct", "mask-direct", "row-many", "domain-many", "mask-many"})
    void authorizationFailuresNeverReturnUnprotectedRows(String stage) {
        boolean many = stage.endsWith("many");
        if (many) when(mapper.selectByQuery(contains("FROM ab_owner_invoice"),anyMap())).thenReturn(List.of(Map.of("invoice_id","i-1")));
        if (stage.startsWith("row")) when(permissions.buildRowFilter(42L,"invoice",43L)).thenThrow(new IllegalStateException("offline"));
        if (stage.startsWith("domain")) when(domains.buildDomainFilter("invoice",43L)).thenThrow(new IllegalStateException("offline"));
        if (stage.startsWith("mask")) {
            when(mapper.selectByQuery(contains("FROM ab_invoice "),anyMap())).thenReturn(List.of(Map.of("secret","value")));
            when(permissions.getFieldMaskRules(42L,"invoice",43L)).thenThrow(new IllegalStateException("offline"));
        }
        assertThatThrownBy(() -> support.getRelationData(relation(many),"o-1",null,42L,43L)).isInstanceOf(MetaServiceException.class).hasCauseInstanceOf(IllegalStateException.class);
        if (!stage.startsWith("mask")) verify(mapper,never()).selectByQuery(contains("FROM ab_invoice "),anyMap());
    }
    @ParameterizedTest @ValueSource(strings = {"table", "target", "source", "join"})
    void maliciousIdentifiersAreRejectedBeforeAnyQuery(String field) {
        var r = relation(true);
        switch(field) { case "table" -> r.setTargetTable("invoice;DROP TABLE invoice"); case "target" -> r.setTargetField("id OR 1=1"); case "source" -> r.setSourceField("id--"); default -> r.setJoinTable("join;DELETE"); }
        assertThatThrownBy(() -> support.getRelationData(r,"o",null,42L,43L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper,permissions,domains);
    }
    @Test void absentJoinTargetsReturnEmptyWithoutTargetAccess() {
        when(mapper.selectByQuery(anyString(),anyMap())).thenReturn(List.of(),List.of(Collections.singletonMap("invoice_id",null)));
        assertThat(support.getRelationData(relation(true),"o",null,42L,43L)).isEmpty();
        assertThat(support.getRelationData(relation(true),"o",null,42L,43L)).isEmpty();
        verifyNoInteractions(permissions,domains);
    }
    @Test void createIsIdempotentAndReportsPartialFailureWithTenantBoundWrites() {
        doAnswer(call -> {
            Map<String,Object> row = call.getArgument(1);
            assertThat(row).containsEntry("tenant_id",42L).containsEntry("owner_id","o").containsKey("created_at");
            if (row.get("invoice_id").equals("duplicate")) throw new DuplicateKeyException("exists");
            if (row.get("invoice_id").equals("bad")) throw new IllegalStateException("storage failure");
            return 1;
        }).when(mapper).insert(eq("ab_owner_invoice"),anyMap());
        var result = support.createRelations(relation(true),"o",List.of("new","duplicate","bad"),42L);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getSuccessRecordIds()).containsExactly("new","duplicate");
        assertThat(result.getFailedRecordIds()).containsExactly("bad");
        assertThat(result.getSuccessCount()).isEqualTo(2); assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(support.createRelations(relation(true),"o",List.of("new"),42L).getSuccess()).isTrue();
    }
    @Test void removalIsTenantScopedAndDistinguishesMissingRowsAndStorageFailure() {
        when(mapper.delete(eq("ab_owner_invoice"),anyMap())).thenAnswer(call -> {
            Map<String,Object> conditions = call.getArgument(1);
            assertThat(conditions).containsEntry("tenant_id",42L).containsEntry("owner_id","o");
            return switch(conditions.get("invoice_id").toString()) { case "new" -> 1; case "absent" -> 0; default -> throw new IllegalStateException("storage failure"); };
        });
        var result = support.removeRelations(relation(true),"o",List.of("new","absent","bad"),42L);
        assertThat(result.getSuccess()).isFalse(); assertThat(result.getSuccessRecordIds()).containsExactly("new");
        assertThat(result.getFailedRecordIds()).containsExactly("absent","bad");
        assertThat(support.removeRelations(relation(true),"o",List.of("new"),42L).getSuccess()).isTrue();
    }
    @Test void nonManyToManyMutationsAreRejected() {
        assertThatThrownBy(() -> support.createRelations(relation(false),"o",List.of("i"),42L)).isInstanceOf(MetaServiceException.class);
        assertThatThrownBy(() -> support.removeRelations(relation(false),"o",List.of("i"),42L)).isInstanceOf(MetaServiceException.class);
        verifyNoInteractions(mapper);
    }
}
