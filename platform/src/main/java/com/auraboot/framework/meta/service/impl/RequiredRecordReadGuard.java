package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.exception.MetaRecordNotFoundException;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.QueryBuilderService;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import com.auraboot.framework.permission.service.PermissionFacade;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Additional AND restriction using the original actor's read path; never grants a model or command permit. */
@Service
public class RequiredRecordReadGuard {
    private final MetaModelService models;
    private final DynamicDataService data;
    private final PermissionFacade permissions;
    private final QueryBuilderService queries;
    private final DynamicDataMapper rows;
    private final DataDomainService domains;
    private final ThreadLocal<Traversal> current = new ThreadLocal<>();
    private record Actor(Long tenant, Long user, Long member) { }
    private static final class Traversal {
        final Actor actor; final Set<RequiredReadReferences.Reference> path = new HashSet<>(); int reads;
        Traversal(Actor actor) { this.actor=actor; }
    }
    public RequiredRecordReadGuard(MetaModelService models, @Lazy DynamicDataService data, PermissionFacade permissions,
            QueryBuilderService queries, DynamicDataMapper rows, DataDomainService domains) {
        this.models=models; this.data=data; this.permissions=permissions; this.queries=queries; this.rows=rows; this.domains=domains;
    }
    public boolean isConfigured(String modelCode) { return RequiredReadReferences.policy(models.getModelDefinition(modelCode).orElse(null)) != null; }
    public void require(String modelCode, Map<String,Object> record) {
        var model=models.getModelDefinition(modelCode).orElse(null);
        var policy=RequiredReadReferences.policy(model); if(policy==null) return;
        var actor=actor(); var traversal=current.get(); boolean owner=traversal==null;
        if(owner) { traversal=new Traversal(actor); current.set(traversal); }
        try {
            if(!actor.equals(traversal.actor) || record==null || !(record.get("pid") instanceof String pid) || !RequiredReadReferences.pid(pid)
                    || !(record.get("tenant_id") instanceof Number tenant) || tenant.longValue()!=actor.tenant()
                    || Boolean.TRUE.equals(record.get("deleted_flag"))) throw denied();
            var key=new RequiredReadReferences.Reference(modelCode,pid);
            if(traversal.path.size()>=16 || !traversal.path.add(key)) throw new MetaServiceException("Required-read dependency cycle or depth budget exceeded");
            try {
                List<RequiredReadReferences.Reference> references;
                try { references=policy.references(record); }
                catch(RequiredReadReferences.InvalidRecordReferences incomplete) { throw denied(); }
                for(var reference:references) {
                    if(++traversal.reads>RequiredReadReferences.MAX_REFERENCES) throw new MetaServiceException("Required-read reference budget exceeded");
                    if(!permissions.canAction(actor.member(),reference.model(),"read")) throw denied();
                    // Remove only the outer row permit. Identity and the exact writer/version plan are restored by Core.
                    var loaded=MetaContext.runWithoutCommandPermit(()->data.getById(reference.model(),reference.pid()));
                    if(loaded==null) throw denied();
                }
            } finally { traversal.path.remove(key); }
        } finally { if(owner) current.remove(); }
    }
    /** Uses an immutable root set and one exact predicate for data/count/cursor/source SQL. Over-budget is an error, never truncation. */
    public String sqlFilter(Long tenantId, String modelCode, Long userId, String baseRowFilter) {
        return sqlFilter(tenantId, modelCode, userId, baseRowFilter, true);
    }
    public String sqlFilter(Long tenantId, String modelCode, Long userId, String baseRowFilter, boolean applyDomain) {
        var model=models.getModelDefinition(modelCode).orElse(null);
        var policy=RequiredReadReferences.policy(model); if(policy==null) return "";
        var actor=actor();
        if(!actor.tenant().equals(tenantId) || !actor.user().equals(userId)) throw denied();
        var query=queries.buildConditionQuery(model,List.of());
        query.addCondition("tenant_id",QueryCondition.Operator.EQ.name(),tenantId);
        if(baseRowFilter!=null && !baseRowFilter.isBlank()) query.addRawCondition(baseRowFilter);
        String domain=applyDomain ? domains.buildDomainFilter(modelCode,userId) : null;
        if(domain!=null && !domain.isBlank()) query.addRawCondition(domain);
        query.setLimit(policy.maxCandidates()+1).setOffset(0);
        var validation=queries.validateQuery(query);
        if(validation==null || !validation.isValid()) throw new MetaServiceException("Required-read candidate query is invalid");
        var selected=rows.selectByQuery(query.getSql(),query.getParameterMap());
        if(selected==null || selected.size()>policy.maxCandidates()) throw new MetaServiceException("Required-read candidate budget exceeded");
        List<Map<String,Object>> candidates=selected.stream().map(record -> (Map<String,Object>)new java.util.LinkedHashMap<>(record))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        JsonbFieldHelper.normalizeJsonReadValues(model,candidates);
        var permitted=new ArrayList<String>(); var seen=new HashSet<String>();
        boolean owner=current.get()==null;
        if(owner) current.set(new Traversal(actor));
        try {
            for(var record:candidates) {
                if(!(record.get("pid") instanceof String pid) || !RequiredReadReferences.pid(pid) || !seen.add(pid))
                    throw new MetaServiceException("Required-read candidate identity is invalid");
                try { require(modelCode,record); permitted.add(pid); }
                catch(AccessDeniedException | MetaRecordNotFoundException denied) { /* Expected invisible root; internal failures still abort. */ }
            }
        } finally { if(owner) current.remove(); }
        if(permitted.isEmpty()) return "AND 1 = 0";
        permitted.sort(String::compareTo);
        // Only host-validated ULIDs enter this legacy SQL-fragment interface; no client expression or escaping path exists.
        return "AND pid IN ("+permitted.stream().map(pid->"'"+pid+"'").collect(java.util.stream.Collectors.joining(","))+")";
    }
    private static Actor actor() {
        if(!MetaContext.exists() || MachineDataAccessContext.isActive()) throw denied();
        var actor=new Actor(MetaContext.getCurrentTenantId(),MetaContext.getCurrentUserId(),MetaContext.getCurrentMemberId());
        if(actor.tenant()==null || actor.tenant()<=0 || actor.user()==null || actor.user()<=0 || actor.member()==null || actor.member()<=0) throw denied();
        return actor;
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("All required records must remain readable by the current actor"); }
}
