package com.auraboot.framework.integration.plugin;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TenantAdministrationFixture;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real plugin import transactions: first-install seeds and preservation of tenant-owned roles. */
@SpringBootTest(classes=TestApplication.class, properties="spring.data.redis.host=")
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class PluginRoleModelPermissionReconciliationIT extends BaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PluginImportService imports;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.auraboot.framework.plugin.service.impl.PluginResourceImporterImpl resourceImporter;
    String namespace, model, roleCode, rolePid;
    Path directory;
    Long roleId;
    TenantAdministrationFixture administrator;

    @BeforeEach void fixture() throws Exception {
        namespace="rup"+UniqueIdGenerator.generate().substring(18).toLowerCase(Locale.ROOT);
        model=namespace+"_widget";roleCode=namespace+"_reader";
        directory=Files.createTempDirectory("role-upgrade-");
        administrator=TenantAdministrationFixture.grant(jdbc,getTestTenant().getId(),getTestTenantMember().getId());
        for(String code:List.of(MetaPermission.ROLE_MANAGE,MetaPermission.PERMISSION_MANAGE,MetaPermission.USER_ROLE_MANAGE))
            grantCommittedPermissionToTestRole(code,"API","role","update","Role upgrade fixture");
        write("plugin.json","""
            {"pluginId":"com.auraboot.%s","namespace":"%s","version":"1.0.0","dslVersion":1,
             "pluginType":"config","displayName":"Role Upgrade Fixture","minPlatformVersion":"1.0.0",
             "dependencies":[],"provides":[],"requires":[],"resourceDirs":{
               "models":"config/models.json","fields":"config/fields","modelFieldBindings":"config/bindings",
               "commands":"config/commands","permissions":"config/permissions.json","roles":"config/roles.json"}}
            """.formatted(namespace,namespace));
        write("config/models.json","""
            [{"code":"%s","displayName:en":"Widget","modelType":"entity","modelCategory":"entity"}]
            """.formatted(model));
        write("config/fields/widget.json","""
            [{"code":"%s_name","displayName:en":"Widget name","dataType":"string","constraints":{"maxLength":100}}]
            """.formatted(model));
        write("config/bindings/widget.json","""
            [{"modelCode":"%s","fieldCode":"%s_name","sequence":1,"visible":true,"editable":true}]
            """.formatted(model,model));
        write("config/commands/widget.json","""
            [{"code":"%s:archive_widget","displayName:en":"Archive","type":"state_transition","modelCode":"%s"}]
            """.formatted(namespace,model));
        write("config/permissions.json","""
            [{"code":"model.%s.read","name:en":"Read widgets","resourceType":"model","resourceCode":"%s","action":"read"}]
            """.formatted(model,model));
        template(false,false);
    }

    void template(boolean upgrade, boolean broken) throws Exception {
        write("config/roles.json","""
            [{"code":"%s","name:en":"%s","defaultDataScopeType":"%s",
              "permissions":["model.%s.read","model.%s.archive"%s],
              "seedMembers":["CURRENT_MEMBER"],
              "permissionPolicies":[{"permissionCode":"model.%s.read","conditions":{"limit":%d}}],
              "dataScopes":[{"permissionCode":"model.%s.read","scopeType":"%s","mergeStrategy":"MIN"}]}]
            """.formatted(roleCode,upgrade?"Updated template":"Widget reader",upgrade?"all":"self",model,model,
                broken?",\"missing.upgrade.permission\"":upgrade?",\"model."+model+".delete\"":"",
                model,upgrade?999:7,model,upgrade?"all":"self"));
    }

    ImportExecuteResult execute() {
        var preview=imports.parseDirectory(directory.toString());
        assertThat(preview.isValid()).as("Fixture parse: %s",preview.getErrors()).isTrue();
        return imports.execute(preview.getImportId(),ImportRequest.builder().importId(preview.getImportId())
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE).autoPublishModels(true)
                .autoPublishFields(true).autoPublishCommands(true).autoPublishPages(true).build());
    }

    void install() {
        var result=execute();assertThat(result.isSuccess()).as("Import: %s",result.getErrorMessage()).isTrue();
        var row=jdbc.queryForMap("SELECT id,pid FROM ab_role WHERE tenant_id=? AND code=? AND deleted_flag=FALSE",getTestTenant().getId(),roleCode);
        roleId=((Number)row.get("id")).longValue();rolePid=(String)row.get("pid");
    }

    List<String> codes() {
        return jdbc.queryForList("SELECT p.code FROM ab_role_permission rp JOIN ab_permission p ON p.id=rp.permission_id WHERE rp.tenant_id=? AND rp.role_id=? ORDER BY p.code",String.class,getTestTenant().getId(),roleId);
    }

    Map<String,Object> snapshot() {
        Long tenant=getTestTenant().getId();var result=new TreeMap<String,Object>();
        result.put("role",jdbc.queryForList("SELECT row_to_json(r)::text FROM ab_role r WHERE tenant_id=? AND id=?",tenant,roleId));
        for(String table:List.of("ab_role_permission","ab_role_data_scope","ab_user_role","ab_role_navigation_state","ab_role_navigation_menu"))
            result.put(table,jdbc.queryForList("SELECT row_to_json(x)::text AS data FROM "+table+" x WHERE tenant_id=? AND role_id=? ORDER BY row_to_json(x)::text",tenant,roleId));
        result.put("fieldBindings",jdbc.queryForList("SELECT row_to_json(x)::text FROM ab_data_permission_role_binding x WHERE tenant_id=? AND role_pid=? ORDER BY id",tenant,rolePid));
        result.put("fieldPolicies",jdbc.queryForList("SELECT row_to_json(x)::text FROM ab_data_permission_policy x WHERE tenant_id=? AND managed_role_pid=? ORDER BY id",tenant,rolePid));
        result.put("audit",jdbc.queryForList("SELECT row_to_json(x)::text FROM ab_role_navigation_audit x WHERE tenant_id=? AND role_pid=? ORDER BY pid",tenant,rolePid));
        return result;
    }

    @Test void firstInstallCompletesGeneratedCommandActionsAndExplicitScopes() {
        install();
        assertThat(codes()).contains("model."+model+".read","model."+model+".archive");
        assertThat(jdbc.queryForObject("SELECT scope_type FROM ab_role_data_scope WHERE tenant_id=? AND role_id=? AND action_code='read'",String.class,getTestTenant().getId(),roleId)).isEqualTo("self");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_user_role WHERE tenant_id=? AND role_id=? AND member_id=?",Integer.class,getTestTenant().getId(),roleId,getTestTenantMember().getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_navigation_state WHERE tenant_id=? AND role_id=?",Integer.class,getTestTenant().getId(),roleId)).isEqualTo(1);
    }

    @Test void repeatedImportPreservesAllPhysicalRoleConfiguration() {
        install();var before=snapshot();
        assertThat(execute().isSuccess()).isTrue();assertThat(snapshot()).isEqualTo(before);
    }

    @Test void upgradePreservesNullDefaultRestrictionsFieldsMenusAndExpiredMembership() throws Exception {
        install();Long tenant=getTestTenant().getId();
        jdbc.update("UPDATE ab_role SET default_data_scope_type=NULL,name='Tenant-owned role' WHERE tenant_id=? AND id=?",tenant,roleId);
        jdbc.update("UPDATE ab_role_permission SET grant_type='deny',priority=39,effective_date=CURRENT_DATE-7,expiry_date=CURRENT_DATE-1,field_scope='{\"readFields\":[\"name\"]}'::jsonb,source_ref='tenant-owned' WHERE tenant_id=? AND role_id=?",tenant,roleId);
        jdbc.update("UPDATE ab_user_role SET expiry_date=CURRENT_DATE-1 WHERE tenant_id=? AND role_id=?",tenant,roleId);
        String policy=UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_data_permission_policy(id,pid,tenant_id,name,model_code,policy_type,field_code,mask_type,managed_role_pid) VALUES(?,?,?,?,?,'COLUMN',?,'HIDE',?)",IdWorker.getId(),policy,tenant,"Role-owned hidden name",model,model+"_name",rolePid);
        jdbc.update("INSERT INTO ab_data_permission_role_binding(tenant_id,policy_pid,role_pid) VALUES(?,?,?)",tenant,policy,rolePid);
        jdbc.update("INSERT INTO ab_role_navigation_menu(tenant_id,role_id,menu_pid) VALUES(?,?,?)",tenant,roleId,UniqueIdGenerator.generate());
        var before=snapshot();template(true,false);
        assertThat(execute().isSuccess()).isTrue();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(codes()).doesNotContain("model."+model+".delete");
        assertThat(imports.reconcileDirectoryRolePermissions(directory.toString())).isEqualTo(1);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void anExplicitEmptyLegacyRoleDoesNotRegainTemplateGrants() throws Exception {
        install();Long tenant=getTestTenant().getId();
        jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=?",tenant,roleId);
        jdbc.update("DELETE FROM ab_role_data_scope WHERE tenant_id=? AND role_id=?",tenant,roleId);
        jdbc.update("UPDATE ab_role SET default_data_scope_type=NULL WHERE tenant_id=? AND id=?",tenant,roleId);
        var before=snapshot();template(true,false);
        assertThat(execute().isSuccess()).isTrue();
        assertThat(snapshot()).isEqualTo(before);assertThat(codes()).isEmpty();
    }

    @Test void unresolvedUpgradeDeclarationIsRejectedBeforeExistingRoleWrites() throws Exception {
        install();var before=snapshot();template(true,true);
        var preview=imports.parseDirectory(directory.toString());
        assertThat(preview.isValid()).isFalse();assertThat(preview.getErrors().toString()).contains("missing.upgrade.permission");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void unresolvedFirstInstallIsRejectedBeforeRoleCreation() throws Exception {
        template(false,true);assertThat(imports.parseDirectory(directory.toString()).isValid()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role WHERE tenant_id=? AND code=?",Integer.class,getTestTenant().getId(),roleCode)).isZero();
    }

    @Test void failureAfterRealInitialGrantsRollsBackTheNewRoleAndBindings() {
        long[] insertedRole={0};
        org.mockito.Mockito.doAnswer(call -> {
            call.callRealMethod();
            long id=jdbc.queryForObject("SELECT id FROM ab_role WHERE tenant_id=? AND code=?",Long.class,getTestTenant().getId(),roleCode);
            insertedRole[0]=id;
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE tenant_id=? AND role_id=?",Integer.class,getTestTenant().getId(),id)).isGreaterThan(0);
            throw new com.auraboot.framework.plugin.exception.PluginException("Injected fault after actual role initialization");
        }).when(resourceImporter).reconcileImportedRolePermissions(
                org.mockito.ArgumentMatchers.argThat(role -> roleCode.equals(role.getCode())),
                org.mockito.ArgumentMatchers.eq(getTestTenant().getId()),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        assertThatThrownBy(this::execute).isInstanceOf(com.auraboot.framework.plugin.exception.PluginException.class)
                .hasStackTraceContaining("Injected fault after actual role initialization");
        assertThat(insertedRole[0]).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role WHERE tenant_id=? AND code=?",Integer.class,getTestTenant().getId(),roleCode)).isZero();
        for(String table:List.of("ab_role_permission","ab_role_data_scope","ab_user_role","ab_role_navigation_state"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND role_id=?",Integer.class,getTestTenant().getId(),insertedRole[0])).isZero();
    }

    void write(String path,String value) throws Exception {
        Path target=directory.resolve(path);Files.createDirectories(target.getParent());Files.writeString(target,value);
    }

    @AfterEach void restoreAdministrator() {
        org.mockito.Mockito.reset(resourceImporter);
        if(administrator!=null)administrator.close();
    }
}
