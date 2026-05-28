package com.auraboot.framework.userattribute.service;

import com.auraboot.framework.userattribute.entity.AbUserAttribute;
import com.auraboot.framework.userattribute.mapper.AbUserAttributeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserAttributeServiceTest {

    private AbUserAttributeMapper mapper;
    private UserAttributeService service;

    @BeforeEach
    void setup() {
        mapper = mock(AbUserAttributeMapper.class);
        service = new UserAttributeService(mapper);
    }

    private AbUserAttribute attr(String code, String value) {
        AbUserAttribute a = new AbUserAttribute();
        a.setAttributeCode(code);
        a.setAttributeValue(value);
        return a;
    }

    @Test
    void getAttributes_returnsCodeValueMap() {
        when(mapper.listByUser(1L, 100L)).thenReturn(List.of(
                attr("allowed_regions", "CN,US"),
                attr("department_code", "SALES")
        ));
        Map<String, String> out = service.getAttributes(1L, 100L);
        assertThat(out)
                .hasSize(2)
                .containsEntry("allowed_regions", "CN,US")
                .containsEntry("department_code", "SALES");
    }

    @Test
    void getAttributes_emptyForUnknownUser() {
        when(mapper.listByUser(1L, 999L)).thenReturn(List.of());
        assertThat(service.getAttributes(1L, 999L)).isEmpty();
    }

    @Test
    void getAttributes_emptyForNullTenant() {
        assertThat(service.getAttributes(null, 100L)).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void getAttributes_emptyForNullUser() {
        assertThat(service.getAttributes(1L, null)).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void getOne_returnsValue() {
        when(mapper.findOne(1L, 100L, "allowed_regions"))
                .thenReturn(attr("allowed_regions", "CN"));
        assertThat(service.get(1L, 100L, "allowed_regions"))
                .contains("CN");
    }

    @Test
    void getOne_emptyOptionalForMissing() {
        when(mapper.findOne(1L, 100L, "no_such")).thenReturn(null);
        assertThat(service.get(1L, 100L, "no_such")).isEmpty();
    }
}
