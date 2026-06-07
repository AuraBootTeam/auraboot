package com.auraboot.framework.scheduler.xxl;

public interface XxlJobAdminClient {

    XxlJobAdminResponse upsertJob(XxlJobAdminRequest request);

    void disableJob(String taskPid);

    void deleteJob(String taskPid);

    void triggerJob(String taskPid, String executorPayload);
}
