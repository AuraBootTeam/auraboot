package com.auraboot.framework.meta.service.impl;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.executor.ExecutorRegistry;
import org.junit.jupiter.api.*;
import com.auraboot.framework.application.tenant.MetaContext;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Listing preserves named-query delegation and stable physical pagination. */
class DynamicDataListingSupportTest {
    @BeforeEach void context() { MetaContext.setContext(42L,43L,"u-43","tester"); }
    @AfterEach void clear() { MetaContext.clear(); }
    final NamedQueryService queries=mock(NamedQueryService.class);
    final MetaModelMapper models=mock(MetaModelMapper.class);
    final DynamicDataListingSupport.EnrichAuditUserDisplayFieldsOperation audit=mock(DynamicDataListingSupport.EnrichAuditUserDisplayFieldsOperation.class);
    final DynamicDataListingSupport support=new DynamicDataListingSupport(mock(QueryBuilderService.class),queries,mock(SecureSqlRewriter.class),mock(DynamicDataMapper.class),mock(DataPermissionEngine.class),mock(FieldMaskService.class),mock(DataDomainService.class),models,mock(ExecutorRegistry.class),(m,r,a)->r,(m,r)->r,audit,m -> null);
    @Test void namedQueryReceivesScalarSetAndRangeConditionsAndRequestedSort() {
        var request=DynamicQueryRequest.builder().pageNum(2).pageSize(15).auditUserDisplayFields(List.of("created_by"))
                .conditions(List.of(QueryCondition.builder().fieldName("status").operator(QueryCondition.Operator.IN).values(List.of("open","paid")).build(),QueryCondition.builder().fieldName("amount").operator(QueryCondition.Operator.BETWEEN).values(List.of(10,100)).build(),QueryCondition.builder().fieldName("owner").operator(QueryCondition.Operator.EQ).value("u-42").build()))
                .sortFields(List.of(SortField.builder().fieldName("amount").direction(SortField.SortDirection.DESC).build())).build();
        var rows=List.<Map<String,Object>>of(Map.of("pid","invoice-42")); var page=PaginationResult.of(rows,1L,2,15);
        when(queries.executeQuery(eq("invoice_summary"),any())).thenReturn(page);
        assertThat(support.listByQueryCode("invoice_summary",request)).isSameAs(page);
        var captured=org.mockito.ArgumentCaptor.forClass(NamedQueryTestRequest.class); verify(queries).executeQuery(eq("invoice_summary"),captured.capture());
        var q=captured.getValue(); assertThat(q.getPage()).isEqualTo(2); assertThat(q.getSize()).isEqualTo(15); assertThat(q.getExecuteQuery()).isTrue();
        assertThat(q.getWhereConditions().get(0).get("value").toString()).isEqualTo("[\"open\",\"paid\"]");
        assertThat(q.getWhereConditions().get(1).get("value").toString()).isEqualTo("[10,100]");
        assertThat(q.getWhereConditions().get(2).get("value").asText()).isEqualTo("u-42");
        assertThat(q.getOrderConditions().get(0).get("direction").asText()).isEqualTo("DESC");
        verify(audit).execute(rows,List.of("created_by"));
    }
    @Test void namedQueryWithoutOptionalFiltersAndCustomParametersHaveDeterministicDefaults() {
        var page=PaginationResult.<Map<String,Object>>of(List.of(),0L,1,20);
        when(queries.executeQuery(eq("invoice_summary"),any())).thenReturn(page);
        support.listByQueryCode("invoice_summary",DynamicQueryRequest.builder().pageNum(1).pageSize(20).build());
        verify(queries).executeQuery(eq("invoice_summary"),argThat(q -> q.getWhereConditions()==null && q.getOrderConditions()==null));
        assertThat(support.executeCustomQuery("invoice","invoice_summary",Map.of("owner","u-42"))).isEmpty();
        verify(queries).executeQuery(eq("invoice_summary"),argThat(q -> Map.of("owner","u-42").equals(q.getParameters()) && q.getSize()==1000 && q.getPage()==1));
        assertThat(support.executeCustomQuery("invoice","invoice_summary",null)).isEmpty();
        verify(queries).executeQuery(eq("invoice_summary"),argThat(q -> q.getParameters()!=null && q.getParameters().isEmpty()));
    }
    @Test void viewNamedQueryAliasTrimsMetadataAndFallsBackToModelCode() {
        assertThat(support.resolveViewNamedQueryCode("invoice")).isEqualTo("invoice");
        var model=new Model(); when(models.findCurrentByCode("invoice")).thenReturn(model);
        assertThat(support.resolveViewNamedQueryCode("invoice")).isEqualTo("invoice");
        var ext=new ExtensionBean(); ext.setDynamicProperty("namedQuery","  invoice_summary "); model.setExtension(ext);
        assertThat(support.resolveViewNamedQueryCode("invoice")).isEqualTo("invoice_summary");
        ext.setDynamicProperty("namedQuery"," "); assertThat(support.resolveViewNamedQueryCode("invoice")).isEqualTo("invoice");
    }

