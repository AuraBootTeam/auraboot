package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.service.UserService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Real, uniquely scoped acceptance identity; retained for native evidence inspection. */
record SemanticAcceptanceIdentity(long tenantId, long userId, long memberId, String userPid, String username) {
    static SemanticAcceptanceIdentity create(UserService users, TenantService tenants,
                                             TenantMemberService members, String purpose) {
        String run = purpose + "-" + UUID.randomUUID();
        var user = users.signUp(run + "@example.test", "SemanticFixture2026!", run, run);
        var draft = new Tenant();
        draft.setPid(UniqueIdGenerator.generate()); draft.setName(run); draft.setDisplayName(run);
        draft.setStatus(StatusConstants.ACTIVE); draft.setDeletedFlag(false);
        draft.setCreatedAt(Instant.now()); draft.setUpdatedAt(Instant.now());
        var tenant = tenants.createTenant(draft);
        MetaContext.clear();
        MetaContext.setContext(tenant.getId(), user.getId(), user.getPid(), user.getUserName());
        var member = members.addMember(user.getId(), tenant.getId(), StatusConstants.ACTIVE);
        var identity = new SemanticAcceptanceIdentity(tenant.getId(), user.getId(), member.getId(),
                user.getPid(), user.getUserName());
        identity.bind();
        return identity;
    }

    void bind() {
        MetaContext.setContext(tenantId, userId, userPid, username);
        MetaContext.setMemberId(memberId);
    }

    void registerSource(MetaModelService sources) {
        var definition = ModelDefinition.builder().build();
        definition.setCode("ab_object_alias"); definition.setTableName("ab_object_alias");
        definition.setSourceType("physical"); definition.setStatus(StatusConstants.PUBLISHED);
        definition.setPrimaryKey("pid");
        definition.setFields(List.of(
                FieldDefinition.builder().code("pid").columnName("pid").dataType("string").primaryKey(true).build(),
                FieldDefinition.builder().code("language").columnName("language").dataType("string").build(),
                FieldDefinition.builder().code("alias").columnName("alias").dataType("string").build()));
        sources.saveDefinition(definition);
    }
}
