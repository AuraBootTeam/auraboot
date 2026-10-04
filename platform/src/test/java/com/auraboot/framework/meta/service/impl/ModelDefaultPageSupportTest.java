package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.constant.DslRegistry;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ModelDefaultPageSupportTest {
    private final List<Object[]> inserted = new ArrayList<>();
    private final PageSchemaMapper mapper = mock(PageSchemaMapper.class, invocation -> {
        if (invocation.getMethod().getName().equals("insertForPluginImport")) {
            inserted.add(invocation.getArguments().clone());
            return 1;
        }
        return Answers.RETURNS_DEFAULTS.answer(invocation);
    });

    @AfterEach
    void clearContext() { MetaContext.clear(); }

    @Test
    void skipAllAvoidsQueriesAndEnvironmentResolution() {
        create(model(Map.of("skipDefaultPages", true)), new ObjectMapper(), tenant -> {
            throw new AssertionError("No environment should be requested");
        });
        verifyNoInteractions(mapper);
        assertThat(inserted).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "form", "detail"})
    void individualSkipFlagsKeepOtherPageKinds(String kind) {
        String flag = "skip" + Character.toUpperCase(kind.charAt(0)) + kind.substring(1) + "Page";
        create(model(Map.of(flag, true)), new ObjectMapper(), tenant -> 16L);
        assertThat(inserted).hasSize(2);
        assertThat(inserted.stream().map(row -> row[9])).doesNotContain(kind);
        verify(mapper, never()).selectAnyByPageKey("orders_" + kind);
    }

    @Test
    void existingPagesAreSkippedBeforeResolvingEnvironment() {
        when(mapper.selectAnyByPageKey(anyString())).thenReturn(new PageSchema());
        create(model(Map.of()), new ObjectMapper(), tenant -> {
            throw new AssertionError("Existing pages must not resolve an environment");
        });
        verify(mapper).selectAnyByPageKey("orders_list");
        verify(mapper).selectAnyByPageKey("orders_form");
        verify(mapper).selectAnyByPageKey("orders_detail");
        assertThat(inserted).isEmpty();
    }

    @Test
    void contextEnvironmentTakesPrecedenceAndStubContractIsPreserved() throws Exception {
        MetaContext.setContext(41L, 7L, "actor-pid", "actor");
        MetaContext.setEnvironmentId(16L);
        ObjectMapper json = new ObjectMapper();
        create(model(Map.of("displayName", "Order \"line\"")), json, tenant -> {
            throw new AssertionError("Context environment must take precedence");
        });
        assertThat(inserted).hasSize(3);
        assertThat(inserted.stream().map(row -> row[4])).containsExactly("orders_list", "orders_form", "orders_detail");
        assertThat(inserted.stream().map(row -> row[0])).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(inserted.stream().map(row -> row[16])).doesNotContainNull().containsOnly(inserted.getFirst()[16]);
        for (Object[] row : inserted) {
            assertThat(row[1]).isEqualTo(41L);
            assertThat(row[2]).isEqualTo(16L);
            assertThat(row[3]).isEqualTo("published");
            assertThat(row[5]).isEqualTo("orders");
            assertThat(json.readTree((String) row[7]).get("zh-CN").asText()).isEqualTo(row[6]);
            assertThat(json.readTree((String) row[7]).get("en").asText()).isEqualTo("Order \"line\" " + row[9]);
            assertThat(row[10]).isEqualTo("admin");
            assertThat(json.readTree((String) row[11]).get("type").asText()).isEqualTo("stack");
            assertThat(json.readTree((String) row[12]).isArray()).isTrue();
            assertThat(row[13]).isEqualTo(DslRegistry.PAGE_SCHEMA_CURRENT_VERSION);
            assertThat(row[14]).isEqualTo(false);
            assertThat(row[17]).isEqualTo(0);
            assertThat(row[18]).isEqualTo("{\"auto_created\":true}");
            assertThat(row[19]).isNull();
        }
    }

    @Test
    void defaultEnvironmentReceivesTenantAndBlankNameUsesModelCode() {
        List<Long> tenants = new ArrayList<>();
        create(model(Map.of("displayName", "  ")), new ObjectMapper(), tenant -> {
            tenants.add(tenant);
            return 29L;
        });
        assertThat(tenants).containsExactly(41L, 41L, 41L);
        assertThat(inserted.stream().map(row -> row[2])).containsOnly(29L);
        assertThat(inserted.stream().map(row -> row[6])).containsExactly("orders列表", "orders表单", "orders详情");
    }

    @Test
    void titleSerializationFailureAbortsWithoutWritingAndPreservesCause() throws Exception {
        ObjectMapper json = mock(ObjectMapper.class);
        JsonProcessingException failure = new JsonProcessingException("invalid title") {};
        when(json.writeValueAsString(any())).thenThrow(failure);
        assertThatThrownBy(() -> create(model(Map.of()), json, tenant -> {
            throw new AssertionError("Serialization must precede environment resolution");
        })).isInstanceOf(IllegalStateException.class).hasCause(failure);
        assertThat(inserted).isEmpty();
    }

    private void create(Model model, ObjectMapper json, Function<Long, Long> environment) {
        ModelDefaultPageSupport.create(model, mapper, json, environment,
                LoggerFactory.getLogger(MetaModelServiceImpl.class));
    }

    private static Model model(Map<String, Object> values) {
        Model model = new Model();
        model.setCode("orders");
        model.setTenantId(41L);
        ExtensionBean extension = new ExtensionBean();
        extension.setExtension(values);
        model.setExtension(extension);
        return model;
    }
}
