package com.auraboot.framework.menu.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.menu.constant.MenuStatus;
import com.auraboot.framework.menu.dto.NavigationConfiguration.*;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.RoleAuthorizationWriteLock;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Tenant customization overlays plugin-owned defaults; imports never write this overlay. */
@Service
@RequiredArgsConstructor
public class NavigationConfigurationService {
    private final MenuMapper menus;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final UserPermissionService permissions;
    private final PermissionMapper permissionDefinitions;
    private final PermissionSnapshotCache snapshots;
    private final RoleAuthorizationWriteLock writes;
    private final jakarta.validation.Validator validator;
    private static final Set<String> KINDS = Set.of("directory", "page", "internal", "external", "embedded");

    @Transactional
    public Snapshot read() {
        writes.lock();
        requireManager();
        return snapshot();
    }

    @Transactional
    public Snapshot create(Write request) {
        begin(request.expectedRevision());
        String pid = UlidGenerator.generate();
        var definition = validate(request.definition(), null);
        validateTree(pid, definition);
        Menu menu = new Menu();
        menu.setPid(pid);
        menu.setTenantId(tenant());
        menu.setCode("navigation_" + pid.toLowerCase(Locale.ROOT));
        menu.setCreatedAt(Instant.now());
        menu.setCreatedBy(MetaContext.getCurrentUserId());
        menu.setDeletedFlag(false);
        ExtensionBean extension = new ExtensionBean();
        extension.setDynamicProperty("navigationCustom", true);
        menu.setExtension(extension);
        apply(menu, definition, defaults());
        menus.insert(menu);
        audit(pid, "CREATE", null, definition);
        return snapshot();
    }

    @Transactional
    public Snapshot update(String pid, Write request) {
        begin(request.expectedRevision());
        Menu base = owned(pid);
        Definition before = effectiveDefinition(base);
        Definition after = validate(request.definition(), base);
        validateTree(pid, after);
        // The effective projection changes, never the plugin-owned default row.
        jdbc.update("""
                INSERT INTO ab_navigation_customization(tenant_id,menu_pid,definition)
                VALUES(?,?,?::jsonb) ON CONFLICT(tenant_id,menu_pid)
                DO UPDATE SET definition=EXCLUDED.definition,updated_at=CURRENT_TIMESTAMP
                """, tenant(), pid, encode(after));
        audit(pid, "UPDATE", before, after);
        return snapshot();
    }

    @Transactional
    public Snapshot remove(String pid, String revision, boolean restore) {
        begin(revision);
        Menu base = defaults().stream().filter(menu -> Objects.equals(pid,menu.getPid())).findFirst().orElse(null);
        if(base==null && restore && overrides(tenant()).containsKey(pid)) {
            Definition before=overrides(tenant()).get(pid);
            jdbc.update("DELETE FROM ab_navigation_customization WHERE tenant_id=? AND menu_pid=?",tenant(),pid);
            audit(pid,"RESTORE",before,null);return snapshot();
        }
        if(base==null)throw new AccessDeniedException("Navigation entry is unavailable in this tenant");
        Definition before = effectiveDefinition(base);
        if (restore) {
            if (custom(base)) throw invalid("Custom menus have no plugin default");
            // Restoring a parent must not create a cycle with customized children.
            validateTree(pid, definition(base, defaults()));
            jdbc.update("DELETE FROM ab_navigation_customization WHERE tenant_id=? AND menu_pid=?", tenant(), pid);
            audit(pid, "RESTORE", before, definition(base, defaults()));
        } else {
            if (!custom(base)) {
                Definition hidden=new Definition(before.name(),before.parentPid(),before.icon(),before.kind(),before.target(),before.opening(),before.permissionCode(),before.authenticated(),false,before.enabled(),before.orderNo());
                jdbc.update("INSERT INTO ab_navigation_customization(tenant_id,menu_pid,definition) VALUES(?,?,?::jsonb) ON CONFLICT(tenant_id,menu_pid) DO UPDATE SET definition=EXCLUDED.definition,updated_at=CURRENT_TIMESTAMP",tenant(),pid,encode(hidden));
                audit(pid,"HIDE",before,hidden);return snapshot();
            }
            if (effectiveMenus(tenant()).stream().anyMatch(menu -> Objects.equals(menu.getParentId(), base.getId())))
                throw invalid("Move or delete child menus first");
            // deletedFlag is the configured MyBatis logic-delete field. An ordinary update omits it.
            if(menus.deleteById(base.getId())!=1)throw new IllegalStateException("Navigation removal did not update exactly one owned menu");
            jdbc.update("DELETE FROM ab_role_navigation_menu WHERE tenant_id=? AND menu_pid=?",tenant(),pid);
            jdbc.update("DELETE FROM ab_navigation_customization WHERE tenant_id=? AND menu_pid=?", tenant(), pid);
            audit(pid, "DELETE", before, null);
        }
        return snapshot();
    }

