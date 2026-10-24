package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.workbench.mapper.AnnouncementAudienceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** One resolution algorithm is shared by preview, submission and release execution. */
@Service
@RequiredArgsConstructor
public class AnnouncementAudienceService {
    private final AnnouncementAudienceMapper directory;
    private final DataScopeService scopes;
    private final UserPermissionService permissions;

    public record Rule(String kind, List<String> userPids, List<String> departmentPids, List<String> rolePids) {
        public Rule {
            if (!Set.of("all","selected").contains(kind == null ? "" : kind)) invalid();
            userPids=clean(userPids); departmentPids=clean(departmentPids); rolePids=clean(rolePids);
            if ("all".equals(kind) && (!userPids.isEmpty() || !departmentPids.isEmpty() || !rolePids.isEmpty())) invalid();
        }
        private static List<String> clean(List<String> values) {
            if (values==null) return List.of();
            if(values.size()>1000 || values.stream().anyMatch(s->s==null || s.isBlank() || s.length()>36)) invalid();
            return values.stream().distinct().sorted().toList();
        }
    }
    public record Recipient(String userPid,String memberPid,String displayName,List<String> matchedRuleRefs) {}
    public record Resolution(List<Recipient> recipients, String scopeType, List<String> allowedDepartmentPids,
                             boolean roleLimitedToManagementScope, Instant resolvedAt) {}

    @Transactional(readOnly=true)
    public Resolution resolve(Rule rule) {
        ScopeView view=scopeView();
        Long tenant=view.tenant(); boolean all=view.all(); DataScopeCondition scope=view.scope();
        List<Map<String,Object>> memberRows=view.members(),departments=view.departments();
        Set<String> allowedDepartments=view.allowedDepartments(),allowedUsers=view.allowedUsers();
        if("all".equals(rule.kind()) && !all) denied();
        Set<String> selectedDepartments=new TreeSet<>();
        for(String root:rule.departmentPids()) {
            Set<String> subtree=subtree(root,departments);
            if(!all && (!Set.of("dept","dept_and_sub").contains(scope.scopeType()) || !allowedDepartments.containsAll(subtree))) denied();
            selectedDepartments.addAll(subtree);
        }
        if(!allowedUsers.containsAll(rule.userPids())) denied();
        Map<String,Set<String>> roleUsers=new TreeMap<>();
        for(String role:rule.rolePids()) roleUsers.put(role,new TreeSet<>());
        Set<String> knownRoles=new HashSet<>();
        for(Map<String,Object> m:directory.roles(tenant)) {
            String role=(String)m.get("role_pid"),user=(String)m.get("user_pid");
            knownRoles.add(role);
            if(roleUsers.containsKey(role) && user!=null && allowedUsers.contains(user)) roleUsers.get(role).add(user);
        }
        if(!knownRoles.containsAll(rule.rolePids())) invalid();
        Map<String,Recipient> recipients=new TreeMap<>();
        for(Map<String,Object> m:memberRows) {
            String user=(String)m.get("user_pid"),dept=(String)m.get("department_pid");
            if(!allowedUsers.contains(user)) continue;
            List<String> matches=new ArrayList<>();
            if("all".equals(rule.kind())) matches.add("all");
            if(rule.userPids().contains(user)) matches.add("person:"+user);
            if(dept!=null && selectedDepartments.contains(dept)) matches.add("department:"+dept);
            roleUsers.forEach((role,users)->{if(users.contains(user)) matches.add("role:"+role);});
            if(matches.isEmpty()) continue;
            Recipient existing=recipients.get(user);
            Set<String> combined=new TreeSet<>(matches);
            if(existing!=null) combined.addAll(existing.matchedRuleRefs());
            recipients.put(user,new Recipient(user,existing==null ? (String)m.get("member_pid") : existing.memberPid(),
                Objects.toString(m.get("display_name"),Objects.toString(m.get("username"),"")),List.copyOf(combined)));
        }
        return new Resolution(List.copyOf(recipients.values()),scope.scopeType(),List.copyOf(allowedDepartments),true,Instant.now());
    }

    private record ScopeView(Long tenant,boolean all,DataScopeCondition scope,List<Map<String,Object>> members,
        List<Map<String,Object>> departments,Set<String> allowedUsers,Set<String> allowedDepartments) {}

