package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.connector.service.ApiConnectorService;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.meta.dto.NamedQueryBatchStatusRequest;
import com.auraboot.framework.meta.dto.NamedQueryCreateRequest;
import com.auraboot.framework.meta.dto.NamedQueryUpdateRequest;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.NamedQueryFieldMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.mapper.NamedQueryVersionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NamedQueryServiceImplTest {

    private final NamedQueryMapper namedQueryMapper = mock(NamedQueryMapper.class);
    private final NamedQueryFieldMapper namedQueryFieldMapper = mock(NamedQueryFieldMapper.class);
    private final NamedQueryVersionMapper namedQueryVersionMapper = mock(NamedQueryVersionMapper.class);
    private final DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);
    private final NamedQueryRateLimiter rateLimiter = mock(NamedQueryRateLimiter.class);
    private final ApiConnectorService apiConnectorService = mock(ApiConnectorService.class);
    private final DecisionUsageIndexService usageIndexService = mock(DecisionUsageIndexService.class);

    private final NamedQueryServiceImpl service = new NamedQueryServiceImpl(
            namedQueryMapper,
            namedQueryFieldMapper,
            namedQueryVersionMapper,
            dynamicDataMapper,
            rateLimiter,
            apiConnectorService,
            usageIndexService);

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void createRefreshesDecisionUsageIndexForConnectorNamedQuery() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        when(namedQueryMapper.countByCode("customer_lookup", null)).thenReturn(0);

        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
        request.setCode("customer_lookup");
        request.setTitle("Customer Lookup");
        request.setConnectorPid("api-1");
        request.setConnectorEndpointCode("lookup");

        service.create(request);

        ArgumentCaptor<NamedQuery> captor = ArgumentCaptor.forClass(NamedQuery.class);
        verify(namedQueryMapper).insert(captor.capture());
        assertThat(captor.getValue().getPid()).isNotBlank();
        verify(usageIndexService).refreshSource("NAMED_QUERY", captor.getValue().getPid());
    }

    @Test
    void updateRefreshesDecisionUsageIndexForExistingNamedQuery() {
        NamedQuery query = connectorQuery();
        when(namedQueryMapper.findByPid("nq-1")).thenReturn(query);

        NamedQueryUpdateRequest request = new NamedQueryUpdateRequest();
        request.setDescription("Updated");

        service.update("nq-1", request);

        assertThat(query.getDescription()).isEqualTo("Updated");
        verify(namedQueryMapper).updateById(query);
        verify(usageIndexService).refreshSource("NAMED_QUERY", "nq-1");
    }

    @Test
    void updateConnectorBindingRefreshesDecisionUsageIndex() {
        NamedQuery query = new NamedQuery();
        query.setPid("nq-1");
        query.setTenantId(10L);
        query.setCode("customer_lookup");
        query.setTitle("Customer Lookup");
        query.setFromSql("SELECT id FROM customer");
        query.setStatus("draft");
        when(namedQueryMapper.findByPid("nq-1")).thenReturn(query);

        NamedQueryUpdateRequest request = new NamedQueryUpdateRequest();
        request.setConnectorPid("api-2");
        request.setConnectorEndpointCode("lookup");

        service.update("nq-1", request);

        assertThat(query.getFromSql()).isNull();
        assertThat(query.getConnectorPid()).isEqualTo("api-2");
        assertThat(query.getConnectorEndpointCode()).isEqualTo("lookup");
        verify(namedQueryMapper).updateById(query);
        verify(usageIndexService).refreshSource("NAMED_QUERY", "nq-1");
    }

    @Test
    void statusUpdateRefreshesDecisionUsageIndexForExistingNamedQuery() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        NamedQuery query = connectorQuery();
        query.setStatus("testing");
        when(namedQueryMapper.findByPid("nq-1")).thenReturn(query);

        service.updateStatus("nq-1", "published");

        verify(namedQueryMapper).updateById(query);
        verify(usageIndexService).refreshSource("NAMED_QUERY", "nq-1");
    }

    @Test
    void deleteRemovesDecisionUsageIndexSourceForNamedQuery() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        NamedQuery query = connectorQuery();
        query.setId(99L);
        query.setCode("customer_lookup");
        when(namedQueryMapper.findByPid("nq-1")).thenReturn(query);

        service.delete("nq-1");

        verify(namedQueryFieldMapper).deleteByQuery(10L, "customer_lookup");
        verify(namedQueryMapper).deleteById(99L);
        verify(usageIndexService).deleteSource("NAMED_QUERY", "nq-1");
    }

    @Test
    void batchStatusUpdateRefreshesDecisionUsageIndexForEachSuccessfulNamedQuery() {
        when(namedQueryMapper.updateStatusByPid("nq-1", "archived")).thenReturn(1);
        when(namedQueryMapper.updateStatusByPid("missing", "archived")).thenReturn(0);
        NamedQueryBatchStatusRequest request = new NamedQueryBatchStatusRequest();
        request.setPids(List.of("nq-1", "missing"));
        request.setTargetStatus("archived");

        service.batchUpdateStatus(request);

        verify(usageIndexService).refreshSource("NAMED_QUERY", "nq-1");
    }

    private NamedQuery connectorQuery() {
        NamedQuery query = new NamedQuery();
        query.setPid("nq-1");
        query.setTenantId(10L);
        query.setCode("customer_lookup");
        query.setTitle("Customer Lookup");
        query.setConnectorPid("api-1");
        query.setConnectorEndpointCode("lookup");
        query.setStatus("draft");
        return query;
    }
}