    /** Fresh database reads: customization changes and removals do not depend on a stale menu cache. */
    public List<Menu> effectiveMenus(Long tenant) {
        if (tenant == null) throw new AccessDeniedException("Navigation tenant required");
        List<Menu> base = defaults(tenant);
        Map<String,Definition> overrides = overrides(tenant);
        for (Menu menu : base) {
            Definition override = overrides.get(menu.getPid());
            if (override != null) apply(menu, override, base);
        }
        base.sort(Comparator.comparing((Menu menu) -> menu.getOrderNo()==null?0:menu.getOrderNo()).thenComparing(Menu::getPid));
        Set<String> published=publishedKeys(tenant);
        for(Menu menu:base)if(menu.getPageKey()!=null && !published.contains(menu.getPageKey()))menu.setVisible(false);
        return base;
    }

    public List<Menu> defaultTargetMenus(Long tenant) { return defaults(tenant).stream().filter(menu->!custom(menu)&&com.auraboot.framework.menu.service.MenuEnvironmentScopeService.isVisibleIn(menu,MetaContext.getCurrentEnvironmentId())).toList(); }

    /** Preserve legacy target protection independently of navigation ordering and overrides. */
    public Menu targetAccessMenu(Long tenant, String path) {
        return selectTargetAccessMenu(defaultTargetMenus(tenant), path);
    }

    static Menu selectTargetAccessMenu(List<Menu> defaults, String path) {
        String pageKey = path != null && path.startsWith("/p/c/") ? path.substring(5)
                : path != null && path.startsWith("/p/") ? path.substring(3) : null;
        return defaults.stream().filter(menu -> !custom(menu))
                .filter(menu -> Objects.equals(path, menu.getPath())
                        || (pageKey != null && Objects.equals(pageKey, menu.getPageKey())))
                .findFirst().orElse(null);
    }

    private void begin(String revision) {
        writes.lock();
        requireManager();
        // Imports touching existing defaults serialize with this snapshot and mutation.
        jdbc.queryForList("SELECT id FROM ab_menu WHERE tenant_id=? AND deleted_flag=FALSE ORDER BY id FOR UPDATE", tenant());
        if (!Objects.equals(snapshot().revision(), revision))
            throw new com.auraboot.framework.exception.BusinessException(ResponseCode.CasVersionConflict);
    }

    private void requireManager() {
        Long user = MetaContext.getCurrentUserId();
        if (com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || user == null || MetaContext.getCurrentMemberId() == null
                || !snapshots.withFreshReads(() -> permissions.hasPermission(user, MetaPermission.MENU_MANAGE)))
            throw new AccessDeniedException("Navigation management permission required");
    }

    private Long tenant() {
        Long tenant = MetaContext.getCurrentTenantId();
        if (tenant == null) throw new AccessDeniedException("Navigation tenant required");
        return tenant;
    }

