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
@MapperScan({
        // Covers all com.auraboot.framework.**.<anything>.mapper paths recursively,
        // including previously hand-enumerated nested packages such as
        // connector.jdbc.mapper, connector.saas.oauth.mapper, connector.airflow.mapper,
        // connector.airflow.secret.mapper, aurabot.skill.mapper, agent.trace.mapper,
        // chatbi.v2.mapper, plugin.marketplace.mapper, billing.*.mapper,
        // dataquality.ge.mapper, promotion.reference.dao.mapper, saas.*.mapper, etc.
        "com.auraboot.framework.**.mapper",
        // Leaf is 'dao', not 'mapper' — not covered by **.mapper above.
        "com.auraboot.framework.*.dao",
        // Retained for safety: leaf is 'connector', not 'mapper'.
        "com.auraboot.framework.bpm.connector",
        // Non-framework packages — must be listed explicitly.
        "com.auraboot.module.*.mapper",
        "com.auraboot.module.meta.excel.mapper",
        "com.auraboot.smart.framework.engine.persister.database"})
@EnableTransactionManagement
@EnableScheduling
public class MetaApplication {

	//todo profile 设置,docker 集成,https
	public static void main(String[] args) {
		SpringApplication.run(MetaApplication.class, args);
	}

}