    private ScopeView scopeView() {
        Long tenant=MetaContext.getCurrentTenantId(), actor=MetaContext.getCurrentUserId();
        if(tenant==null || actor==null || !permissions.hasPermission(actor,"announcement.announcement.publish")) denied();
        List<Map<String,Object>> memberRows=directory.members(tenant);
        String actorPid=MetaContext.getCurrentUserPid();
        if(memberRows.stream().noneMatch(m->actorPid.equals(m.get("user_pid")))) denied();
        boolean all=permissions.hasPermission(actor,"announcement.announcement.publish_all");
        DataScopeCondition scope=all ? DataScopeCondition.all()
            : scopes.resolveAudienceScope(MetaContext.getCurrentMemberId(),"ab_announcement","publish");
        if(scope==null || Set.of("none","not_configured").contains(scope.scopeType())) denied();
        Set<String> allowedDepartments=new TreeSet<>(scope.deptPids());
        List<Map<String,Object>> departments=directory.departments(tenant);
        Set<String> allowedUsers=new TreeSet<>();
        Set<String> teamUsers=new TreeSet<>();
        if("team".equals(scope.scopeType())) {
            directory.teams(tenant).stream().filter(m->allowedDepartments.contains(m.get("team_pid")))
                .forEach(m->teamUsers.add((String)m.get("user_pid")));
        }
        for(Map<String,Object> m:memberRows) {
            String user=(String)m.get("user_pid"), dept=(String)m.get("department_pid");
            boolean permitted=switch(scope.scopeType()) {
                case "all" -> all;
                case "self" -> actorPid.equals(user);
                case "dept","dept_and_sub" -> dept!=null && allowedDepartments.contains(dept);
                case "team" -> teamUsers.contains(user);
                default -> false;
            };
            if(permitted) allowedUsers.add(user);
        }
        return new ScopeView(tenant,all,scope,memberRows,departments,allowedUsers,allowedDepartments);
    }

    public record Option(String value,Map<String,String> label) {
        public Option(String value,String name) {this(value,Map.of("zh-CN",name,"en-US",name));}
    }
    @Transactional(readOnly=true)
    public List<Option> options(String kind) {
        ScopeView view=scopeView();
        if("kind".equals(kind)) return view.all() ? List.of(new Option("selected",Map.of("zh-CN","定向通知","en-US","Selected audience")),new Option("all",Map.of("zh-CN","当前租户全员","en-US","All tenant members"))) : List.of(new Option("selected",Map.of("zh-CN","定向通知","en-US","Selected audience")));
        if("person".equals(kind)) return view.members().stream().filter(m->view.allowedUsers().contains(m.get("user_pid")))
            .map(m->new Option((String)m.get("user_pid"),Objects.toString(m.get("display_name"),Objects.toString(m.get("username"),"")))).distinct().toList();
        if("department".equals(kind)) return view.departments().stream()
            .filter(d->view.all() || (Set.of("dept","dept_and_sub").contains(view.scope().scopeType())
                && view.allowedDepartments().containsAll(subtree((String)d.get("pid"),view.departments()))))
            .map(d->new Option((String)d.get("pid"),(String)d.get("label"))).toList();
        if("role".equals(kind)) return directory.roles(view.tenant()).stream()
            .filter(r->r.get("user_pid")!=null && view.allowedUsers().contains(r.get("user_pid")))
            .map(r->new Option((String)r.get("role_pid"),(String)r.get("label"))).distinct().toList();
        invalid(); return List.of();
    }

    private static Set<String> subtree(String root,List<Map<String,Object>> departments) {
        if(departments.stream().noneMatch(d->root.equals(d.get("pid")))) invalid();
        Set<String> result=new TreeSet<>(); result.add(root);
        boolean changed;
        do { changed=false; for(Map<String,Object> d:departments) {
            if(d.get("parent_pid") != null && result.contains(d.get("parent_pid"))) changed|=result.add((String)d.get("pid"));
        }} while(changed);
        return result;
    }
    private static void denied(){throw new AccessDeniedException("Announcement audience exceeds the current management scope");}
    private static void invalid(){throw BusinessException.i18n("announcement.error.invalid_input");}
}
