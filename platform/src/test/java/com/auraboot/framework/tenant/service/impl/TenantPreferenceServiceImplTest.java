package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.tenant.dao.entity.TenantPreference;
import com.auraboot.framework.tenant.dao.mapper.TenantPreferenceMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantPreferenceServiceImpl")
class TenantPreferenceServiceImplTest {

    @Mock
    private TenantPreferenceMapper preferenceMapper;

    @InjectMocks
    private TenantPreferenceServiceImpl service;

    private final ObjectMapper mapper = new ObjectMapper();

    private TenantPreference fixture(String key, JsonNode value) {
        TenantPreference p = new TenantPreference();
        p.setId(1L);
        p.setPid("pid-1");
        p.setTenantId(10L);
        p.setPreferenceKey(key);
        p.setPreferenceValue(value);
        return p;
    }

    @Test
    @DisplayName("getPreference returns value when present")
    void getPreferenceReturnsValue() {
        JsonNode node = mapper.valueToTree(Map.of("a", 1));
        when(preferenceMapper.selectOne(any(QueryWrapper.class)))
                .thenReturn(fixture("k", node));

        JsonNode result = service.getPreference(10L, "k");

        assertNotNull(result);
        assertEquals(1, result.get("a").asInt());
    }

    @Test
    @DisplayName("getPreference returns null when no record")
    void getPreferenceReturnsNullWhenAbsent() {
        when(preferenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertNull(service.getPreference(10L, "missing"));
    }

    @Test
    @DisplayName("setPreference updates existing record")
    void setPreferenceUpdatesExisting() {
        JsonNode oldVal = mapper.valueToTree(Map.of("a", 1));
        JsonNode newVal = mapper.valueToTree(Map.of("a", 2));
        TenantPreference existing = fixture("k", oldVal);
        when(preferenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        service.setPreference(10L, "k", newVal);

        ArgumentCaptor<TenantPreference> captor = ArgumentCaptor.forClass(TenantPreference.class);
        verify(preferenceMapper).updateById(captor.capture());
        verify(preferenceMapper, never()).insert(any(TenantPreference.class));
        assertEquals(newVal, captor.getValue().getPreferenceValue());
    }

    @Test
    @DisplayName("setPreference inserts when no existing record")
    void setPreferenceInsertsWhenAbsent() {
        JsonNode val = mapper.valueToTree(Map.of("k", "v"));
        when(preferenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        service.setPreference(11L, "newKey", val);

        ArgumentCaptor<TenantPreference> captor = ArgumentCaptor.forClass(TenantPreference.class);
        verify(preferenceMapper).insert(captor.capture());
        TenantPreference inserted = captor.getValue();
        assertEquals(11L, inserted.getTenantId());
        assertEquals("newKey", inserted.getPreferenceKey());
        assertEquals(val, inserted.getPreferenceValue());
        assertNotNull(inserted.getPid());
    }

    @Test
    @DisplayName("getPreferencesByPrefix maps key->value")
    void getPreferencesByPrefixReturnsMap() {
        JsonNode v1 = mapper.valueToTree(Map.of("x", 1));
        JsonNode v2 = mapper.valueToTree(Map.of("y", 2));
        List<TenantPreference> rows = Arrays.asList(
                fixture("ui.theme", v1),
                fixture("ui.lang", v2)
        );
        when(preferenceMapper.selectList(any(QueryWrapper.class))).thenReturn(rows);

        Map<String, JsonNode> result = service.getPreferencesByPrefix(10L, "ui.");

        assertEquals(2, result.size());
        assertEquals(v1, result.get("ui.theme"));
        assertEquals(v2, result.get("ui.lang"));
    }

    @Test
    @DisplayName("getPreferencesByPrefix returns empty map when no rows")
    void getPreferencesByPrefixEmpty() {
        when(preferenceMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        Map<String, JsonNode> result = service.getPreferencesByPrefix(10L, "ui.");

        assertEquals(0, result.size());
    }
}
