package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

/** Public source descriptions are explicitly allowlisted by the authorized domain adapter. */
@Getter
@Setter
public class DomainExportTaskDTO extends ExportTaskDTO {
    private String title;
    private String fileName;
    private JsonNode sourceSnapshot;
}
