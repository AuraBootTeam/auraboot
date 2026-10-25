package com.auraboot.framework.rag.d7;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class D7CompiledKnowledgePage {

    private int schemaVersion;
    private String id;
    private String type;
    private String status;
    private String staleStatus;
    private String visibility;
    private String tenantScope;
    private String confidence;
    private String title;
    private String summary;
    private String body;
    private List<D7SourceRef> sourceRefs;
}
