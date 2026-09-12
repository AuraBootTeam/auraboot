package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.mapper.AbCapabilityMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilitySyncServiceTest {

    @Test
    void skipsWorkflowCollectionWhenProductCapabilityIsAbsent() {
        DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);
        AbCapabilityMapper capabilityMapper = mock(AbCapabilityMapper.class);
        CapabilityGraphService graphService = mock(CapabilityGraphService.class);
        CapabilityMappingSupport mappingSupport = mock(CapabilityMappingSupport.class);
        WorkflowCapabilityRegistry workflowCapabilities = mock(WorkflowCapabilityRegistry.class);

        when(dynamicDataMapper.selectByQuery(anyString(), any(Map.class))).thenReturn(List.of());
        when(graphService.buildCapabilityGraph(42L)).thenReturn(Map.of());
        when(capabilityMapper.selectList(any())).thenReturn(List.of());
        when(workflowCapabilities.available("catalog.list")).thenReturn(false);

        CapabilitySyncService service = new CapabilitySyncService(
                dynamicDataMapper,
                new ObjectMapper(),
                capabilityMapper,
                graphService,
                mappingSupport,
                workflowCapabilities);

        assertEquals(0, service.syncCapabilities(42L).join());
        verify(workflowCapabilities).available("catalog.list");
        verify(workflowCapabilities, never()).execute(anyString(), any());
    }
}
