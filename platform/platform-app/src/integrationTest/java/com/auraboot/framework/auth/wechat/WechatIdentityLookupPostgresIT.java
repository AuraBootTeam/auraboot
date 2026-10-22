package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL/MyBatis query coverage; no simulated mapper query results. */
class WechatIdentityLookupPostgresIT {
    private static SqlSessionFactory sessions;
    private static final String MINI = "wx-mini-trusted";
    private static final String WEB = "wx-web-trusted";

    @BeforeAll
    static void initializeDatabase() throws Exception {
        String url = System.getenv("AURA_WECHAT_IDENTITY_TEST_DATABASE_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/aura_boot_wechat_identity(?:\\?.*)?")) {
            throw new IllegalStateException("Explicit isolated aura_boot_wechat_identity PostgreSQL URL required");
        }
        String user = System.getenv().getOrDefault("AURA_WECHAT_IDENTITY_TEST_DATABASE_USER", "ghj");
        String password = System.getenv().getOrDefault("AURA_WECHAT_IDENTITY_TEST_DATABASE_PASSWORD", "");
        String schema = "identity_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
            connection.createStatement().execute("CREATE TABLE " + schema + ".ab_auth_identity ("
                    + "id bigint PRIMARY KEY,pid varchar(64),user_id bigint,provider varchar(64),"
                    + "app_id varchar(64),openid varchar(128),unionid varchar(128),"
                    + "last_login_at timestamptz,created_at timestamptz,updated_at timestamptz)");
        }
        String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var datasource = new PooledDataSource("org.postgresql.Driver", schemaUrl, user, password);
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("wechat-identity-it", new JdbcTransactionFactory(), datasource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AuthIdentityMapper.class);
        sessions = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void websiteFirstMiniLoginFindsWebsiteAccount() {
        try (SqlSession session = sessions.openSession(true)) {
            AuthIdentityMapper mapper = session.getMapper(AuthIdentityMapper.class);
            String union = unique();
            insert(mapper, 101L, "wechat_web", WEB, "web-" + union, union);
            var result = resolve(mapper, "wechat_mini", MINI, "mini-" + union, union);
            assertThat(result.identity().getUserId()).isEqualTo(101L);
            assertThat(result.openidMatched()).isFalse();
        }
    }

    @Test
    void miniFirstWebsiteLoginFindsMiniAccount() {
        try (SqlSession session = sessions.openSession(true)) {
            AuthIdentityMapper mapper = session.getMapper(AuthIdentityMapper.class);
            String union = unique();
            insert(mapper, 102L, "wechat_mini", MINI, "mini-" + union, union);
            assertThat(resolve(mapper, "wechat_web", WEB, "web-" + union, union)
                    .identity().getUserId()).isEqualTo(102L);
        }
    }

    @Test
    void existingSplitIdentityRejectsBothLoginDirectionsIncludingMissingClaim() {
        try (SqlSession session = sessions.openSession(true)) {
            AuthIdentityMapper mapper = session.getMapper(AuthIdentityMapper.class);
            String union = unique();
            insert(mapper, 103L, "wechat_web", WEB, "web-" + union, union);
            insert(mapper, 104L, "wechat_mini", MINI, "mini-" + union, union);
            assertThatThrownBy(() -> resolve(mapper, "wechat_mini", MINI, "mini-" + union, union))
                    .isInstanceOf(RootUnCheckedException.class).hasMessageContaining("identity conflict");
            assertThatThrownBy(() -> resolve(mapper, "wechat_web", WEB, "web-" + union, union))
                    .isInstanceOf(RootUnCheckedException.class);
            assertThatThrownBy(() -> resolve(mapper, "wechat_mini", MINI, "mini-" + union, null))
                    .isInstanceOf(RootUnCheckedException.class);
        }
    }

    @Test
    void controlledCorrectionResolvesOriginalOwnerWithoutResidualSplit() {
        try (SqlSession session = sessions.openSession(true)) {
            AuthIdentityMapper mapper = session.getMapper(AuthIdentityMapper.class);
            String union = unique();
            insert(mapper, 105L, "wechat_web", WEB, "web-" + union, union);
            AuthIdentity duplicate = insert(mapper, 106L, "wechat_mini", MINI, "mini-" + union, union);
            assertThatThrownBy(() -> resolve(mapper, "wechat_mini", MINI, "mini-" + union, union))
                    .isInstanceOf(RootUnCheckedException.class);
            duplicate.setUserId(105L);
            mapper.updateById(duplicate);
            assertThat(resolve(mapper, "wechat_mini", MINI, "mini-" + union, union)
                    .identity().getUserId()).isEqualTo(105L);
        }
    }

    @Test
    void unconfiguredApplicationsAndOtherProvidersCannotClaimAccount() {
        try (SqlSession session = sessions.openSession(true)) {
            AuthIdentityMapper mapper = session.getMapper(AuthIdentityMapper.class);
            String union = unique();
            insert(mapper, 107L, "wechat_web", "untrusted-web-app", "web-" + union, union);
            insert(mapper, 108L, "wechat_official", WEB, "official-" + union, union);
            insert(mapper, 109L, "wechat_mini", "other-mini", "mini-" + union, union);
            assertThat(resolve(mapper, "wechat_mini", MINI, "mini-" + union, union).identity()).isNull();
        }
    }

    @Test
    void changedUnionClaimDoesNotReplaceOpenidOwner() {
        try (SqlSession session = sessions.openSession(true)) {
            AuthIdentityMapper mapper = session.getMapper(AuthIdentityMapper.class);
            String union = unique();
            insert(mapper, 110L, "wechat_mini", MINI, "mini-" + union, union);
            assertThatThrownBy(() -> resolve(mapper, "wechat_mini", MINI, "mini-" + union, "changed-" + union))
                    .isInstanceOf(RootUnCheckedException.class);
        }
    }

    @Test
    void openidWithoutUnionStillResolvesOnlyConfiguredApp() {
        try (SqlSession session = sessions.openSession(true)) {
            AuthIdentityMapper mapper = session.getMapper(AuthIdentityMapper.class);
            String openid = unique();
            insert(mapper, 111L, "wechat_mini", "other-mini", openid, null);
            insert(mapper, 112L, "wechat_mini", MINI, openid, null);
            var result = resolve(mapper, "wechat_mini", MINI, openid, null);
            assertThat(result.identity().getUserId()).isEqualTo(112L);
            assertThat(result.openidMatched()).isTrue();
        }
    }

    @Test
    void concurrentChannelProvisioningWaitsForCommittedOwner() throws Exception {
        String union = unique();
        try (SqlSession first = sessions.openSession(false);
             var executor = Executors.newSingleThreadExecutor()) {
            AuthIdentityMapper mapper = first.getMapper(AuthIdentityMapper.class);
            assertThat(resolve(mapper, "wechat_web", WEB, "web-" + union, union).identity()).isNull();
            insert(mapper, 113L, "wechat_web", WEB, "web-" + union, union);
            CountDownLatch started = new CountDownLatch(1);
            var second = executor.submit(() -> {
                try (SqlSession follower = sessions.openSession(false)) {
                    started.countDown();
                    var identity = resolve(follower.getMapper(AuthIdentityMapper.class),
                            "wechat_mini", MINI, "mini-" + union, union).identity();
                    follower.commit();
                    return identity;
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                first.commit();
            }
            assertThat(second.get(5, TimeUnit.SECONDS).getUserId()).isEqualTo(113L);
        }
    }

    private static WechatIdentityLookup.Resolution resolve(AuthIdentityMapper mapper, String provider,
                                                          String appId, String openid, String unionid) {
        return WechatIdentityLookup.resolve(mapper, provider, appId, openid, unionid, MINI, WEB);
    }

    private static AuthIdentity insert(AuthIdentityMapper mapper, Long user, String provider,
                                       String app, String openid, String union) {
        AuthIdentity identity = new AuthIdentity();
        identity.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        identity.setPid(unique());
        identity.setUserId(user);
        identity.setProvider(provider);
        identity.setAppId(app);
        identity.setOpenid(openid);
        identity.setUnionid(union);
        mapper.insert(identity);
        return identity;
    }

    private static String unique() { return UUID.randomUUID().toString(); }
}