    private List<Menu> defaults() { return defaults(tenant()); }
    private List<Menu> defaults(Long tenant) {
        return menus.selectList(new QueryWrapper<Menu>().eq("tenant_id", tenant).eq("deleted_flag",false).in("type",0,1).orderByAsc("order_no","pid"));
    }

    private Menu owned(String pid) {
        return defaults().stream().filter(menu -> Objects.equals(pid, menu.getPid())).findFirst()
                .orElseThrow(() -> new AccessDeniedException("Navigation entry is unavailable in this tenant"));
    }

    private Map<String,Definition> overrides(Long tenant) {
        Map<String,Definition> result = new HashMap<>();
        jdbc.query("SELECT menu_pid,definition::text FROM ab_navigation_customization WHERE tenant_id=? ORDER BY menu_pid",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getString(1), decode(rs.getString(2))), tenant);
        return result;
    }

    private Definition effectiveDefinition(Menu menu) {
        return overrides(tenant()).getOrDefault(menu.getPid(), definition(menu, defaults()));
    }

    private Snapshot snapshot() {
        var base = defaults();
        var overrides = overrides(tenant());
        Set<String> published=publishedKeys(tenant());
        var entries = new ArrayList<>(base.stream().map(menu -> new Entry(menu.getPid(), overrides.getOrDefault(menu.getPid(), definition(menu,base)),
                custom(menu) ? "custom" : "default", overrides.containsKey(menu.getPid()), targetAvailable(menu,overrides.getOrDefault(menu.getPid(),definition(menu,base)),published))).toList());
        overrides.forEach((pid,definition) -> {if(base.stream().noneMatch(menu -> pid.equals(menu.getPid()))) entries.add(new Entry(pid,definition,"missing",true,false));});
        List<Option> pages = jdbc.query("""
                SELECT page_key,name FROM (
                    SELECT DISTINCT ON(page_key) page_key,name FROM ab_page_schema
                    WHERE tenant_id=? AND deleted_flag=FALSE AND status='published'
                      AND (env_id=? OR env_id IS NULL)
                    ORDER BY page_key,(env_id IS NOT NULL) DESC,is_current DESC,published_at DESC,id DESC
                ) current_pages ORDER BY name,page_key
                """, (rs,i) -> new Option(rs.getString(1),rs.getString(2)), tenant(), MetaContext.getCurrentEnvironmentId());
        // Navigation audiences reference existing page-entry requirements, not technical model CRUD.
        Map<String,Option> audienceOptions=new TreeMap<>();
        for(Menu menu:base)if(!custom(menu) && Integer.valueOf(1).equals(menu.getType())
                && menu.getPermissionCode()!=null && !menu.getPermissionCode().isBlank())
            audienceOptions.putIfAbsent(menu.getPermissionCode(),new Option(menu.getPermissionCode(),menu.getName()));
        var availablePermissions=audienceOptions.values().stream().sorted(Comparator.comparing(Option::label)).toList();
        List<Audit> audit = jdbc.query("""
                SELECT a.pid,a.menu_pid,COALESCE(a.after_definition->>'name',a.before_definition->>'name'),
                       COALESCE(u.email,''),a.action,a.created_at FROM ab_navigation_audit a
                LEFT JOIN ab_user u ON u.id=a.actor_id
                WHERE a.tenant_id=? ORDER BY a.created_at DESC,a.pid DESC LIMIT 30
                """, (rs,i) -> new Audit(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant().toString()), tenant());
        // Defaults and overlays both participate; a plugin update invalidates an old editor.
        String revision = digest(encode(List.of(base, new TreeMap<>(overrides), pages, availablePermissions)));
        return new Snapshot(revision, entries, pages, availablePermissions, audit);
    }

    private static boolean targetAvailable(Menu menu,Definition definition,Set<String> published) {
        String page="page".equals(definition.kind())?definition.target():"internal".equals(definition.kind())&&Objects.equals(definition.target(),menu.getPath())?menu.getPageKey():null;
        return page==null || published.contains(page);
    }

    private Set<String> publishedKeys(Long tenant) {
        return new HashSet<>(jdbc.queryForList("SELECT page_key FROM ab_page_schema WHERE tenant_id=? AND deleted_flag=FALSE AND status='published' AND (env_id=? OR env_id IS NULL)",String.class,tenant,MetaContext.getCurrentEnvironmentId()));
    }

    private Definition definition(Menu menu, List<Menu> all) {
        Object kind = extra(menu,"navigationKind");
        String parent = all.stream().filter(m -> Objects.equals(m.getId(),menu.getParentId())).map(Menu::getPid).findFirst().orElse(null);
        String target = String.valueOf(Optional.ofNullable(extra(menu,"navigationTarget")).orElse(Optional.ofNullable(menu.getPath()).orElse("")));
        String resolvedKind = kind == null ? (Integer.valueOf(0).equals(menu.getType()) ? "directory" : target.matches("https?://.*") ? "external" : "internal") : kind.toString();
        return new Definition(menu.getName(),parent,menu.getIcon(),resolvedKind,target,
                Objects.equals(extra(menu,"navigationOpening"),"new") ? "new" : "same",menu.getPermissionCode(),
                menu.getPermissionCode()==null || menu.getPermissionCode().isBlank(),!Boolean.FALSE.equals(menu.getVisible()),
                menu.getStatus()==MenuStatus.ACTIVE,menu.getOrderNo()==null?0:menu.getOrderNo());
    }

    private Definition validate(Definition d, Menu base) {
        if (d == null || !validator.validate(d).isEmpty() || d.kind()==null || d.opening()==null || d.target()==null || d.name()==null || d.name().isBlank() || d.name().length()>120 || !KINDS.contains(d.kind())
                || !Set.of("same","new").contains(d.opening()) || d.orderNo()<0 || d.orderNo()>100000)
            throw invalid("Invalid navigation definition");
        if (!"directory".equals(d.kind())) {
            validateTarget(d.kind(),d.target());
            if ("page".equals(d.kind()) && jdbc.queryForObject("""
                    SELECT count(*) FROM ab_page_schema WHERE tenant_id=? AND page_key=? AND deleted_flag=FALSE
                    AND status='published' AND (env_id=? OR env_id IS NULL)
                    """,Integer.class,tenant(),d.target(),MetaContext.getCurrentEnvironmentId())==0)
                throw invalid("Select an available published page");
            if ((d.permissionCode()==null || d.permissionCode().isBlank()) && !d.authenticated())
                throw invalid("Choose authenticated visibility or an existing permission");
        }
        if (base!=null && base.getPermissionCode()!=null && !base.getPermissionCode().isBlank()
                && !Objects.equals(base.getPermissionCode(),d.permissionCode()))
            throw invalid("A default menu retains its target access requirement");
        if (d.permissionCode()!=null && !d.permissionCode().isBlank()
                && (base==null || !Objects.equals(base.getPermissionCode(),d.permissionCode()))
                && permissionDefinitions.findResolvableDefinitions(tenant()).stream().noneMatch(p -> Objects.equals(p.getCode(),d.permissionCode())
                && (p.getTenantId()==null || Objects.equals(p.getTenantId(),tenant()))))
            throw invalid("Select an existing permission");
        return d;
    }

    public static void validateTarget(String kind, String target) {
        if (target==null || target.isBlank() || target.length()>2048 || target.chars().anyMatch(c -> c<=32 || c==127) || target.contains("\\"))
            throw invalid("Invalid navigation target");
        try {
            URI uri = URI.create(target);
            if (Set.of("external","embedded").contains(kind)) {
                if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)
                    throw invalid("External pages require an HTTP or HTTPS address");
            } else if ("page".equals(kind)) {
                if (!target.matches("[A-Za-z][A-Za-z0-9_-]{0,149}")) throw invalid("Invalid page reference");
            } else if (!target.startsWith("/") || target.startsWith("//") || uri.isAbsolute()
                    || !Objects.equals(uri.normalize().getPath(),uri.getPath())) throw invalid("Use an absolute internal path");
        } catch (IllegalArgumentException error) {
            throw invalid("Invalid navigation address");
        }
    }

    private void validateTree(String pid, Definition proposed) {
        var entries = new HashMap<String,Definition>();
        var base = defaults();
        var overrides = overrides(tenant());
        for (Menu menu:base) entries.put(menu.getPid(),overrides.getOrDefault(menu.getPid(),definition(menu,base)));
        validateTopology(pid,proposed,entries);
    }

    static void validateTopology(String pid,Definition proposed,Map<String,Definition> existing) {
        var entries=new HashMap<>(existing);
        entries.put(pid,proposed);
        if(!"directory".equals(proposed.kind()) && entries.values().stream().anyMatch(entry -> Objects.equals(entry.parentPid(),pid)))
            throw invalid("Move child menus before changing directory type");
        if (proposed.parentPid()!=null && !proposed.parentPid().isBlank()) {
            var parent = entries.get(proposed.parentPid());
            if (parent==null || !"directory".equals(parent.kind())) throw invalid("Select an existing directory");
        }
        Set<String> seen=new HashSet<>();
        for(String current=pid;current!=null && !current.isBlank();) {
            if(!seen.add(current)) throw invalid("Navigation cannot contain a parent cycle");
            Definition d=entries.get(current);current=d==null?null:d.parentPid();
        }
    }

    private void apply(Menu menu, Definition d, List<Menu> base) {
        String previousPath=menu.getPath();String previousPage=menu.getPageKey();
        menu.setName(d.name());menu.setIcon(d.icon());menu.setType("directory".equals(d.kind())?0:1);
        menu.setParentId(d.parentPid()==null || d.parentPid().isBlank()?null:base.stream().filter(m -> Objects.equals(m.getPid(),d.parentPid())).map(Menu::getId).findFirst().orElse(-1L));
        menu.setPath("embedded".equals(d.kind())?"/navigation/"+menu.getPid():"page".equals(d.kind())?"/p/c/"+d.target():d.target());
        menu.setPageKey("page".equals(d.kind())?d.target():!custom(menu)&&"internal".equals(d.kind())&&Objects.equals(d.target(),previousPath)?previousPage:null);
        menu.setPermissionCode(d.permissionCode());menu.setVisible(d.visible());menu.setStatus(d.enabled()?MenuStatus.ACTIVE:MenuStatus.INACTIVE);
        menu.setOrderNo(d.orderNo());menu.setI18nKey(null);
        if(menu.getExtension()==null)menu.setExtension(new ExtensionBean());
        menu.getExtension().setDynamicProperty("navigationKind",d.kind());
        menu.getExtension().setDynamicProperty("navigationTarget",d.target());
        menu.getExtension().setDynamicProperty("navigationOpening",d.opening());
        menu.getExtension().setDynamicProperty("navigationCustomized",true);
    }

    private static boolean custom(Menu menu) { return Boolean.TRUE.equals(extra(menu,"navigationCustom")); }
    private static Object extra(Menu menu,String key) { return menu.getExtension()==null?null:menu.getExtension().get(key); }
    private void audit(String pid,String action,Definition before,Definition after) {
        jdbc.update("INSERT INTO ab_navigation_audit(pid,tenant_id,menu_pid,actor_id,action,before_definition,after_definition) VALUES(?,?,?,?,?,?::jsonb,?::jsonb)",
                UlidGenerator.generate(),tenant(),pid,MetaContext.getCurrentUserId(),action,before==null?null:encode(before),after==null?null:encode(after));
    }
    private Definition decode(String value) {
        try{return json.readValue(value,Definition.class);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid stored navigation customization",e);}
    }
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Navigation serialization failed",e);}
    }
    private static String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static RuntimeException invalid(String message) { return new RootUnCheckedException(ResponseCode.BadParam,message); }
}
