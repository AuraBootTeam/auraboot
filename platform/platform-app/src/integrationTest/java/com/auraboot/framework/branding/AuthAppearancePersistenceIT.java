package com.auraboot.framework.branding;

import com.auraboot.framework.branding.mapper.AuthAppearanceStateMapper;
import com.auraboot.framework.branding.mapper.AuthAppearanceRevisionMapper;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.ConflictException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

/** Production service and mapper round trips on an explicitly owned migrated PostgreSQL database. */
@SpringJUnitConfig(AuthAppearancePersistenceIT.Config.class)
class AuthAppearancePersistenceIT {
    @Autowired AuthAppearanceService service;
    @Autowired AuthAppearanceStateMapper states;
    @Autowired AuthAppearanceRevisionMapper revisions;
    @Autowired DataSourceTransactionManager transactions;
    private final ObjectMapper json = new ObjectMapper();
    private TransactionTemplate transaction;

    @BeforeEach void begin() {
        transaction = new TransactionTemplate(transactions);
        MetaContext.setContext(9L, 7L, "appearance-test-actor", "appearance-test");
    }
    @AfterEach void finish() { MetaContext.clear(); }

    @Test void savesPublishesAndRollsBackExactJsonbWithAuditAndCas() throws Exception {
        var first = json.readTree("""
            {"version":1,"template":"split","defaultLocale":"zh-CN",
             "content":{"headline":{"mode":"custom","values":{"zh-CN":"部署品牌","en-US":"Deployment brand"}}}}
            """);
        var second = json.readTree("{\"version\":1,\"template\":\"centered\",\"defaultLocale\":\"en-US\"}");
        transaction.executeWithoutResult(status -> {
            long start = service.view().version();
            service.save(start, first);
            assertThat(states.selectById(1L).getDraft()).isEqualTo(first);
            assertThat(service.view().publishedVersion()).isLessThan(start + 1);
            service.publish(start + 1);
            assertThat(service.published().appearance()).isEqualTo(first);
            assertThatThrownBy(() -> service.save(start, second)).isInstanceOf(ConflictException.class);
            service.save(start + 2, second);
            service.publish(start + 3);
            service.rollback(start + 4, start + 2);
            assertThat(states.lockState().getPublished()).isEqualTo(first);
            assertThat(service.view().draft()).isEqualTo(first);
            assertThat(revisions.selectById(start + 5).getAction()).isEqualTo("rollback");
            assertThat(revisions.selectById(start + 5).getActorId()).isEqualTo(7L);
            assertThat(revisions.selectById(start + 5).getSnapshot()).isEqualTo(first);
            status.setRollbackOnly();
        });
    }

    @Test void transactionRollbackRemovesStateAndAuditTogether() throws Exception {
        long start = service.view().version();
        var config = json.readTree("{\"version\":1,\"template\":\"centered\",\"defaultLocale\":\"en-US\"}");
        transaction.executeWithoutResult(status -> { service.save(start, config); status.setRollbackOnly(); });
        assertThat(service.view().version()).isEqualTo(start);
        assertThat(revisions.selectById(start + 1)).isNull();
    }

