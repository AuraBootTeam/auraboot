package com.auraboot.framework.menu.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.menu.dto.RoleNavigation.*;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.RoleAuthorizationAuthoringAccess;
import com.auraboot.framework.permission.service.RoleAuthorizationWriteLock;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** One independent role-navigation writer; all writes share the tenant authorization lock. */
@Service
@RequiredArgsConstructor
public class RoleNavigationService {
    private final JdbcTemplate jdbc;
    private final RoleMapper roles;
    private final NavigationConfigurationService configuration;
    private final PermissionSnapshotCache snapshots;
    private final UserPermissionService permissions;
    private final RoleAuthorizationAuthoringAccess authoring;
    private final RoleAuthorizationWriteLock writes;
    private final ObjectMapper json;

    public void initialize(Role role) {
        if(role.getTenantId()!=null) jdbc.update("INSERT INTO ab_role_navigation_state(tenant_id,role_id,updated_by) VALUES(?,?,?) ON CONFLICT DO NOTHING",
                role.getTenantId(),role.getId(),MetaContext.getCurrentUserId());
    }

    @Transactional
    public Selection read(String rolePid) { writes.lock();requireManager();return selection(owned(rolePid)); }

    @Transactional
    public Selection save(String rolePid,Write request) {
        writes.lock();requireManager();Role role=owned(rolePid);
        var before=selection(role);checkRevision(before.revision(),request.expectedRevision());
        apply(role,request.selectedMenuPids());return selection(role);
    }

    /** Called by the role draft transaction, after its own authoring and revision checks. */
    public Selection selection(Role role) {
        var base=configuration.effectiveMenus(role.getTenantId());
        Set<String> codes=roleCodes(role);
        var protectedTargets=configuration.defaultTargetMenus(role.getTenantId());
        var selected=selected(role,base,codes);
        Map<Long,String> pids=new HashMap<>();base.forEach(m->pids.put(m.getId(),m.getPid()));
        var choices=new ArrayList<>(base.stream().map(menu->new Choice(menu.getPid(),menu.getName(),pids.get(menu.getParentId()),
                Integer.valueOf(0).equals(menu.getType()),!Boolean.FALSE.equals(menu.getVisible())&&menu.getStatus()==com.auraboot.framework.menu.constant.MenuStatus.ACTIVE&&MenuEnvironmentScopeService.isVisibleIn(menu,MetaContext.getCurrentEnvironmentId()),
                custom(menu)?"custom":"default",targetAllowed(menu,protectedTargets,codes),targetPermission(menu,protectedTargets))).toList());
        for(String missing:selected)if(base.stream().noneMatch(m->missing.equals(m.getPid())))
            choices.add(new Choice(missing,"",null,false,false,"missing",false,null));
        String mode=explicit(role)?"explicit":"legacy";
        return new Selection(role.getPid(),digest(List.of(role,mode,new TreeSet<>(selected),choices)),mode,selected,choices);
    }

    public void validate(Role role,Set<String> proposed) {
        if(proposed==null || proposed.size()>5000 || proposed.stream().anyMatch(pid->pid==null||pid.isBlank()||pid.length()>32))throw new AccessDeniedException("Invalid navigation selection");
        var before=selection(role);
        var known=new HashSet<>(before.menus().stream().filter(m->!m.directory()).map(Choice::pid).toList());
        if(!known.containsAll(proposed))throw new AccessDeniedException("Navigation selection contains an unavailable or foreign entry");
    }

    public Selection preview(Role role,Set<String> proposed,Set<String> resultingCodes){
        validate(role,proposed);var current=selection(role);
        return new Selection(current.rolePid(),current.revision(),"explicit",Set.copyOf(proposed),current.menus().stream()
            .map(m->new Choice(m.pid(),m.name(),m.parentPid(),m.directory(),m.enabled(),m.source(),m.permissionCode()==null||m.permissionCode().isBlank()||resultingCodes.contains(m.permissionCode()),m.permissionCode())).toList());
    }

