package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ValidationContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.user.service.UserService;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Actual service/provider/MyBatis SQL against transaction-local PostgreSQL fixtures. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named="AURA_PROCUREMENT_REFERENCE_IT",matches="1")
class ReferenceIdentityPostgresIT {
    private JdbcTemplate jdbc;private TransactionTemplate transaction;private ValidationServiceImpl service;
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("TEST_DATABASE_URL");assertThat(url).matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):5432/enterprise_22(?:\\?.*)?");
        var source=new UnpooledDataSource("org.postgresql.Driver",url,System.getenv("TEST_DATABASE_USERNAME"),System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc=new JdbcTemplate(source);assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo("enterprise_22");
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        var configuration=new org.apache.ibatis.session.Configuration();configuration.addMapper(DynamicDataMapper.class);factory.setConfiguration(configuration);
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(DynamicDataMapper.class);
        service=new ValidationServiceImpl(mapper,mock(UserService.class),mock(MetaModelService.class));
        MetaContext.setContext(1L,7L,null,"system");
    }
    @AfterEach void clear(){MetaContext.clear();}
    private void fixture(Runnable assertions){transaction.executeWithoutResult(status -> {
        jdbc.execute("CREATE TEMP TABLE procurement_ref_identity(pid text PRIMARY KEY,tenant_id bigint NOT NULL,display_sequence integer,business_code text) ON COMMIT DROP");
        jdbc.update("INSERT INTO procurement_ref_identity VALUES ('PID-A',1,1,'BIZ-A'),('PID-B',2,2,'BIZ-B')");
        assertions.run();assertThat(jdbc.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
    });}
    private FieldDefinition field(String identity,String display){
        var field=new FieldDefinition();field.setCode("reference_identity");field.setName("Reference identity");field.setDataType("reference");
        var target=new FieldDefinition.RefTarget();target.setTargetEntity("submission");target.setTargetTable("procurement_ref_identity");target.setValueField(identity);target.setTargetField(display);field.setRefTarget(target);return field;
    }
    @Test void pidWithNumericDisplayValidatesWithoutAbortingTheTransaction(){fixture(() -> {
        var field=field("pid","display_sequence");
        assertThat(service.validateField(field,"PID-A",ValidationContext.CREATE).isValid()).isTrue();
        for(String invalid:new String[]{"1","BIZ-A","PID-B","MISSING"})
            assertThat(service.validateField(field,invalid,ValidationContext.CREATE).isValid()).as(invalid).isFalse();
    });}
    @Test void explicitBusinessIdentityRejectsPidAndDisplayAlternatives(){fixture(() -> {
        var field=field("business_code","display_sequence");
        assertThat(service.validateField(field,"BIZ-A",ValidationContext.CREATE).isValid()).isTrue();
        for(String invalid:new String[]{"PID-A","1","BIZ-B"})
            assertThat(service.validateField(field,invalid,ValidationContext.CREATE).isValid()).as(invalid).isFalse();
    });}
    @Test void undeclaredLegacyIdentityRetainsPidAndBusinessKeyCompatibility(){fixture(() -> {
        var field=field(null,"business_code");
        assertThat(service.validateField(field,"BIZ-A",ValidationContext.CREATE).isValid()).isTrue();
        assertThat(service.validateField(field,"PID-A",ValidationContext.CREATE).isValid()).isTrue();
        assertThat(service.validateField(field,"PID-B",ValidationContext.CREATE).isValid()).isFalse();
    });}
}
