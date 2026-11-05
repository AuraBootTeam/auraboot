package com.auraboot.framework.menu.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.menu.dto.RoleNavigation.*;
import com.auraboot.framework.permission.service.RoleAuthorizationDraftService;
import com.auraboot.framework.permission.service.RoleAuthorizationWriteLock;
import com.auraboot.framework.permission.service.SubjectPermissionService;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Read-only subject simulation; never impersonates a user or changes the request identity. */
@Service
@RequiredArgsConstructor
public class RoleNavigationPreviewService {
    private final RoleNavigationService navigation;
    private final RoleAuthorizationDraftService drafts;
    private final RoleAuthorizationWriteLock writes;
    private final PermissionSnapshotCache snapshots;
    private final JdbcTemplate jdbc;
    private final RoleMapper roles;
    private final NavigationConfigurationService configuration;
    private final SubjectPermissionService subjects;
    private final com.auraboot.framework.permission.service.RoleAuthorizationAuthoringAccess authoring;
    private final com.auraboot.framework.permission.service.UserPermissionService permissions;

    @Transactional
    public PreviewOptions options(){
        writes.lock();navigation.requireManager();
        Long tenant=MetaContext.getCurrentTenantId();
        var members=canReadMembers()?jdbc.query("SELECT m.pid,COALESCE(NULLIF(u.nick_name,''),NULLIF(u.user_name,''),u.email) FROM ab_tenant_member m JOIN ab_user u ON u.id=m.user_id WHERE m.tenant_id=? AND m.deleted_flag=FALSE AND m.status='active' AND u.deleted_flag=FALSE AND u.is_enabled=TRUE ORDER BY 2,u.email LIMIT 200",(rs,i)->new MemberOption(rs.getString(1),rs.getString(2)),tenant):List.<MemberOption>of();
        return new PreviewOptions(members,navigation.tenantRoles().stream().map(r->new RoleOption(r.getPid(),r.getName(),"active".equalsIgnoreCase(r.getStatus()),false,"preview")).toList());
    }

    @Transactional
    public UserPreview preview(PreviewRequest request){
        writes.lock();navigation.requireManager();
        if(request==null || (request.memberPid()==null)==(request.rolePids()==null))throw new AccessDeniedException("Choose a member or a simulated role combination");
        if(request.draft()!=null){authoring.requireWrite();if(request.draftRolePid()==null)throw new AccessDeniedException("Draft owner required");navigation.owned(request.draftRolePid());}
        Long tenant=MetaContext.getCurrentTenantId();Long subject=null;
        List<Role> selected;
        if(request.memberPid()!=null){
            if(!canReadMembers())throw new AccessDeniedException("Member-role read permission required");
            var actor=jdbc.queryForList("SELECT m.id,m.user_id FROM ab_tenant_member m JOIN ab_user u ON u.id=m.user_id WHERE m.tenant_id=? AND m.pid=? AND m.deleted_flag=FALSE AND m.status='active' AND u.deleted_flag=FALSE AND u.is_enabled=TRUE",tenant,request.memberPid());
            if(actor.size()!=1)throw new AccessDeniedException("Active member is unavailable in this tenant");
            Long member=((Number)actor.getFirst().get("id")).longValue();subject=((Number)actor.getFirst().get("user_id")).longValue();final Long user=subject;
            var ids=snapshots.withFreshReads(()->new LinkedHashSet<>(snapshots.getUserRoleIds(tenant,user,member)));
            var baseline=roles.findByTenantIdAndCode(tenant,"tenant_member");if(baseline!=null)ids.add(baseline.getId());
            selected=ids.isEmpty()?List.of():roles.selectBatchIds(ids).stream().filter(r->Objects.equals(tenant,r.getTenantId())&&!Boolean.TRUE.equals(r.getDeletedFlag())&&"active".equalsIgnoreCase(r.getStatus())).toList();
        }else{
            if(request.rolePids().size()>5000)throw new AccessDeniedException("Invalid role combination");
            selected=request.rolePids().stream().map(navigation::owned).filter(r->"active".equalsIgnoreCase(r.getStatus())).toList();
        }
        var codes=new HashSet<String>();var menuPids=new HashSet<String>();List<Choice> catalog=null;
        for(Role role:selected){
            var selection=navigation.selection(role);catalog=selection.menus();var roleCodes=new HashSet<>(navigation.roleCodes(role));var chosen=selection.selectedMenuPids();
            if(role.getPid().equals(request.draftRolePid())&&request.draft()!=null){
                var reviewed=drafts.preview(role.getPid(),request.draft());
                roleCodes.removeAll(reviewed.capabilities().revokedCodes());roleCodes.addAll(reviewed.capabilities().grantedCodes());
                if(request.draft().menuPids()!=null)chosen=request.draft().menuPids();
            }
            codes.addAll(roleCodes);menuPids.addAll(chosen);
        }
        if(catalog==null)catalog=List.of();
        Map<Long,Boolean> visibility=Map.of();Map<String,Long> idsByPid=new HashMap<>();
        if(subject!=null){var menus=configuration.effectiveMenus(tenant);menus.forEach(m->idsByPid.put(m.getPid(),m.getId()));visibility=subjects.batchEvaluateVisibility("menu",menus.stream().map(m->m.getId()).toList(),subject);}
        final var permitted=visibility;final boolean memberPreview=subject!=null;
        var selectedMenus=catalog.stream().filter(m->m.enabled()&&!m.directory()&&menuPids.contains(m.pid())&&(!memberPreview||permitted.getOrDefault(idsByPid.get(m.pid()),true)))
                .map(m->new Choice(m.pid(),m.name(),m.parentPid(),false,true,m.source(),m.permissionCode()==null||m.permissionCode().isBlank()||codes.contains(m.permissionCode()),m.permissionCode())).toList();
        return new UserPreview(selected.stream().map(Role::getPid).collect(java.util.stream.Collectors.toSet()),selectedMenus,request.memberPid()==null?"simulation":"member");
    }
    private boolean canReadMembers(){return snapshots.withFreshReads(()->permissions.hasPermission(MetaContext.getCurrentUserId(),com.auraboot.framework.permission.constants.MetaPermission.USER_ROLE_READ));}
}