    private DynamicDataListingSupport physicalListing(QueryBuilderService builder) {
        return new DynamicDataListingSupport(builder,queries,mock(SecureSqlRewriter.class),
                mock(DynamicDataMapper.class),mock(DataPermissionEngine.class),mock(FieldMaskService.class),
                mock(DataDomainService.class),models,mock(ExecutorRegistry.class),(m,r,a)->r,(m,r)->r,audit,m -> null);
    }

    private ModelDefinition physicalModel() {
        return ModelDefinition.builder().code("ledger_sort").tableName("mt_ledger_sort")
                .primaryKey("publicKey").fields(List.of(
                        FieldDefinition.builder().code("publicKey").columnName("public_ref").dataType("string").build(),
                        FieldDefinition.builder().code("amount").columnName("stored_amount").dataType("integer").build()))
                .build();
    }

    @Test void defaultPhysicalPagingKeepsUpdatedOrderAndAddsUniqueRecordIdentity() {
        var builder=new QueryBuilderServiceImpl(mock(DynamicDataMapper.class));
        var model=physicalModel();model.setPrimaryKey(null);
        var query=builder.buildConditionQuery(model,List.of());
        query=physicalListing(builder).applyStableListSort(query,model,null);
        assertThat(query.getSql()).containsIgnoringCase("ORDER BY updated_at DESC, pid ASC");
    }

    @Test void physicalPagingPreservesRequestedPriorityAndMapsDeclaredIdentityToItsColumn() {
        var builder=new QueryBuilderServiceImpl(mock(DynamicDataMapper.class));
        var model=physicalModel();
        var requested=List.of(SortField.builder().fieldName("amount").direction(SortField.SortDirection.DESC).build());
        var query=physicalListing(builder).applyStableListSort(builder.buildConditionQuery(model,List.of()),model,requested);
        assertThat(query.getSql()).containsIgnoringCase("ORDER BY stored_amount DESC, public_ref ASC");
        assertThat(requested).extracting(SortField::getFieldName).containsExactly("amount");
    }