    public void apply(Role role,Set<String> proposed) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive() || !Objects.equals(role.getTenantId(),MetaContext.getCurrentTenantId()))throw new AccessDeniedException("Owned role navigation transaction required");
        requireManager();
        validate(role,proposed);var before=selection(role);
        initialize(role);
        jdbc.update("DELETE FROM ab_role_navigation_menu WHERE tenant_id=? AND role_id=?",role.getTenantId(),role.getId());
        for(String pid:new TreeSet<>(proposed))jdbc.update("INSERT INTO ab_role_navigation_menu(tenant_id,role_id,menu_pid) VALUES(?,?,?)",role.getTenantId(),role.getId(),pid);
        jdbc.update("UPDATE ab_role_navigation_state SET updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE tenant_id=? AND role_id=?",MetaContext.getCurrentUserId(),role.getTenantId(),role.getId());
        audit(role,before,proposed);
    }

    /** Fresh active roles, including the implicit member role and governed contributors. */
    public Set<String> visibleForCurrentActor(List<Menu> menus) {
        if(!MetaContext.exists()||MetaContext.getCurrentMemberId()==null||MetaContext.getCurrentUserId()==null)return Set.of();
        Long tenant=MetaContext.getCurrentTenantId(),user=MetaContext.getCurrentUserId(),member=MetaContext.getCurrentMemberId();
        Integer active=jdbc.queryForObject("SELECT count(*) FROM ab_tenant_member m JOIN ab_user u ON u.id=m.user_id WHERE m.tenant_id=? AND m.id=? AND u.id=? AND m.status='active' AND m.deleted_flag=FALSE AND u.is_enabled=TRUE AND u.deleted_flag=FALSE",Integer.class,tenant,member,user);
        if(active==null||active!=1)return Set.of();
        return snapshots.withFreshReads(()->{
            var ids=new LinkedHashSet<>(snapshots.getUserRoleIds(tenant,user,member));
            var baseline=roles.findByTenantIdAndCode(tenant,"tenant_member");if(baseline!=null)ids.add(baseline.getId());
            var result=new HashSet<String>();
            if(!ids.isEmpty())for(Role role:roles.selectBatchIds(ids))
                if(Objects.equals(tenant,role.getTenantId())&&!Boolean.TRUE.equals(role.getDeletedFlag())&&"active".equalsIgnoreCase(role.getStatus()))
                    result.addAll(selected(role,menus,roleCodes(role)));
            return Set.copyOf(result);
        });
    }

    @Transactional
    public EntryRoles entryRoles(String menuPid) {
        writes.lock();requireManager();requireMenu(menuPid);
        var options=tenantRoles().stream().map(role->{var s=selection(role);return new RoleOption(role.getPid(),role.getName(),"active".equalsIgnoreCase(role.getStatus()),s.selectedMenuPids().contains(menuPid),s.mode());}).toList();
        return new EntryRoles(menuPid,entryRevision(menuPid),options);
    }

    @Transactional
    public EntryRoles saveEntryRoles(String menuPid,EntryRolesWrite request) {
        writes.lock();requireManager();requireMenu(menuPid);
        checkRevision(entryRevision(menuPid),request.expectedRevision());
        var owners=tenantRoles();var known=owners.stream().map(Role::getPid).collect(java.util.stream.Collectors.toSet());
        if(request.rolePids()==null||!known.containsAll(request.rolePids()))throw new AccessDeniedException("Role is unavailable in this tenant");
        for(Role role:owners){var s=selection(role);boolean want=request.rolePids().contains(role.getPid());
            if(s.selectedMenuPids().contains(menuPid)!=want){var next=new HashSet<>(s.selectedMenuPids());if(want)next.add(menuPid);else next.remove(menuPid);apply(role,next);}}
        return entryRoles(menuPid);
    }

    List<Role> tenantRoles(){return roles.selectList(new QueryWrapper<Role>().eq("tenant_id",MetaContext.getCurrentTenantId()).eq("deleted_flag",false).orderByAsc("name","pid"));}
    Role owned(String pid){Role r=roles.findByTenantIdAndPid(MetaContext.getCurrentTenantId(),pid);if(r==null||Boolean.TRUE.equals(r.getDeletedFlag()))throw new AccessDeniedException("Role is unavailable in this tenant");return r;}
    private void requireMenu(String pid){if(configuration.effectiveMenus(MetaContext.getCurrentTenantId()).stream().noneMatch(m->pid.equals(m.getPid())&&Integer.valueOf(1).equals(m.getType())))throw new AccessDeniedException("Assignable navigation entry required");}
    private String entryRevision(String menuPid){return digest(List.of(menuPid,tenantRoles().stream().map(this::selection).toList()));}
    private boolean explicit(Role role){return jdbc.queryForObject("SELECT count(*) FROM ab_role_navigation_state WHERE tenant_id=? AND role_id=?",Integer.class,role.getTenantId(),role.getId())>0;}
    private Set<String> selected(Role role,List<Menu> menus,Set<String> codes){
        if(explicit(role))return new TreeSet<>(jdbc.queryForList("SELECT menu_pid FROM ab_role_navigation_menu WHERE tenant_id=? AND role_id=? ORDER BY menu_pid",String.class,role.getTenantId(),role.getId()));
        return menus.stream().filter(m->!Integer.valueOf(0).equals(m.getType())&&!custom(m)&&legacyAllowed(m,codes)).map(Menu::getPid).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }
    Set<String> roleCodes(Role role){return snapshots.withFreshReads(()->snapshots.resolvePermissionCodes(role.getTenantId(),snapshots.getEffectiveRolePermissionIds(role.getTenantId(),role.getId())));}
    private String targetPermission(Menu menu,List<Menu> sources){
        Object kind=menu.getExtension()==null?null:menu.getExtension().get("navigationKind");
        if("external".equals(kind)||"embedded".equals(kind)||Integer.valueOf(0).equals(menu.getType()))return null;
        Menu target=NavigationConfigurationService.selectTargetAccessMenu(sources,menu.getPath());
        return target==null?null:target.getPermissionCode();
    }
    private boolean targetAllowed(Menu menu,List<Menu> sources,Set<String> codes){String code=targetPermission(menu,sources);return code==null||code.isBlank()||codes.contains(code);}
    static boolean legacyAllowed(Menu menu,Set<String> codes){return menu.getPermissionCode()==null||menu.getPermissionCode().isBlank()||codes.contains(menu.getPermissionCode());}
    private static boolean custom(Menu menu){return menu.getExtension()!=null&&Boolean.TRUE.equals(menu.getExtension().get("navigationCustom"));}
    void requireManager(){
        Long user=MetaContext.getCurrentUserId();
        if(user==null||MetaContext.getCurrentMemberId()==null||com.auraboot.framework.application.security.MachineDataAccessContext.isActive()||com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active())throw new AccessDeniedException("Human navigation manager required");
        if(!snapshots.withFreshReads(()->permissions.hasPermission(user,MetaPermission.MENU_MANAGE)))authoring.requireWrite();
    }
    @Transactional
    public List<Audit> auditLog(String rolePid){
        writes.lock();requireManager();owned(rolePid);
        return jdbc.query("SELECT a.pid,COALESCE(NULLIF(u.nick_name,''),u.email,''),a.created_at,a.after_selection::text FROM ab_role_navigation_audit a LEFT JOIN ab_user u ON u.id=a.actor_id WHERE a.tenant_id=? AND a.role_pid=? ORDER BY a.created_at DESC,a.pid DESC LIMIT 100",(rs,i)->{
            try {var row=json.readTree(rs.getString(4));return new Audit(rs.getString(1),rs.getString(2),rs.getTimestamp(3).toInstant().toString(),json.convertValue(row.path("addedMenus"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}),json.convertValue(row.path("removedMenus"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}),row.path("previousMode").asText());}
            catch(java.io.IOException e){throw new IllegalStateException("Invalid role navigation audit",e);}
        },MetaContext.getCurrentTenantId(),rolePid);
    }
    @Transactional
    public List<Menu> readMenus(Long roleId){
        writes.lock();requireManager();Role role=roles.selectById(roleId);
        if(role==null||!Objects.equals(role.getTenantId(),MetaContext.getCurrentTenantId())||Boolean.TRUE.equals(role.getDeletedFlag()))throw new AccessDeniedException("Owned role required");
        Set<String> selected=selection(role).selectedMenuPids();
        return configuration.effectiveMenus(role.getTenantId()).stream().filter(m->Integer.valueOf(0).equals(m.getType())||selected.contains(m.getPid())).toList();
    }
    private void audit(Role role,Selection before,Set<String> after){
        Map<String,String> names=new HashMap<>();before.menus().forEach(m->names.put(m.pid(),m.name()));
        var added=after.stream().filter(pid->!before.selectedMenuPids().contains(pid)).map(pid->names.getOrDefault(pid,"")).sorted().toList();
        var removed=before.selectedMenuPids().stream().filter(pid->!after.contains(pid)).map(pid->names.getOrDefault(pid,"")).sorted().toList();
        jdbc.update("INSERT INTO ab_role_navigation_audit(pid,tenant_id,role_pid,actor_id,before_selection,after_selection) VALUES(?,?,?,?,?::jsonb,?::jsonb)",UlidGenerator.generate(),role.getTenantId(),role.getPid(),MetaContext.getCurrentUserId(),encode(Map.of("name",role.getName(),"menuPids",before.selectedMenuPids())),encode(Map.of("name",role.getName(),"menuPids",after,"addedMenus",added,"removedMenus",removed,"previousMode",before.mode())));
    }
    private void checkRevision(String current,String expected){if(!Objects.equals(current,expected))throw new BusinessException(ResponseCode.CasVersionConflict);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode navigation state",e);}}
    private String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
