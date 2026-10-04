package com.auraboot.framework.semantic.controller;

import com.auraboot.framework.semantic.service.SemanticPreaggService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SemanticPreaggControllerTest {

    @Mock
    private SemanticPreaggService service;
    @Captor
    private ArgumentCaptor<List<String>> dimensions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SemanticPreaggController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void omittedDimensionsCreateAnUngroupedAggregate() throws Exception {
        assertDimensions(createRequest(), List.of());
    }

    @Test
    void emptyDimensionsCreateAnUngroupedAggregate() throws Exception {
        assertDimensions(createRequest().param("dimensionCodes", ""), List.of());
    }

    @Test
    void commaSeparatedDimensionsRetainTheirOrder() throws Exception {
        assertDimensions(createRequest().param("dimensionCodes", "region,category"),
                List.of("region", "category"));
    }

    @Test
    void repeatedDimensionsRetainTheirOrder() throws Exception {
        assertDimensions(createRequest().param("dimensionCodes", "region", "category"),
                List.of("region", "category"));
    }

    private MockHttpServletRequestBuilder createRequest() {
        return post("/api/semantic/preaggs")
                .param("name", "Scheduled page count")
                .param("semanticModelPid", "model-pid")
                .param("metricCode", "page_count_metric");
    }

    private void assertDimensions(MockHttpServletRequestBuilder request,
                                  List<String> expected) throws Exception {
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        verify(service).create(eq("Scheduled page count"), eq("model-pid"),
                eq("page_count_metric"), dimensions.capture(), eq(60));
        assertThat(dimensions.getValue()).containsExactlyElementsOf(expected);
    }
}
