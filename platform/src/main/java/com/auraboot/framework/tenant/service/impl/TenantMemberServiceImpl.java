package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 租户成员服务实现类
 */
@Slf4j
@Service
public class TenantMemberServiceImpl extends ServiceImpl<TenantMemberMapper, TenantMember> implements TenantMemberService {

    @Autowired
    private TenantMemberMapper tenantMemberMapper;
    @Autowired
    private ObjectMapper objectMapper;

    private static final Set<String> TEAM_LIST_KEYS = Set.of("teamIds", "team_ids", "teams");
    private static final Set<String> TEAM_SINGLE_KEYS = Set.of("teamId", "team_id");
    private static final List<String> TEAM_OBJECT_ID_KEYS = List.of("teamId", "team_id", "id", "pid", "code");

    @Override
    public TenantMember findByPid(String pid) {
        QueryWrapper<TenantMember> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().eq(TenantMember::getPid, pid);
        return getOne(queryWrapper);
    }
    
    @Override
    public TenantMember findByTenantIdAndUserId(Long tenantId, Long userId) {
        return tenantMemberMapper.findByTenantIdAndUserId(tenantId, userId);
    }
    
    @Transactional
    public TenantMember addMember(Long userId, Long tenantId, String status) {
        log.info("Adding member: user {} to tenant {}", userId, tenantId);

        TenantMember byTenantIdAndUserId = tenantMemberMapper.findByTenantIdAndUserId(tenantId, userId);
        // 检查用户是否已经是该租户的成员
        if (null != byTenantIdAndUserId) {
            throw new BusinessException("用户已经是该租户的成员");
        }

        TenantMember member = new TenantMember();
        member.setPid(UniqueIdGenerator.generate()); // 生成业务ID
        member.setTenantId(tenantId);
        member.setUserId(userId);
        member.setStatus(status);
        member.setJoinDate(Instant.now());
        member.setCreatedBy(userId);
        member.setUpdatedBy(userId);
        validateAndNormalizeTeamProfile(member, null);
        
        save(member);
        
        log.info("Member added successfully: {}", member.getId());
        return member;
    }

    @Override
    @Transactional
    public TenantMember updateMember(TenantMember member) {
        log.info("Updating member: {}", member.getId());
        
        TenantMember existingMember = getById(member.getId());
        if (existingMember == null) {
            throw new BusinessException("成员不存在: " + member.getId());
        }
        
        validateAndNormalizeTeamProfile(member, existingMember);
        member.setUpdatedAt(Instant.now());
        updateById(member);
        
        log.info("Member updated successfully: {}", member.getId());
        return member;
    }

    @Override
    public List<TenantMember> findByTenantId(Long tenantId) {
        return tenantMemberMapper.findByTenantId(tenantId);
    }






    @Override
    public Page<TenantMember> findMembers(int pageNum, int pageSize, Long tenantId,
                                          String keyword, String memberType, String status) {
        Page<TenantMember> page = new Page<>(pageNum, pageSize);
        QueryWrapper<TenantMember> queryWrapper = new QueryWrapper<>();
        // ab_tenant_member is in ignoreTable list, so TenantLineInterceptor does NOT auto-add tenant_id.
        // We must filter by tenant_id explicitly.
        queryWrapper.eq("tenant_id", tenantId);

        // keyword search is not supported on tenant member directly (fields like position/department
        // were removed from the schema). Callers should filter by user email at the application layer.

        if (StringUtils.hasText(status)) {
            queryWrapper.eq("status", status);
        }

        queryWrapper.orderByDesc("created_at");

        return page(page, queryWrapper);
    }

//    @Override
//    public Map<String, Object> getMemberDetail(Long memberId) {
//        return tenantMemberMapper.getMemberDetail(memberId);
//    }



    @Override
    @Transactional
    public boolean activateMember(Long memberId) {
        log.info("Activating member: {}", memberId);
        
        TenantMember member = getById(memberId);
        if (member == null) {
            throw new BusinessException("成员不存在: " + memberId);
        }
        
        member.setStatus(StatusConstants.ACTIVE);
        member.setUpdatedAt(Instant.now());
        
        return updateById(member);
    }

    @Override
    @Transactional
    public boolean deactivateMember(Long memberId) {
        log.info("Deactivating member: {}", memberId);

        TenantMember member = getById(memberId);
        if (member == null) {
            throw new BusinessException("成员不存在: " + memberId);
        }

        member.setStatus(StatusConstants.INACTIVE);
        member.setLeaveDate(Instant.now());
        member.setUpdatedAt(Instant.now());

        return updateById(member);
    }

