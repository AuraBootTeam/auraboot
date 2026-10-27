package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.meta.service.impl.pipeline.phases.*;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.*;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG result-cache and result-record READ revocation; no mocked cache or permission verdicts. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
class QueryResultCacheRevocationIT {
    @Autowired IdempotencyService store;
    @Autowired CompletionPhase completion;
    @Autowired DynamicDataService data;
    @Autowired MetaModelService models;
    @Autowired SchemaManagementService schema;
    @Autowired UserService users;
    @Autowired TenantService tenants;
    @Autowired TenantMemberService members;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @Autowired com.auraboot.framework.permission.service.PermissionService permissions;
    @Autowired com.auraboot.framework.permission.service.UserPermissionService userPermissions;
    @Autowired com.auraboot.framework.rbac.mapper.RolePermissionMapper rolePermissions;
    private Tenant tenant;
    private User user;
    private String model, record, policy, key;
    private Long member;
    private final AtomicInteger queries = new AtomicInteger();

    @BeforeEach
    void seed() {
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        user = users.signUp("query-cache-" + suffix + "@example.test", "test-password-123");
        var t = new Tenant(); t.setPid(UniqueIdGenerator.generate()); t.setName("query-cache-" + suffix);
        t.setDisplayName("Query cache regression"); t.setStatus("active"); t.setDeletedFlag(false);
        t.setCreatedAt(java.time.Instant.now()); t.setUpdatedAt(java.time.Instant.now());
        tenant = tenants.createTenant(t);
        members.addMember(user.getId(), tenant.getId(), "active");
        member = members.findByTenantIdAndUserId(tenant.getId(), user.getId()).getId();
        actor();
        model = "qcache_" + suffix;
        models.saveDefinition(ModelDefinition.builder().code(model).tableName("mt_" + model)
                .status("published").modelType("entity").fields(List.of(FieldDefinition.builder()
                        .code("secret").columnName("secret").dataType("string").build())).build());
        assertTrue(schema.createTableByModel(model).isSuccess());
        // This row belongs to somebody else, so a committed ALL -> SELF change revokes its READ.
        MetaContext.setContext(tenant.getId(), user.getId() + 100000L, "fixture-other", "fixture-other");
        record = MetaContext.runWithCommandPermitScope("ALL", () -> data.create(model, Map.of("secret", "private-result")))
                .get("pid").toString();
        actor();
        String role = UniqueIdGenerator.generate(); policy = UniqueIdGenerator.generate();
        long roleId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO ab_role (id,pid,tenant_id,name,deleted_flag) VALUES (?,?,?,'query-cache-role',false)",
                roleId, role, tenant.getId());
        jdbc.update("INSERT INTO ab_data_permission_policy (id,pid,tenant_id,name,model_code,policy_type,scope_type,priority,enabled,created_at,updated_at) "
                        + "VALUES (?,?,?,'query-cache-read',?,'row','all',1,true,now(),now())",
                roleId + 1, policy, tenant.getId(), model);
        jdbc.update("INSERT INTO ab_data_permission_role_binding (id,tenant_id,policy_pid,role_pid,created_at) VALUES (?,?,?,?,now())",
                roleId + 2, tenant.getId(), policy, role);
        jdbc.update("INSERT INTO ab_user_role (id,pid,member_id,tenant_id,role_id,deleted_flag) VALUES (?,?,?,?,?,false)",
                roleId + 3, UniqueIdGenerator.generate(), member, tenant.getId(), roleId);
        var permission = new com.auraboot.framework.permission.dto.PermissionCreateRequest();
        permission.setCode(model + ":read"); permission.setName("Query cache record read");
        permission.setResourceType("model"); permission.setResourceCode(model); permission.setAction("read");
        permission.setSource("integration_test");
        var created = permissions.create(permission);
        var grant = new com.auraboot.framework.rbac.entity.RolePermission();
        grant.setPid(UniqueIdGenerator.generate()); grant.setTenantId(tenant.getId());
        grant.setRoleId(roleId); grant.setPermissionId(created.getId()); grant.setGrantType("grant");
        grant.setStatus("active"); grant.setPriority(100); grant.setDeletedFlag(false);
        grant.setCreatedAt(java.time.Instant.now()); grant.setUpdatedAt(java.time.Instant.now());
        rolePermissions.insert(grant);
        userPermissions.evictPermissionDefinitions(tenant.getId());
        userPermissions.evictRoleUsers(tenant.getId(), roleId);
        key = "same-query-" + suffix;
    }

    @AfterEach
    void clearActor() { MetaContext.clear(); }

    @Test
    void committedReadRevocationCannotReplayAnOldSameKeyResult() throws Exception {
        var cached = context(true, key);
        var first = execute(cached);
        assertFalse(first.isIdempotentReplay());
        assertEquals("private-result", ((Map<?,?>) first.getData().get("row")).get("secret"));
        assertEquals(1, queries.get());
        var oldIntent = CommandIdempotencyIntent.snapshot(context(true, key));
        assertNotNull(store.checkScopedIdempotency(key, cached.getCommandCode(), oldIntent, tenant.getId()));

        assertEquals(1, jdbc.update("UPDATE ab_data_permission_policy SET scope_type='self' WHERE tenant_id=? AND pid=?",
                tenant.getId(), policy));
        assertThrows(AccessDeniedException.class, () -> data.getById(model, record));
        // Control: the original configured behavior really has a durable, now-stale outcome.
        assertTrue(execute(context(true, key)).isIdempotentReplay());
        assertEquals(1, queries.get());
        // Import + consolidation + stored JSON feed the actual pipeline policy.
        var uncached = context(false, key);
        assertThrows(AccessDeniedException.class, () -> execute(uncached));
        assertEquals(2, queries.get(), "The result-record READ must run again despite the old key");
        assertFalse(uncached.isShortCircuited());
        assertNull(uncached.getIdempotencyIntent());
        assertNotNull(store.checkScopedIdempotency(key, cached.getCommandCode(), oldIntent, tenant.getId()),
                "Ignoring an old cached result does not erase it");

        assertEquals(1, jdbc.update("UPDATE ab_data_permission_policy SET scope_type='all' WHERE tenant_id=? AND pid=?",
                tenant.getId(), policy));
        long before = cacheRows();
        var fresh = execute(context(false, key + "-fresh"));
        assertFalse(fresh.isIdempotentReplay());
        assertEquals("private-result", ((Map<?,?>) fresh.getData().get("row")).get("secret"));
        assertEquals(before, cacheRows(), "Completion must not insert a new outcome or leave a processing claim");
        assertEquals(3, queries.get());
    }

    private long cacheRows() {
        return jdbc.queryForObject("SELECT count(*) FROM ab_idempotency_record WHERE tenant_id=?", Long.class, tenant.getId());
    }

    private CommandExecuteResult execute(CommandPipelineContext context) {
        var query = new CommandPhase() {
            public String name() { return "result_record_query"; }
            public void execute(CommandPipelineContext c) {
                queries.incrementAndGet();
                c.setHandlerResults(Map.of("row", data.getById(model, record)));
            }
        };
        var pipeline = new CommandPipeline(List.of(new IdempotencyPhase(store)), List.of(query, completion));
        return new TransactionTemplate(transactions).execute(status -> {
            var replay = pipeline.executePreGuardPhases(context);
            return replay == null ? pipeline.executeGuardedPhases(context) : replay;
        });
    }

    private CommandPipelineContext context(boolean cache, String requestKey) throws Exception {
        var dto = json.readValue("{\"code\":\"fixture:query\",\"type\":\"query\","
                + "\"executionConfig\":{\"cacheResult\":" + cache + "}}",
                com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO.class);
        Map<String,Object> stored = json.readValue(json.writeValueAsString(dto.getConsolidatedExecutionConfig()),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        var request = new CommandExecuteRequest(); request.setClientRequestId(requestKey);
        // Exercise completion without unrelated public domain-event/audit effects. This test calls the
        // pipeline, not CommandExecutor's dry-run rollback wrapper; the PG cache transaction commits.
        request.setDryRun(true);
        var command = new CommandDefinition(); command.setCode("fixture:query"); command.setModelCode(model);
        return CommandPipelineContext.builder().commandCode(command.getCode()).command(command).execConfig(stored)
                .request(request).tenantId(tenant.getId()).userId(user.getId()).build();
    }

    private void actor() {
        MetaContext.setContext(tenant.getId(), user.getId(), user.getPid(), user.getUserName());
        MetaContext.setMemberId(member);
    }
}
