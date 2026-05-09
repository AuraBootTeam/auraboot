package com.auraboot.framework.meta.service.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointAdapterTest {

    @Test
    void list_channel_builder_defaults() {
        EndpointAdapter.ListChannel l = EndpointAdapter.ListChannel.builder().endpoint("https://x").build();
        assertThat(l.getMethod()).isEqualTo("GET");
        assertThat(l.getResponseItemsPath()).isEqualTo("data.items");
        assertThat(l.getResponseTotalPath()).isEqualTo("data.total");
        assertThat(l.getPageParam()).isEqualTo("pageNum");
        assertThat(l.getPageSizeParam()).isEqualTo("pageSize");
        assertThat(l.getSortFieldParam()).isEqualTo("sortField");
        assertThat(l.getSortOrderParam()).isEqualTo("sortOrder");
        assertThat(l.getFilterParamMode()).isEqualTo("json-array");
    }

    @Test
    void detail_channel_builder_defaults() {
        EndpointAdapter.DetailChannel d = EndpointAdapter.DetailChannel.builder().endpoint("https://x").build();
        assertThat(d.getMethod()).isEqualTo("GET");
        assertThat(d.getResponseItemPath()).isEqualTo("data");
        assertThat(d.getPathParams()).isEmpty();
    }

    @Test
    void roundtrip_via_jackson() throws Exception {
        ObjectMapper om = new ObjectMapper();
        EndpointAdapter src = EndpointAdapter.builder()
            .list(EndpointAdapter.ListChannel.builder().endpoint("https://api.example.com/list")
                .filterParamMode("flat").build())
            .detail(EndpointAdapter.DetailChannel.builder().endpoint("https://api.example.com/{id}")
                .pathParams(List.of("id")).build())
            .build();
        String json = om.writeValueAsString(src);
        EndpointAdapter copy = om.readValue(json, EndpointAdapter.class);
        assertThat(copy.getList().getEndpoint()).isEqualTo("https://api.example.com/list");
        assertThat(copy.getList().getFilterParamMode()).isEqualTo("flat");
        assertThat(copy.getDetail().getPathParams()).containsExactly("id");
    }
}
