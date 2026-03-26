package com.auraboot.framework.application.database.snowflake;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花ID生成器配置
 */
@Component
public class SnowflakeIdGeneratorConfig implements IdentifierGenerator {
    
    @Value("${snowflake.worker-id:1}")
    private long workerId;
    
    @Value("${snowflake.datacenter-id:1}")
    private long datacenterId;
    
    private final SnowflakeIdWorker snowflakeIdWorker;
    
    public SnowflakeIdGeneratorConfig() {
        // 默认值，会在@PostConstruct中重新初始化
        this.snowflakeIdWorker = new SnowflakeIdWorker(1, 1);
    }
    
    @jakarta.annotation.PostConstruct
    public void init() {
        // 使用配置的workerId和datacenterId重新初始化
        this.snowflakeIdWorker.setWorkerId(workerId);
        this.snowflakeIdWorker.setDatacenterId(datacenterId);
    }
    
    @Override
    public Long nextId(Object entity) {
        return snowflakeIdWorker.nextId();
    }
}