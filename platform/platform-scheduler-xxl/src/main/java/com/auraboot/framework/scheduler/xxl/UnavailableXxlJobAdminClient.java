package com.auraboot.framework.scheduler.xxl;

import com.auraboot.framework.exception.BusinessException;

public class UnavailableXxlJobAdminClient implements XxlJobAdminClient {

    private static final String MESSAGE = "XXL-JOB Admin client is not configured. "
            + "Provide an XxlJobAdminClient bean before using aura.scheduler.engine=xxl.";

    @Override
    public XxlJobAdminResponse upsertJob(XxlJobAdminRequest request) {
        throw unavailable();
    }

    @Override
    public void disableJob(String taskPid) {
        throw unavailable();
    }

    @Override
    public void deleteJob(String taskPid) {
        throw unavailable();
    }

    @Override
    public void triggerJob(String taskPid, String executorPayload) {
        throw unavailable();
    }

    private BusinessException unavailable() {
        return new BusinessException(MESSAGE);
    }
}