    @Test void physicalPagingPreservesExplicitUniqueIdentityDirectionWithoutAddingConflictingOrder() {
        var builder=new QueryBuilderServiceImpl(mock(DynamicDataMapper.class));
        var model=physicalModel();
        var requested=List.of(SortField.builder().fieldName("publicKey").direction(SortField.SortDirection.DESC).build());
        var query=physicalListing(builder).applyStableListSort(builder.buildConditionQuery(model,List.of()),model,requested);
        assertThat(query.getSql()).endsWith("ORDER BY public_ref DESC");
    }
    @Test void requiredMemberPredicateIsSharedByDataCountAndCursorUnderAnAllPermit() {
        var mapper=mock(DynamicDataMapper.class);var policies=mock(DataPermissionEngine.class);
        var builder=new QueryBuilderServiceImpl(mapper);var model=RequiredReadReferencesTest.model(10);
        String allowed=RequiredReadReferencesTest.id(9),predicate="pid IN ('"+allowed+"')";
        when(policies.buildRequiredReadFilterForQuery(42L,"fixture_bundle",43L,null,null)).thenReturn("AND "+predicate);
        var listing=new DynamicDataListingSupport(builder,queries,new SecureSqlRewriter(),mapper,policies,
                mock(FieldMaskService.class),mock(DataDomainService.class),models,mock(ExecutorRegistry.class),(m,r,a)->r,(m,r)->r,audit,m->model);
        when(mapper.selectByQuery(anyString(),anyMap())).thenAnswer(call->{
            assertThat((String)call.getArgument(0)).contains(predicate);
            return new ArrayList<>(List.of(new LinkedHashMap<>(Map.<String,Object>of("pid",allowed)))); });
        when(mapper.countByQuery(anyString(),anyMap())).thenAnswer(call->{assertThat((String)call.getArgument(0)).contains(predicate);return 1L;});
        MetaContext.runWithCommandPermitScope("ALL",()->{
            var offset=listing.list("fixture_bundle",DynamicQueryRequest.builder().pageNum(1).pageSize(2).build());
            assertThat(offset.getTotal()).isEqualTo(1L);assertThat(offset.getRecords()).hasSize(1);
            var cursor=listing.list("fixture_bundle",DynamicQueryRequest.builder().pageNum(1).pageSize(2).cursor(RequiredReadReferencesTest.id(1)).build());
            assertThat(cursor.getTotal()).isEqualTo(1L);assertThat(cursor.getNextCursor()).isEqualTo(allowed);
        });
        verify(policies,never()).buildRowFilter(any(),anyString(),any());
        verify(policies,times(2)).buildRequiredReadFilterForQuery(42L,"fixture_bundle",43L,null,null);
    }
    @Test void differentQueriesNeverReuseARequiredSubsetAndCountExcludesCursorContinuation() {
        var mapper=mock(DynamicDataMapper.class);var policies=mock(DataPermissionEngine.class);
        var builder=new QueryBuilderServiceImpl(mapper);var model=RequiredReadReferencesTest.model(10);
        var fields=new ArrayList<>(model.getFields());fields.add(FieldDefinition.builder().code("name").columnName("name").dataType("string").searchable(true).build());model.setFields(fields);
        var masks=mock(FieldMaskService.class);when(masks.applyMaskingForList(anyString(),anyList(),any()))
                .thenAnswer(call->call.getArgument(1));
        var listing=new DynamicDataListingSupport(builder,queries,new SecureSqlRewriter(),mapper,policies,
                masks,mock(DataDomainService.class),models,mock(ExecutorRegistry.class),(m,r,a)->r,(m,r)->r,audit,m->model);
        var expected=new java.util.concurrent.atomic.AtomicReference<String>();
        when(policies.buildRowFilterForQuery(eq(42L),eq("fixture_bundle"),eq(43L),anyList(),anyString())).thenAnswer(call->{
            List<QueryCondition> conditions=call.getArgument(3);String pid=(String)conditions.get(0).getValue();
            expected.set(pid);assertThat((String)call.getArgument(4)).isEqualTo(pid.equals(RequiredReadReferencesTest.id(9))?"first":"second");
            return "AND pid IN ('"+pid+"')";
        });
        when(mapper.selectByQuery(anyString(),anyMap())).thenAnswer(call->{
            assertThat((String)call.getArgument(0)).contains("pid IN ('"+expected.get()+"')","ILIKE");
            return new ArrayList<>(List.of(new LinkedHashMap<>(Map.<String,Object>of("pid",expected.get()))));
        });
        when(mapper.countByQuery(anyString(),anyMap())).thenAnswer(call->{
            assertThat((String)call.getArgument(0)).contains("pid IN ('"+expected.get()+"')","ILIKE").doesNotContain("pid >","LIMIT","OFFSET");
            return 1L;
        });
        try(var ignored=DynamicDataQueryScope.open()) {
            for(int n:List.of(9,10)) {
                String pid=RequiredReadReferencesTest.id(n);
                var condition=QueryCondition.builder().fieldName("pid").operator(QueryCondition.Operator.EQ).value(pid).build();
                var page=listing.list("fixture_bundle",DynamicQueryRequest.builder().conditions(List.of(condition))
                        .keyword(n==9?"first":"second").pageNum(1).pageSize(1).cursor(RequiredReadReferencesTest.id(1)).build());
                assertThat(page.getTotal()).isEqualTo(1L);assertThat(page.getRecords()).extracting(r->r.get("pid")).containsExactly(pid);
                assertThat(page.getNextCursor()).isEqualTo(pid);
            }
        }
        verify(policies,times(2)).buildRowFilterForQuery(eq(42L),eq("fixture_bundle"),eq(43L),anyList(),anyString());
        verify(policies,never()).buildRowFilter(any(),anyString(),any());
    }
}