    @Test void concurrentWritersCannotOverwriteTheWinningRevision() throws Exception {
        long start = service.view().version();
        var config = json.readTree("{\"version\":1,\"template\":\"centered\",\"defaultLocale\":\"en-US\"}");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> write = () -> {
                MetaContext.setContext(9L, 7L, "appearance-test-actor", "appearance-test");
                try {
                    ready.countDown();
                    if (!go.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Writer start timed out");
                    service.save(start, config);
                    return true;
                } catch (ConflictException conflict) { return false; }
                finally { MetaContext.clear(); }
            };
            var left = workers.submit(write);
            var right = workers.submit(write);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(java.util.List.of(left.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    right.get(10, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(service.view().version()).isEqualTo(start + 1);
            assertThat(revisions.selectById(start + 1).getSnapshot()).isEqualTo(config);
            assertThat(revisions.selectById(start + 2)).isNull();
        }
    }

    @Test void explicitSeederInitializesSnapshotDatabaseOnlyOnce() {
        transaction.executeWithoutResult(status -> {
            states.deleteById(1L);
            var seed = new AuthAppearanceSeeder(states);
            seed.seed(); seed.seed();
            assertThat(states.selectById(1L).getVersion()).isZero();
            assertThat(states.selectById(1L).getPublishedVersion()).isZero();
            status.setRollbackOnly();
        });
    }

    @Test void refusesPublishingAnAbsentManagedAssetWithoutChangingTheRelease() throws Exception {
        var config = json.readTree("{\"version\":1,\"template\":\"centered\",\"defaultLocale\":\"en-US\",\"images\":{\"mode\":\"none\",\"logoUrl\":\"/api/auth/appearance/assets/" + "a".repeat(64) + ".png\"}}");
        transaction.executeWithoutResult(status -> {
            long start = service.view().version();
            long published = service.view().publishedVersion();
            service.save(start, config);
            assertThatThrownBy(() -> service.publish(start + 1)).hasMessageContaining("assetMissing");
            assertThat(service.view().version()).isEqualTo(start + 1);
            assertThat(service.view().publishedVersion()).isEqualTo(published);
            assertThat(revisions.selectById(start + 2)).isNull();
            status.setRollbackOnly();
        });
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = AuthAppearanceStateMapper.class, annotationClass = Mapper.class)
    static class Config {
        @Bean DataSource dataSource() {
            String url = System.getenv("AUTH_APPEARANCE_IT_DB");
            if (url == null || !url.matches("jdbc:postgresql://[^/]+/auth_appearance_schema_[a-z0-9_]+")) {
                throw new IllegalStateException("AUTH_APPEARANCE_IT_DB must identify an owned auth_appearance_schema database");
            }
            return new DriverManagerDataSource(url, System.getenv("AUTH_APPEARANCE_IT_USER"),
                    System.getenv().getOrDefault("AUTH_APPEARANCE_IT_PASSWORD", ""));
        }
        @Bean org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource source, org.springframework.context.ApplicationContext context) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setPlugins(new com.auraboot.framework.application.database.mybatis.MybatisPlusConfig()
                    .mybatisPlusInterceptor(new com.auraboot.framework.application.database.dialect.PostgresDialect(),
                            context, new MockEnvironment()));
            return factory.getObject();
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean DeploymentBrandingProvider brandingProvider() throws Exception {
            Path file = Files.createTempFile("appearance-it-branding", ".json");
            file.toFile().deleteOnExit();
            Files.writeString(file, """
                {"schemaVersion":1,"orderReference":"SO-2026-001","productName":"Northstar",
                 "platformName":"Northstar Operations Platform","logoUrl":"/brand/logo.png",
                 "faviconUrl":"/brand/icon.ico","favicon32Url":"/brand/icon.png",
                 "appleTouchIconUrl":"/brand/apple.png","manifestUrl":"/brand/manifest.webmanifest",
                 "websiteUrl":"https://northstar.example.com","docsUrl":"https://northstar.example.com/docs",
                 "supportUrl":"https://northstar.example.com/support","copyrightHolder":"Northstar",
                 "poweredByText":"Powered by Northstar","generatedByText":"Generated by Northstar"}
                """);
            return new DeploymentBrandingProvider(new MockEnvironment().withProperty("EDITION", "standard")
                    .withProperty("AURABOOT_BRANDING_CONFIG_PATH", file.toString())
                    .withProperty("AURABOOT_WHITE_LABEL_ORDER_REFERENCE", "SO-2026-001"), new ObjectMapper());
        }
        @Bean AuthAppearanceAssetService assets(DeploymentBrandingProvider branding) throws Exception {
            Path root = Files.createTempDirectory("appearance-it-assets");
            root.toFile().deleteOnExit();
            return new AuthAppearanceAssetService(new MockEnvironment().withProperty(
                    "AURABOOT_AUTH_APPEARANCE_ASSET_ROOT", root.toString()), branding);
        }
        @Bean AuthAppearanceService service(AuthAppearanceStateMapper states, AuthAppearanceRevisionMapper revisions,
                DeploymentBrandingProvider branding, AuthAppearanceAssetService assets) { return new AuthAppearanceService(states, revisions, branding, assets); }
    }
}
