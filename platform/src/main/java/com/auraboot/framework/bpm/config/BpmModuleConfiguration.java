package com.auraboot.framework.bpm.config;

import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * BPM模块配置类
 * 负责BPM模块的初始化和组件扫描
 *
 * @author AuraBoot Team
 */
@Slf4j
@Configuration
@ComponentScan(basePackages = "com.auraboot.framework.bpm")
@RequiredArgsConstructor
public class BpmModuleConfiguration {

    private final SmartEngine smartEngine;
    private final BpmAuditService bpmAuditService;

    /**
     * 应用启动完成后的初始化操作
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("BPM Module initializing...");

        // 验证SmartEngine是否正确初始化
        if (smartEngine != null) {
            log.info("SmartEngine integration verified successfully");
        } else {
            log.error("SmartEngine integration failed - SmartEngine is null");
        }

        // 验证审计服务
        if (bpmAuditService != null) {
            log.info("BPM Audit Service initialized successfully");
        } else {
            log.error("BPM Audit Service initialization failed");
        }

    }
}