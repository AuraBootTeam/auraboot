package com.auraboot.framework.devpipeline.importer;

public interface PipelineImportService {
    PipelineImportResult importFromPacket(PipelineImportRequest request);

    PipelineImportPreview previewPacket(PipelineImportRequest request);
}
