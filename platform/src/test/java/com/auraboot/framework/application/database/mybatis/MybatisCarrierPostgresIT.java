package com.auraboot.framework.application.database.mybatis;

import static org.junit.jupiter.api.Assertions.*;

import com.auraboot.framework.application.tenant.MetaContext;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual PG, MyBatis-Plus, relocated OGNL and Spring transaction behavior on an owned database. */
@Tag("integration")
class MybatisCarrierPostgresIT {
    private ProbeMapper mapper;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach void prepare() throws Exception {
        MybatisCarrierIntegrity.verify(getClass().getClassLoader());
        String url = System.getenv("TEST_DATABASE_URL");
        com.auraboot.framework.test.util.OwnedTestDatabase.require(url,
                com.auraboot.framework.test.util.OwnedTestDatabase.AURABOOT_SLOT_PATTERN,
                "Carrier compatibility requires an explicitly owned test database");
        String user = System.getenv("TEST_DATABASE_USERNAME");
        String password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "");
        String schema = "ognl_carrier_" + UUID.randomUUID().toString().replace("-", "");
        var base = new UnpooledDataSource("org.postgresql.Driver", url, user, password);
        new JdbcTemplate(base).execute("CREATE SCHEMA " + schema);
        var source = new UnpooledDataSource("org.postgresql.Driver",
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE mt_carrier_compat_it (id bigint PRIMARY KEY, tenant_id bigint NOT NULL, name text, amount numeric(18,2))");
        jdbc.update("INSERT INTO mt_carrier_compat_it VALUES (1,101,'owned',10.50),(2,202,'foreign',99.00),(3,101,'nullable',NULL)");
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() { return new LongValue(MetaContext.getCurrentTenantId()); }
        }));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ProbeMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setPlugins(interceptor);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ProbeMapper.class);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        MetaContext.setContext(101L, 7L, null, "system");
        // Retain the tiny, uniquely named schema for inspection; never drop another run's data.
        System.out.println("Carrier PostgreSQL fixture retained: " + schema);
    }

    @AfterEach void clearActor() { MetaContext.clear(); }

    @Test void dynamicIfForeachAndTenantInterceptionPreserveActorIsolation() {
        assertEquals(List.of(1L, 3L), mapper.forIds(List.of(1L, 2L, 3L)).stream().map(Probe::getId).toList());
        assertEquals(List.of(1L, 3L), mapper.forIds(null).stream().map(Probe::getId).toList());
        assertNull(mapper.selectById(2L));
        MetaContext.setContext(202L, 8L, null, "system");
        assertEquals(List.of(2L), mapper.forIds(List.of(1L, 2L, 3L)).stream().map(Probe::getId).toList());
        assertNull(mapper.selectById(1L));
    }

    @Test void inheritedMapperWritesAndNullDecimalMappingRemainCompatible() {
        var created = new Probe();
        created.setId(4L); created.setTenantId(101L); created.setName("created"); created.setAmount(new BigDecimal("28.50"));
        assertEquals(1, mapper.insert(created));
        assertEquals(new BigDecimal("28.50"), mapper.selectById(4L).getAmount());
        assertNull(mapper.selectById(3L).getAmount());
        created.setName("updated");
        assertEquals(1, mapper.updateById(created));
        assertEquals("updated", mapper.selectById(4L).getName());
        assertEquals(1, mapper.deleteById(4L));
        assertNull(mapper.selectById(4L));
        assertEquals("foreign", jdbc.queryForObject("SELECT name FROM mt_carrier_compat_it WHERE id=2", String.class));
    }

    @Test void mapperFailureRollsBackThroughTheRealSpringTransaction() {
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            var created = new Probe();
            created.setId(9L); created.setTenantId(101L); created.setName("must-rollback");
            assertEquals(1, mapper.insert(created));
            assertEquals("must-rollback", mapper.selectById(9L).getName());
            throw new IllegalStateException("controlled failure after actual mapper write");
        }));
        assertNull(mapper.selectById(9L));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM mt_carrier_compat_it WHERE id=9", Integer.class));
        assertEquals("owned", mapper.selectById(1L).getName());
    }

    public interface ProbeMapper extends BaseMapper<Probe> {
        @Select({"<script>SELECT id,tenant_id,name,amount FROM mt_carrier_compat_it",
                "<where><if test='ids != null and !ids.isEmpty()'>id IN",
                "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
                "</if></where> ORDER BY id</script>"})
        List<Probe> forIds(@Param("ids") List<Long> ids);
    }

    @TableName("mt_carrier_compat_it")
    public static class Probe {
        @TableId private Long id;
        private Long tenantId;
        private String name;
        private BigDecimal amount;
        public Long getId() { return id; }
        public void setId(Long value) { id = value; }
        public Long getTenantId() { return tenantId; }
        public void setTenantId(Long value) { tenantId = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal value) { amount = value; }
    }
}
