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

/** Named-query delegation preserves filter shape, sorting and display enrichment. */
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
}