    @Override
    @Transactional
    public boolean suspendMember(Long memberId, String reason) {
        log.info("Suspending member: {}, reason: {}", memberId, reason);
        
        TenantMember member = getById(memberId);
        if (member == null) {
            throw new BusinessException("成员不存在: " + memberId);
        }
        
        member.setStatus(StatusConstants.SUSPENDED);
        member.setUpdatedAt(Instant.now());
        // 可以在settings中记录暂停原因
        
        return updateById(member);
    }

    @Override
    @Transactional
    public boolean removeMember(Long memberId) {
        log.info("Removing member: {}", memberId);

        TenantMember member = getById(memberId);
        if (member == null) {
            throw new BusinessException("成员不存在: " + memberId);
        }

        // Set leaveDate before logical delete
        member.setLeaveDate(Instant.now());
        member.setUpdatedAt(Instant.now());
        updateById(member);

        // Use removeById for logical delete — updateById excludes the logic-delete field
        return removeById(memberId);
    }









    @Override
    public List<TenantMember> findLeavingMembers(int days) {
        Instant futureDate = Instant.now().plus(days, ChronoUnit.DAYS);
        Date targetDate = Date.from(futureDate.atZone(ZoneOffset.UTC).toInstant());
        
        QueryWrapper<TenantMember> queryWrapper = new QueryWrapper<>();
        // deleted_flag auto-filtered by MP global logic-delete config
        queryWrapper.eq("status", StatusConstants.ACTIVE)
                   .isNotNull("leave_date")
                   .le("leave_date", targetDate)
                   .ge("leave_date", Instant.now());
        
        return list(queryWrapper);
    }



    @Override
    public List<Long> getTenantIdsByUserId(Long userId) {
        log.info("Getting all tenant IDs for user: {}", userId);
        
        List<Long> tenantIds = tenantMemberMapper.getTenantIdsByUserId(userId);
        
        log.info("Found {} tenant(s) for user {}: {}", tenantIds.size(), userId, tenantIds);
        return tenantIds;
    }

    @Override
    public Long getTenantIdByUserId(Long userId) {
        log.info("Getting single tenant ID for user: {}", userId);

        List<Long> tenantIds = getTenantIdsByUserId(userId);

        if (tenantIds.isEmpty()) {
            log.warn("No active tenant found for user: {}", userId);
            return null;
        }

        if (tenantIds.size() == 1) {
            Long tenantId = tenantIds.get(0);
            log.info("Found single tenant {} for user {}", tenantId, userId);
            return tenantId;
        }

        // Multiple tenants — prefer the default business tenant (non-System).
        // System Tenant is the Control Plane; daily login should resolve to the business tenant.
        log.info("User {} belongs to {} tenants: {}, resolving default business tenant", userId, tenantIds.size(), tenantIds);

        // Strategy 1: Pick the non-System tenant with role assignments
        for (Long tid : tenantIds) {
            if (isSystemTenant(tid)) continue;
            long roleCount = baseMapper.countUserRolesInTenant(userId, tid);
            if (roleCount > 0) {
                log.info("Selected business tenant {} for user {} (has {} role(s))", tid, userId, roleCount);
                return tid;
            }
        }

        // Strategy 2: Any non-System tenant (even without roles yet)
        for (Long tid : tenantIds) {
            if (!isSystemTenant(tid)) {
                log.info("Selected non-System tenant {} for user {} (no role bindings yet)", tid, userId);
                return tid;
            }
        }

        // Fallback: first tenant (should only happen if user is ONLY in System tenant)
        Long tenantId = tenantIds.get(0);
        log.info("Fallback to first tenant {} for user {}", tenantId, userId);
        return tenantId;
    }

    /**
     * Check if a tenant is the System Tenant (Control Plane).
     * System tenant is identified by name "System" (created during bootstrap).
     */
    private boolean isSystemTenant(Long tenantId) {
        if (tenantId == null) return false;
        try {
            String name = tenantMemberMapper.getTenantNameById(tenantId);
            return "System".equals(name);
        } catch (Exception e) {
            return false;
        }
    }

