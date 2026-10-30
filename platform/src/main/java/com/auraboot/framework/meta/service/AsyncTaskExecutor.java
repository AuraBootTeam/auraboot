package com.auraboot.framework.meta.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Interface for async task executors.
 * Each implementation handles a specific task type (EXPORT, BATCH_OP, etc.).
 *
 * <p>Implementations are auto-discovered via Spring and registered by their
 * {@link #getTaskType()} return value.</p>
 */
public interface AsyncTaskExecutor {

    /**
     * Unique task type this executor handles (e.g., "export", "batch_op").
     */
    String getTaskType();

    /**
     * Public submission is opt-in. SQL exports, command handlers and other host-created jobs
     * must use an authorized business entry point instead of accepting client execution identity.
     * An opted-in executor must authorize typed input against its persisted task owner.
     */
    default boolean supportsPublicSubmission() { return false; }

    /**
     * Execute the task.
     *
     * @param inputParams task input parameters from the submission
     * @param callback    progress callback for reporting execution progress
     * @return result of the execution
     */
    AsyncTaskResult execute(JsonNode inputParams, ProgressCallback callback);

    /**
     * Functional interface for reporting task progress.
     */
    @FunctionalInterface
    interface ProgressCallback {
        /**
         * Report progress.
         *
         * @param percentage completion percentage (0-100)
         * @param message    human-readable description of the current step
         */
        void report(int percentage, String message);
    }
}
