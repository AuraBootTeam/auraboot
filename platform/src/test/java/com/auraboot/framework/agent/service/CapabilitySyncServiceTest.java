package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.mapper.AbCapabilityMapper;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
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
    void skipsWorkflowCollectionWhenProductTableIsAbsent() {
        DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);
        AbCapabilityMapper capabilityMapper = mock(AbCapabilityMapper.class);
        CapabilityGraphService graphService = mock(CapabilityGraphService.class);
        CapabilityMappingSupport mappingSupport = mock(CapabilityMappingSupport.class);
        TableMetadataService tableMetadataService = mock(TableMetadataService.class);

        when(dynamicDataMapper.selectByQuery(anyString(), any(Map.class))).thenReturn(List.of());
        when(graphService.buildCapabilityGraph(42L)).thenReturn(Map.of());
        when(capabilityMapper.selectList(any())).thenReturn(List.of());
        when(tableMetadataService.tableExists("ab_bpm_process_definition")).thenReturn(false);

        CapabilitySyncService service = new CapabilitySyncService(
                dynamicDataMapper,
                new ObjectMapper(),
                capabilityMapper,
                graphService,
                mappingSupport,
                tableMetadataService);

        assertEquals(0, service.syncCapabilities(42L).join());
        verify(tableMetadataService).tableExists("ab_bpm_process_definition");
        verify(dynamicDataMapper, never()).selectByQuery(
                org.mockito.ArgumentMatchers.contains("ab_bpm_process_definition"), any(Map.class));
    }
}
