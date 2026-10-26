package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.io.IOException;

/** Lazy host dependency avoids a queue-executor-service construction cycle. */
@Component
@Slf4j
@RequiredArgsConstructor
public class DomainExportAsyncTaskExecutor implements AsyncTaskExecutor {
    private final ObjectProvider<DomainExportTaskService> exports;
    @Override public String getTaskType(){return DomainExportTaskService.TASK_TYPE;}
    @Override public AsyncTaskResult execute(JsonNode input,ProgressCallback progress) {
        try {return exports.getObject().execute(input,progress);}
        // Top-level worker boundary: retain diagnostics privately and persist only a stable public failure key.
        catch(IOException | RuntimeException failure) {
            log.error("Domain export execution failed",failure);
            return AsyncTaskFailureClassifier.isRetryable(failure) ? AsyncTaskResult.retryableFailure("$i18n:export.domain_failed")
                : AsyncTaskResult.nonRetryableFailure("$i18n:export.domain_failed");
        }
    }
}
