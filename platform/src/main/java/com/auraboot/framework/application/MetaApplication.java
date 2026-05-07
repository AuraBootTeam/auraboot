package com.auraboot.framework.application;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(
    scanBasePackages = {"com.auraboot.framework", "com.auraboot.module"},
    exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class}
)
@MapperScan({"com.auraboot.framework.*.mapper", "com.auraboot.framework.*.dao.mapper", "com.auraboot.framework.*.dao", "com.auraboot.framework.agent.trace.mapper", "com.auraboot.framework.bpm.connector", "com.auraboot.framework.plugin.marketplace.mapper", "com.auraboot.framework.saas.config.mapper", "com.auraboot.framework.saas.account.mapper", "com.auraboot.framework.saas.license.mapper", "com.auraboot.framework.saas.bootstrap.mapper", "com.auraboot.framework.promotion.reference.dao.mapper", "com.auraboot.module.*.mapper", "com.auraboot.module.meta.excel.mapper", "com.auraboot.smart.framework.engine.persister.database"})
@EnableTransactionManagement
@EnableScheduling
public class MetaApplication {

	//todo profile 设置,docker 集成,https
	public static void main(String[] args) {
		SpringApplication.run(MetaApplication.class, args);
	}

}
