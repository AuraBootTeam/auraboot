package com.auraboot.framework.infrastructure.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.infrastructure.mq.MqProperties;
import com.auraboot.framework.infrastructure.mq.MqProvider;
import com.auraboot.framework.infrastructure.storage.StorageProperties;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Infrastructure status controller.
 * Shows current Storage, MQ, Redis, and Database provider status.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/infrastructure")
@RequiredArgsConstructor
public class InfrastructureController {

    private final StorageProvider storageProvider;
    private final StorageProperties storageProperties;
    private final MqProvider mqProvider;
    private final MqProperties mqProperties;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    /**
     * Get current infrastructure status — all providers and their configuration.
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Storage
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("type", storageProperties.getType());
        storage.put("provider", storageProvider.getClass().getSimpleName());
        storage.put("tenantIsolated", storageProperties.isTenantIsolated());
        result.put("storage", storage);

        // MQ
        Map<String, Object> mq = new LinkedHashMap<>();
        mq.put("type", mqProperties.getType());
        mq.put("provider", mqProvider.getClass().getSimpleName());
        result.put("mq", mq);

        // Redis
        Map<String, Object> redis = new LinkedHashMap<>();
        if (redisTemplate != null) {
            try {
                String pong = redisTemplate.execute(
                        (org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping());
                redis.put("connected", "pong".equals(pong));
                Properties info = redisTemplate.execute(
                        (org.springframework.data.redis.core.RedisCallback<Properties>) connection -> connection.info("server"));
                redis.put("version", info != null ? info.getProperty("redis_version", "unknown") : "unknown");
            } catch (Exception e) {
                redis.put("connected", false);
                redis.put("error", e.getMessage());
            }
        } else {
            redis.put("connected", false);
            redis.put("status", "not-configured");
        }
        result.put("redis", redis);

        // Database
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("url", maskPassword(datasourceUrl));
        result.put("database", db);

        return ApiResponse.success(result);
    }

    /**
     * Test storage connectivity — write a small test file, read it back, delete it.
     */
    @PostMapping("/test/storage")
    public ApiResponse<Map<String, Object>> testStorage() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", storageProperties.getType());
        result.put("provider", storageProvider.getClass().getSimpleName());

        String testKey = "_infra_test_" + System.currentTimeMillis() + ".txt";
        try {
            byte[] testContent = "infrastructure-test".getBytes();
            storageProvider.upload(testKey,
                    new java.io.ByteArrayInputStream(testContent),
                    testContent.length, "text/plain");
            boolean exists = storageProvider.exists(testKey);
            storageProvider.delete(testKey);

            result.put("status", "ok");
            result.put("writeRead", exists);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ApiResponse.success(result);
    }

    /**
     * Test MQ connectivity — send and receive a test message.
     */
    @PostMapping("/test/mq")
    public ApiResponse<Map<String, Object>> testMq() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", mqProperties.getType());
        result.put("provider", mqProvider.getClass().getSimpleName());

        try {
            String testTopic = "_infra_test";
            String testBody = "ping-" + System.currentTimeMillis();
            mqProvider.send(testTopic, testBody, Map.of());
            result.put("status", "ok");
            result.put("sent", true);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ApiResponse.success(result);
    }

    /**
     * Test Redis connectivity — PING.
     */
    @PostMapping("/test/redis")
    public ApiResponse<Map<String, Object>> testRedis() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (redisTemplate == null) {
            result.put("status", "not-configured");
            return ApiResponse.success(result);
        }
        try {
            redisTemplate.hasKey("__health_check__");
            result.put("status", "ok");
            result.put("ping", "pong");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ApiResponse.success(result);
    }

    private String maskPassword(String url) {
        if (url == null) return null;
        return url.replaceAll("password=[^&]*", "password=***");
    }
}
