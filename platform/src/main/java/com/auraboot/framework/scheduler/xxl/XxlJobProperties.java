package com.auraboot.framework.scheduler.xxl;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "aura.scheduler.xxl")
public class XxlJobProperties {

    private String adminAddresses = "";
    private String adminUsername = "admin";
    private String adminPassword = "123456";
    private String accessToken = "";
    private String executorAppName = "auraboot-platform";
    private String executorAddress = "";
    private String executorIp = "";
    private int executorPort = 9999;
    private String logPath = "./data/xxl-job/logs";
    private int logRetentionDays = 30;
    private int connectTimeoutMillis = 5000;
    private int readTimeoutMillis = 10000;
}
