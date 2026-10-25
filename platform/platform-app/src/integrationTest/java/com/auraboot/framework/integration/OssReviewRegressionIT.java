package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.web.filter.JwtAuthenticationFilter;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.file.controller.FileUploadController;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.meta.controller.BatchQueryController;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.CriticalPathService;
import com.auraboot.framework.notification.routing.DefaultRecipientResolver;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.tenant.service.TenantMemberApplicationService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Owned PostgreSQL, real Spring services, production authorization and local object storage. */
@org.springframework.boot.test.context.SpringBootTest(
        classes = com.auraboot.review.OssReviewTestApplication.class,
        properties = "aura.storage.local.base-path=${AURA_EVIDENCE_ROOT}/test-files")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OssReviewRegressionIT extends BaseIntegrationTest {
    @Autowired FileService files;
    @Autowired FileUploadController fileController;
    @Autowired MetaModelService models;
    @Autowired SchemaManagementService schema;
    @Autowired DynamicDataService records;
    @Autowired DataPermissionPolicyService policies;
    @Autowired CriticalPathService schedules;
    @Autowired WatchService watches;
    @Autowired DefaultRecipientResolver recipients;
    @Autowired UserPermissionService permissions;
    @Autowired com.auraboot.framework.rbac.service.RoleService roleActions;
    @Autowired com.auraboot.framework.permission.mapper.PermissionMapper permissionDefinitions;
    @Autowired com.auraboot.framework.notification.service.NotificationTemplateService templates;
    @Autowired com.auraboot.framework.notification.mapper.NotificationTemplateMapper templateMapper;
    @Autowired com.auraboot.framework.notification.routing.NotificationRouter router;
    @Autowired NamedQueryService queries;
    @Autowired BatchQueryController batch;
    @Autowired TenantMemberApplicationService lifecycle;
    @Autowired TenantMemberService members;
    @Autowired UserService users;
    @Autowired JwtUtil jwt;
    @Autowired JwtAuthenticationFilter authentication;
    @Autowired SessionManagementService sessions;

    private String unique(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private String registerFileModel(boolean restricted) {
        applyTestMetaContext();
        String code = unique("review_");
        List<FieldDefinition> fields = new ArrayList<>();
        for (String field : List.of("id", "pid", "created_by", "file_name", "file_size", "project_ref")) {
            fields.add(FieldDefinition.builder().code(field).columnName(field)
                    .dataType(Set.of("id", "created_by", "file_size").contains(field) ? "long" : "string")
                    .primaryKey("pid".equals(field)).sortable(true).filterable(true)
                    .extraProps(restricted && "file_size".equals(field)
                            ? Map.of("fieldPermission", Map.of("view", List.of("restricted_review_role"))) : Map.of())
                    .build());
        }
        models.saveDefinition(ModelDefinition.builder().code(code).displayName("Review file fixture")
                .modelType("entity").sourceType("physical").tableName("mt_" + code).primaryKey("pid")
                .status("published").fields(fields).build());
        assertThat(schema.createTableByModel(code).isSuccess()).isTrue();
        return code;
    }

    private String createRecord(String model) {
        return String.valueOf(MetaContext.runWithCommandPermitScope("ALL", () -> records.create(model,
                Map.of("file_name", unique("task_"), "file_size", 20L, "project_ref", "review"))).get("pid"));
    }

    private void grantRead(String model) {
        grantCommittedPermissionToTestRole("model." + model + ".read", "model", model, "read", "Review record read");
        applyTestMetaContext();
    }

    private String upload() {
        applyTestMetaContext();
        byte[] bytes = "owned review artifact".getBytes(StandardCharsets.UTF_8);
        return files.uploadFile(new MockMultipartFile("file", unique("review_") + ".txt", "text/plain", bytes),
                getTestUser().getId()).getFileId();
    }

    @Test void uploadedFileIsListedAndMetadataUrlDownloadsExactBytes() throws Exception {
        String pid = upload();
        assertThat(files.getFilesByUserId(getTestUser().getId())).extracting(file -> file.getPid()).contains(pid);
        var metadata = fileController.getFile(pid, getTestUser().getId()).getBody().getData();
        assertThat(metadata.getUrl()).isEqualTo("/api/file/download/" + pid);
        var download = fileController.downloadFile(pid, getTestUser().getId());
        try (var stream = download.getBody().getInputStream()) {
            assertThat(stream.readAllBytes()).isEqualTo("owned review artifact".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(download.getHeaders().getFirst("Content-Disposition")).contains("attachment");
        var source = MetaContext.snapshot();
        try {
            MetaContext.setContext(getTestTenant().getId() + 99999, getTestUser().getId(), "other", "other");
            assertThat(files.getFileById(pid)).isNull();
            assertThat(files.getFilesByUserId(getTestUser().getId())).isEmpty();
        } finally { MetaContext.restore(source); }
    }

    @Test void schedulesEnforceModelAndFieldPermissionBeforeReturningRealValues() {
        String model = registerFileModel(false);
        String pid = createRecord(model);
        assertThatThrownBy(() -> schedules.compute(model, "pid", pid, "file_size", "file_name"))
                .isInstanceOf(AccessDeniedException.class);
        grantRead(model);
        var result = schedules.compute(model, "pid", pid, "file_size", "file_name");
        assertThat(result.totalDuration()).isEqualTo(20);
        assertThat(result.scheduleMap()).hasSize(1);
        String hidden = registerFileModel(true);
        String hiddenPid = createRecord(hidden);
        grantRead(hidden);
        assertThatThrownBy(() -> schedules.compute(hidden, "pid", hiddenPid, "file_size", "file_name"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void schedulesHonorSelfRowScopeAndExcludeAnotherMembersTask() {
        String model = registerFileModel(false);
        String own = createRecord(model);
        grantRead(model);
        var other = users.signUp(unique("scope_") + "@example.test", "ReviewTest2026x");
        var otherMember = members.addMember(other.getId(), getTestTenant().getId(), "active");
        var source = MetaContext.snapshot();
        try {
            MetaContext.setContext(getTestTenant().getId(), other.getId(), other.getPid(), other.getUserName());
            MetaContext.setMemberId(otherMember.getId());
            createRecord(model);
        } finally { MetaContext.restore(source); }
        var policy = new DataPermissionPolicyCreateRequest();
        policy.setName("Review self scope"); policy.setModelCode(model);
        policy.setPolicyType("row"); policy.setScopeType("self");
        policies.bindToRole(policies.create(policy).getPid(), getTestRole().getPid());
        var result = schedules.compute(model, "project_ref", "review", "file_size", "file_name");
        assertThat(result.totalDuration()).isEqualTo(20);
        assertThat(result.scheduleMap()).hasSize(1);
        assertThat(records.list(model, DynamicQueryRequest.builder().pageNum(1).pageSize(20)
                .sortFields(List.of(SortField.builder().fieldName("pid").build())).build()).getRecords())
                .extracting(row -> row.get("pid")).containsExactly(own);
    }

    @Test void subscriptionAndDeliveryRecheckCurrentRecordAuthority() {
        String model = registerFileModel(false);
        String pid = createRecord(model);
        assertThatThrownBy(() -> watches.toggleWatchByRecordPid(model, pid)).isInstanceOf(AccessDeniedException.class);
        grantRead(model);
        assertThat(watches.toggleWatchByRecordPid(model, pid)).isTrue();
        var event = new CommandCompletedEvent(getTestTenant().getId(), pid, model,
                Map.of("secret", "record content"), "review:" + model, "update");
        assertThat(recipients.resolve(event, "watchers", null)).containsExactly(getTestUser().getId());
        // Revoke the exact fixture grant after subscription. A stale watch is not authority.
        var jdbc = applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var request = new com.auraboot.framework.notification.dto.NotificationTemplateCreateRequest();
        request.setCode(event.getCommandCode()); request.setName("Review watch delivery");
        request.setChannel("in_app"); request.setSubjectTemplate("Owned record"); request.setBodyTemplate("${secret}");
        var template = templates.create(request);
        template.setRecipientStrategy("watchers"); templateMapper.updateById(template);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(router, "route", event, event.getCommandCode());
        Long delivered = jdbc.queryForObject("SELECT COUNT(*) FROM ab_notification WHERE tenant_id=? AND user_id=? AND source_type=? AND source_id=?",
                Long.class, getTestTenant().getId(), getTestUser().getId(), model, pid);
        assertThat(delivered).isEqualTo(1);
        assertThat(roleActions.removePermissions(getTestRole().getId(),
                List.of(permissionDefinitions.findByCode("model." + model + ".read").getId()))).isTrue();
        assertThat(recipients.resolve(event, "watchers", null)).isEmpty();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(router, "route", event, event.getCommandCode());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ab_notification WHERE tenant_id=? AND user_id=? AND source_type=? AND source_id=?",
                Long.class, getTestTenant().getId(), getTestUser().getId(), model, pid)).isEqualTo(delivered);
        assertThat(MetaContext.getCurrentUserId()).isEqualTo(getTestUser().getId());
        // Revocation must not prevent a user from removing their stale subscription.
        assertThat(watches.toggleWatchByRecordPid(model, pid)).isFalse();
    }

    @Test void batchQueryUsesRealNamedQueryOnWorkerWithCallerTenant() {
        String pid = upload();
        String code = unique("review_query_");
        var request = new NamedQueryCreateRequest();
        request.setCode(code); request.setTitle("Review batch context");
        request.setFromSql("SELECT pid, tenant_id FROM ab_file WHERE pid = '" + pid + "' AND tenant_id = #{params.tenantId}");
        request.setStatus("published");
        var field = new NamedQueryFieldRequest();
        field.setFieldCode("pid"); field.setColumnExpr("pid"); field.setDataType("string");
        request.setFields(List.of(field));
        queries.create(request);
        var item = new BatchQueryController.QueryItem(); item.setId("owned"); item.setDatasourceId("nq:" + code);
        var input = new BatchQueryController.BatchRequest(); input.setQueries(List.of(item));
        var data = batch.batchQuery(input).getData().get("owned");
        assertThat(data).isInstanceOf(PaginationResult.class);
        assertThat(((PaginationResult<?>) data).getRecords()).hasSize(1);
        assertThat(((Map<?, ?>) ((PaginationResult<?>) data).getRecords().get(0)).get("pid")).isEqualTo(pid);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void removingMemberRevokesExistingSignedSessionAndRejectsReplay(boolean batchRemoval) throws Exception {
        applyTestMetaContext();
        var user = users.signUp(unique("review_") + "@example.test", "ReviewTest2026x");
        var member = members.addMember(user.getId(), getTestTenant().getId(), "active");
        var principal = new CustomUserDetails(user.getUserName(), user.getPassword(), user.getId(), user.getPid(),
                org.springframework.security.core.authority.AuthorityUtils.NO_AUTHORITIES, true, true, true, true);
        String token = jwt.generateTokenWithTenantId(principal, user.getPid(), getTestTenant().getId(), member.getId(), user.getSecurityVersion());
        sessions.createSession(user.getId(), token, "127.0.0.1", "owned-review");
        assertThat(authenticate(token)).isEqualTo(204);
        applyTestMetaContext();
        assertThat(batchRemoval
                ? lifecycle.batchRemoveMembers(List.of(member.getPid()), getTestUser().getId())
                : lifecycle.removeMember(member.getPid(), getTestUser().getId())).isTrue();
        assertThat(sessions.isSessionValid(token)).isFalse();
        assertThat(authenticate(token)).isEqualTo(401);
    }

    @Test void inactiveMembershipRejectsAnOtherwiseUnrevokedSignedSession() throws Exception {
        applyTestMetaContext();
        var user = users.signUp(unique("inactive_") + "@example.test", "ReviewTest2026x");
        var member = members.addMember(user.getId(), getTestTenant().getId(), "active");
        var principal = new CustomUserDetails(user.getUserName(), user.getPassword(), user.getId(), user.getPid(),
                org.springframework.security.core.authority.AuthorityUtils.NO_AUTHORITIES, true, true, true, true);
        String token = jwt.generateTokenWithTenantId(principal, user.getPid(), getTestTenant().getId(), member.getId(), user.getSecurityVersion());
        sessions.createSession(user.getId(), token, "127.0.0.1", "owned-review");
        assertThat(authenticate(token)).isEqualTo(204);
        applyTestMetaContext();
        members.deactivateMember(member.getId());
        assertThat(sessions.isSessionValid(token)).isTrue();
        assertThat(authenticate(token)).isEqualTo(401);
    }

    private int authenticate(String token) throws Exception {
        SecurityContextHolder.clearContext(); MetaContext.clear();
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.setServletPath("/api/auth/me"); request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        try { authentication.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204)); }
        finally { SecurityContextHolder.clearContext(); MetaContext.clear(); }
        return response.getStatus();
    }
}
