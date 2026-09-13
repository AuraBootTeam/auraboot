package com.auraboot.framework.application;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Platform-only runtime composition used by the application artifact contract.
 *
 * <p>The compatibility {@link MetaApplication} still composes the historical
 * monolith while CRM and BPM are being extracted. This source deliberately
 * excludes both product implementations so a core-only release cannot hide a
 * missing product migration behind startup repair or scheduler exceptions.</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
    RedisAutoConfiguration.class,
    RedisRepositoriesAutoConfiguration.class
})
@ComponentScan(
    basePackages = {"com.auraboot.framework", "com.auraboot.module"},
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = MetaApplication.class),
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.auraboot\\.framework\\.(bpm|crm)(\\..*)?"
        ),
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = {
                "com\\.auraboot\\.framework\\.automation\\.bpm(\\..*)?"
            }
        )
    }
)
@MapperScan({
    "com.auraboot.framework.**.mapper",
    "com.auraboot.framework.*.dao",
    "com.auraboot.module.*.mapper",
    "com.auraboot.module.meta.excel.mapper"
})
@EnableTransactionManagement
@EnableScheduling
public class CoreOnlyApplication {
}