    private void validateAndNormalizeTeamProfile(TenantMember incoming, TenantMember existing) {
        boolean settingsChanged = existing == null || !Objects.equals(existing.getSettings(), incoming.getSettings());
        boolean extensionsChanged = existing == null || !Objects.equals(existing.getExtensions(), incoming.getExtensions());
        boolean permissionsChanged = existing == null || !Objects.equals(existing.getPermissions(), incoming.getPermissions());
        if (!settingsChanged && !extensionsChanged && !permissionsChanged) {
            return;
        }

        JsonNode settingsNode = parseJsonNode("settings", incoming.getSettings(), incoming.getUserId(), incoming.getTenantId());
        JsonNode extensionsNode = parseJsonNode("extensions", incoming.getExtensions(), incoming.getUserId(), incoming.getTenantId());
        JsonNode permissionsNode = parseJsonNode("permissions", incoming.getPermissions(), incoming.getUserId(), incoming.getTenantId());

        LinkedHashSet<String> teamIds = new LinkedHashSet<>();
        extractTeamIds(settingsNode, teamIds);
        extractTeamIds(extensionsNode, teamIds);
        extractTeamIds(permissionsNode, teamIds);

        if (teamIds.isEmpty()) {
            return;
        }

        ObjectNode normalizedSettings = settingsNode != null && settingsNode.isObject()
                ? (ObjectNode) settingsNode.deepCopy()
                : objectMapper.createObjectNode();

        ArrayNode teamArray = objectMapper.createArrayNode();
        teamIds.forEach(teamArray::add);
        normalizedSettings.set("teamIds", teamArray);
        incoming.setSettings(normalizedSettings.toString());
    }

    private JsonNode parseJsonNode(String fieldName, String rawJson, Long userId, Long tenantId) {
        if (!StringUtils.hasText(rawJson)) {
            return null;
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception ex) {
            throw new BusinessException(String.format(
                    "Invalid tenant_member.%s JSON for user=%s tenant=%s: %s",
                    fieldName, userId, tenantId, ex.getMessage()
            ));
        }
    }

    private void extractTeamIds(JsonNode node, Set<String> teamIds) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (TEAM_SINGLE_KEYS.contains(key)) {
                    addTeamIdCandidate(value, teamIds);
                } else if (TEAM_LIST_KEYS.contains(key)) {
                    addTeamCollection(value, teamIds);
                }
                if (value != null && (value.isObject() || value.isArray())) {
                    extractTeamIds(value, teamIds);
                }
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> extractTeamIds(item, teamIds));
        }
    }

    private void addTeamCollection(JsonNode value, Set<String> teamIds) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            value.forEach(item -> addTeamIdCandidate(item, teamIds));
            return;
        }
        if (value.isTextual()) {
            String text = value.asText();
            if (!StringUtils.hasText(text)) {
                return;
            }
            String trimmed = text.trim();
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                try {
                    JsonNode parsed = objectMapper.readTree(trimmed);
                    if (parsed.isArray()) {
                        parsed.forEach(item -> addTeamIdCandidate(item, teamIds));
                    } else {
                        addTeamIdCandidate(parsed, teamIds);
                    }
                    return;
                } catch (Exception ignored) {
                    // Fallback to delimiter parsing.
                }
            }
            Arrays.stream(trimmed.split("[,;\\s]+"))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .filter(token -> !"null".equalsIgnoreCase(token))
                    .map(token -> token.toLowerCase(Locale.ROOT))
                    .forEach(teamIds::add);
            return;
        }
        addTeamIdCandidate(value, teamIds);
    }

    private void addTeamIdCandidate(JsonNode candidate, Set<String> teamIds) {
        if (candidate == null || candidate.isNull()) {
            return;
        }
        if (candidate.isTextual() || candidate.isNumber()) {
            String value = candidate.asText();
            if (StringUtils.hasText(value) && !"null".equalsIgnoreCase(value.trim())) {
                teamIds.add(value.trim().toLowerCase(Locale.ROOT));
            }
            return;
        }
        if (candidate.isObject()) {
            for (String idKey : TEAM_OBJECT_ID_KEYS) {
                JsonNode idNode = candidate.get(idKey);
                if (idNode != null && !idNode.isNull() && (idNode.isTextual() || idNode.isNumber())) {
                    String value = idNode.asText();
                    if (StringUtils.hasText(value) && !"null".equalsIgnoreCase(value.trim())) {
                        teamIds.add(value.trim().toLowerCase(Locale.ROOT));
                        return;
                    }
                }
            }
        }
    }

}
