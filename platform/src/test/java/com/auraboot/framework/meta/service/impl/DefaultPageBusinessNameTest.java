package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DefaultPageBusinessNameTest {
    @AfterEach void tearDown() { MetaContext.clear(); }

    @Test void generatedPagesKeepTechnicalKeysSeparateFromBusinessNamesAndEscapeTitles() throws Exception {
        List<Object[]> inserted = new ArrayList<>();
        PageSchemaMapper mapper = mock(PageSchemaMapper.class, invocation -> {
            if (invocation.getMethod().getName().equals("insertForPluginImport")) {
                inserted.add(invocation.getArguments().clone());
                return 1;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        MetaModelServiceImpl service = mock(MetaModelServiceImpl.class, CALLS_REAL_METHODS);
        ObjectMapper json = new ObjectMapper();
        ReflectionTestUtils.setField(service, "pageSchemaMapper", mapper);
        ReflectionTestUtils.setField(service, "objectMapper", json);
        Model model = new Model();
        model.setCode("cbf_order_line");
        model.setTenantId(41L);
        ExtensionBean extension = new ExtensionBean();
        extension.setExtension(Map.of("displayName", "履约订单\"明细\""));
        model.setExtension(extension);
        MetaContext.setContext(41L, 7L, "actor-pid", "管理员");
        MetaContext.setEnvironmentId(16L);
        ReflectionTestUtils.invokeMethod(service, "autoCreateDefaultPages", model);
        assertThat(inserted).hasSize(3);
        assertThat(inserted.stream().map(row -> row[4])).containsExactly(
                "cbf_order_line_list", "cbf_order_line_form", "cbf_order_line_detail");
        assertThat(inserted.stream().map(row -> row[6])).containsExactly(
                "履约订单\"明细\"列表", "履约订单\"明细\"表单", "履约订单\"明细\"详情");
        for (Object[] row : inserted) {
            assertThat(row[1]).isEqualTo(41L);
            assertThat(row[2]).isEqualTo(16L);
            assertThat(json.readTree((String) row[7]).get("zh-CN").asText()).isEqualTo(row[6]);
            assertThat(row[18]).isEqualTo("{\"auto_created\":true}");
        }
    }
}
