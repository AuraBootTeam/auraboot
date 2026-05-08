package com.auraboot.framework.rag.service;

import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingService branch coverage")
class EmbeddingServiceBranchTest {

    @Mock
    private CloudConfigService cloudConfigService;

    private EmbeddingService service;

    @BeforeEach
    void setUp() {
        service = new EmbeddingService(cloudConfigService, new ObjectMapper());
    }

    @Test
    @DisplayName("embed returns null when underlying batch yields no results")
    void embedSingleNullWhenNoConfig() {
        when(cloudConfigService.getEffectiveConfig(anyLong(), anyString(), anyString())).thenReturn(null);
        assertNull(service.embed(1L, "hello", "openai"));
    }

    @Test
    @DisplayName("embedBatch returns empty list when input is null")
    void embedBatchNullInput() {
        assertTrue(service.embedBatch(1L, null, "openai").isEmpty());
        verifyNoInteractions(cloudConfigService);
    }

    @Test
    @DisplayName("embedBatch returns empty list when input is empty")
    void embedBatchEmptyInput() {
        assertTrue(service.embedBatch(1L, List.of(), "openai").isEmpty());
        verifyNoInteractions(cloudConfigService);
    }

    @Test
    @DisplayName("embedBatch returns empty when no provider config resolved")
    void embedBatchNoConfig() {
        when(cloudConfigService.getEffectiveConfig(anyLong(), anyString(), anyString())).thenReturn(null);
        assertTrue(service.embedBatch(1L, List.of("a", "b"), "openai").isEmpty());
    }

    @Test
    @DisplayName("embedBatch defaults to 'openai' when providerCode null")
    void embedBatchDefaultProviderWhenNull() {
        when(cloudConfigService.getEffectiveConfig(anyLong(), anyString(), anyString())).thenReturn(null);
        service.embedBatch(1L, List.of("a"), null);
        verify(cloudConfigService).getEffectiveConfig(1L, "embedding", "openai");
    }

    @Test
    @DisplayName("embedBatch defaults to 'openai' when providerCode blank")
    void embedBatchDefaultProviderWhenBlank() {
        when(cloudConfigService.getEffectiveConfig(anyLong(), anyString(), anyString())).thenReturn(null);
        service.embedBatch(1L, List.of("a"), "   ");
        verify(cloudConfigService).getEffectiveConfig(1L, "embedding", "openai");
    }

    @Test
    @DisplayName("embedBatch returns empty when CloudConfig present but config blank")
    void embedBatchBlankConfig() {
        CloudConfig cc = new CloudConfig();
        cc.setConfig("");
        lenient().when(cloudConfigService.getEffectiveConfig(anyLong(), anyString(), anyString())).thenReturn(cc);
        assertTrue(service.embedBatch(1L, List.of("a"), "openai").isEmpty());
    }

    @Test
    @DisplayName("embedBatch returns empty when apiKey missing in config")
    void embedBatchMissingApiKey() {
        CloudConfig cc = new CloudConfig();
        cc.setConfig("{\"baseUrl\":\"https://api.openai.com\"}");
        when(cloudConfigService.getEffectiveConfig(anyLong(), anyString(), anyString())).thenReturn(cc);
        assertTrue(service.embedBatch(1L, List.of("a"), "openai").isEmpty());
    }

    @Test
    @DisplayName("embedBatch returns empty when apiKey is blank")
    void embedBatchBlankApiKey() {
        CloudConfig cc = new CloudConfig();
        cc.setConfig("{\"apiKey\":\"\",\"baseUrl\":\"https://api.openai.com\"}");
        when(cloudConfigService.getEffectiveConfig(anyLong(), anyString(), anyString())).thenReturn(cc);
        assertTrue(service.embedBatch(1L, List.of("a"), "openai").isEmpty());
    }

    @Test
    @DisplayName("embedBatch swallows CloudConfig lookup exception and returns empty")
    void embedBatchSwallowsConfigException() {
        when(cloudConfigService.getEffectiveConfig(anyLong(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        assertTrue(service.embedBatch(1L, List.of("a"), "openai").isEmpty());
    }

    @Test
    @DisplayName("embedBatch on bad JSON in config returns empty")
    void embedBatchBadJson() {
        CloudConfig cc = new CloudConfig();
        cc.setConfig("{not json");
        when(cloudConfigService.getEffectiveConfig(anyLong(), anyString(), anyString())).thenReturn(cc);
        assertTrue(service.embedBatch(1L, List.of("a"), "openai").isEmpty());
    }

    @Test
    @DisplayName("embedBatch fills nulls for failed batch when API call throws (unreachable host)")
    void embedBatchApiCallFails() {
        CloudConfig cc = new CloudConfig();
        cc.setConfig("{\"apiKey\":\"sk-x\",\"baseUrl\":\"http://127.0.0.1:1\",\"defaultModel\":\"m\",\"maxBatchSize\":2}");
        when(cloudConfigService.getEffectiveConfig(anyLong(), anyString(), anyString())).thenReturn(cc);

        List<float[]> result = service.embedBatch(1L, List.of("a", "b"), "openai");
        assertEquals(2, result.size());
        assertNull(result.get(0));
        assertNull(result.get(1));
    }
}
