package com.auraboot.framework.meta.service.executor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * Config DTO for {@code sourceType=endpoint} virtual models, read from
 * {@code Model.extension.endpointAdapter}. Two channels are independently
 * configured: {@link ListChannel} for paginated list access and
 * {@link DetailChannel} for single-record lookup.
 *
 * <p>Phase 1 supports HTTP endpoints (GET by default) returning JSON whose
 * items/total/item can be extracted via a dotted path. GraphQL / RPC
 * adapters are deferred to phase 2.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EndpointAdapter {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ListChannel {
        private String endpoint;
        @Builder.Default private String method = "GET";
        @Builder.Default private String responseItemsPath = "data.items";
        @Builder.Default private String responseTotalPath = "data.total";
        @Builder.Default private String pageParam = "pageNum";
        @Builder.Default private String pageSizeParam = "pageSize";
        @Builder.Default private String sortFieldParam = "sortField";
        @Builder.Default private String sortOrderParam = "sortOrder";
        @Builder.Default private String filterParamMode = "json-array";
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DetailChannel {
        private String endpoint;
        @Builder.Default private String method = "GET";
        @Builder.Default private String responseItemPath = "data";
        @Builder.Default private List<String> pathParams = Collections.emptyList();
    }

    private ListChannel list;
    private DetailChannel detail;
}
