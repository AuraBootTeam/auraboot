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
    private static final ThreadLocal<Integer> REFERENCE_READ_DEPTH = new ThreadLocal<>();
    private static final ThreadLocal<CanonicalReadFrame> CANONICAL_READ = new ThreadLocal<>();
    private static final class CanonicalReadFrame {
        final Actor actor;
        final String model, pid;
        final CanonicalReadFrame parent;
        RequiredRecordReadGuard verifier;
        RequiredReadReferences.Policy policy;
        List<RequiredReadReferences.Reference> references;
        int phase; // 0: loader, 1: final authorizer, 2: closed
        boolean completed, consumed, valid=true;
        CanonicalReadFrame(Actor actor,String model,String pid,CanonicalReadFrame parent) {
            this.actor=actor;this.model=model;this.pid=pid;this.parent=parent;
        }
        boolean matches(String model,String pid) { return java.util.Objects.equals(this.model,model) && java.util.Objects.equals(this.pid,pid); }
    }
    private static boolean sameCanonicalActor(Actor actor) {
        return MetaContext.exists() && !MachineDataAccessContext.isActive() && !MetaContext.hasCommandPermitScope()
                && java.util.Objects.equals(actor.tenant(),MetaContext.getCurrentTenantId())
                && java.util.Objects.equals(actor.user(),MetaContext.getCurrentUserId())
                && java.util.Objects.equals(actor.member(),MetaContext.getCurrentMemberId());
    }
    static void withCanonicalRead(String model,String pid,Runnable read) {
        var previous=CANONICAL_READ.get();
        CanonicalReadFrame frame=null;
        try {
            if (!MetaContext.exists() || MachineDataAccessContext.isActive() || MetaContext.hasCommandPermitScope()
                    || isResolvingReference() || MetaContext.getCurrentTenantId()==null
                    || MetaContext.getCurrentUserId()==null || MetaContext.getCurrentMemberId()==null) {
                read.run();return;
            }
            frame=new CanonicalReadFrame(new Actor(MetaContext.getCurrentTenantId(),MetaContext.getCurrentUserId(),MetaContext.getCurrentMemberId()),model,pid,previous);
            CANONICAL_READ.set(frame);
            read.run();
        }
        catch(RuntimeException | Error failure) {invalidateCanonicalRead();throw failure;}
        finally {
            if(frame!=null) {
                frame.phase=2;frame.valid=false;
                if(previous==null)CANONICAL_READ.remove();else CANONICAL_READ.set(previous);
            }
        }
    }
    /** Called only at the end of the full human physical authorization read, after all masks/FLS. */
    static void completePhysicalCanonicalRead(String model,String pid) {
        var frame=CANONICAL_READ.get();
        if(frame!=null && frame.phase==0 && frame.valid && frame.policy!=null
                && frame.matches(model,pid) && sameCanonicalActor(frame.actor)) frame.completed=true;
    }
    /** Loader success alone never authorizes the masked result; the original helper still runs. */
    static void finalizeCanonicalRead(String model,String pid,Runnable authorize) {
        var frame=CANONICAL_READ.get();
        boolean matches=frame!=null && frame.matches(model,pid);
        try {
            if(matches)frame.phase=1;
            authorize.run();
        }
        catch(RuntimeException | Error failure) {invalidateCanonicalRead();throw failure;}
        finally {if(matches) {frame.phase=2;frame.valid=false;}}
    }
    static void invalidateCanonicalRead() {
        for(var frame=CANONICAL_READ.get();frame!=null;frame=frame.parent) frame.valid=false;
    }
    /** A later record loader is an independent read, not the final check of the loaded masked row. */
    static void beforeIndependentRecordLoad() {
        var frame=CANONICAL_READ.get();
        if(frame!=null && (frame.phase==1 || frame.policy!=null)) invalidateCanonicalRead();
    }
    private static CanonicalReadFrame takeCanonicalAttempt(String model,Map<String,Object> record) {
        var frame=CANONICAL_READ.get();
        if(frame==null || frame.phase!=1 || frame.consumed || record==null
                || !(record.get("pid") instanceof String pid) || !frame.matches(model,pid)) return null;
        frame.consumed=true;
        return frame;
    }
    /** Dependency authorization needs canonical rows, not recursively derived display labels. */
    static boolean isResolvingReference() { return REFERENCE_READ_DEPTH.get() != null; }
    private Map<String,Object> readReference(RequiredReadReferences.Reference reference) {
        Integer previous = REFERENCE_READ_DEPTH.get();
        REFERENCE_READ_DEPTH.set(previous == null ? 1 : previous + 1);
        try {
            return MetaContext.runWithoutCommandPermit(() -> data.getById(reference.model(),reference.pid()));
        } finally {
            if (previous == null) REFERENCE_READ_DEPTH.remove();
            else REFERENCE_READ_DEPTH.set(previous);
        }
    }
    private record Actor(Long tenant, Long user, Long member) { }
    private static final class Traversal {
        final Actor actor; final Set<RequiredReadReferences.Reference> path = new HashSet<>(); int reads;
        final Set<RequiredReadReferences.Reference> terminals = new HashSet<>();
        Traversal(Actor actor) { this.actor=actor; }
    }
    private boolean immutablePhysicalTerminal(RequiredReadReferences.Reference reference) {
        var model=models.getModelDefinition(reference.model()).orElse(null);
        return model!=null && model.isImmutable()
                && (model.getSourceType()==null || "physical".equals(model.getSourceType()))
                && !"view".equals(model.getModelType())
                && (model.getPrimaryKey()==null || "pid".equals(model.getPrimaryKey()))
                && RequiredReadReferences.policy(model)==null;
    }
    public RequiredRecordReadGuard(MetaModelService models, @Lazy DynamicDataService data, PermissionFacade permissions,
            QueryBuilderService queries, DynamicDataMapper rows, DataDomainService domains) {
        this.models=models; this.data=data; this.permissions=permissions; this.queries=queries; this.rows=rows; this.domains=domains;
    }
    public boolean isConfigured(String modelCode) {
        try {return RequiredReadReferences.policy(models.getModelDefinition(modelCode).orElse(null)) != null;}
        catch(RuntimeException | Error failure) {invalidateCanonicalRead();throw failure;}
    }
    public void require(String modelCode, Map<String,Object> record) {
        try {requireInternal(modelCode,record);}
        catch(RuntimeException | Error failure) {invalidateCanonicalRead();throw failure;}
    }
    private void requireInternal(String modelCode, Map<String,Object> record) {
        // SQL candidates and nested reference reads cannot consume a direct canonical-read witness.
        var finalAttempt=current.get()==null && !isResolvingReference() ? takeCanonicalAttempt(modelCode,record) : null;
        var model=models.getModelDefinition(modelCode).orElse(null);
        var policy=RequiredReadReferences.policy(model); if(policy==null) return;
        var actor=actor(); var traversal=current.get(); boolean owner=traversal==null;
        if(owner) { traversal=new Traversal(actor); current.set(traversal); }
        // SQL scans share the work budget, but never a completed read between root candidates.
        boolean root=traversal.path.isEmpty();
        if(root) traversal.terminals.clear();
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
                if(finalAttempt!=null && finalAttempt.valid && finalAttempt.completed && finalAttempt.verifier==this
                        && finalAttempt.actor.equals(actor) && sameCanonicalActor(actor)
                        && policy.equals(finalAttempt.policy) && references.equals(finalAttempt.references)) return;
                for(var reference:references) {
                    if(++traversal.reads>RequiredReadReferences.MAX_REFERENCES) throw new MetaServiceException("Required-read reference budget exceeded");
                    if(!actor.equals(actor())) throw denied();
                    if(!permissions.canAction(actor.member(),reference.model(),"read")) throw denied();
                    // Only leaf nodes have no omitted descendant work/depth. Mutable or virtual
                    // records and every nonterminal still use the ordinary read on every edge.
                    boolean terminal=immutablePhysicalTerminal(reference);
                    if(terminal && traversal.terminals.contains(reference)) continue;
                    // Remove only the outer row permit. Identity and the exact writer/version plan are restored by Core.
                    var loaded=readReference(reference);
                    if(loaded==null) throw denied();
                    // A nested guard alone is not success: masking and the rest of getById must finish.
                    if(terminal) traversal.terminals.add(reference);
                }
                var frame=CANONICAL_READ.get();
                if(owner && root && !isResolvingReference() && frame!=null && frame.phase==0 && frame.valid
                        && frame.matches(modelCode,pid) && frame.actor.equals(actor) && sameCanonicalActor(actor)) {
                    // Immutable normalized identities, captured before the caller can mutate/mask its Map.
                    frame.verifier=this;frame.policy=policy;frame.references=List.copyOf(references);
                }
            } finally { traversal.path.remove(key); }
        } finally {
            if(root) traversal.terminals.clear();
            if(owner) current.remove();
        }
    }
    /** Uses an immutable root set and one exact predicate for data/count/cursor/source SQL. Over-budget is an error, never truncation. */
    public String sqlFilter(Long tenantId, String modelCode, Long userId, String baseRowFilter) {
        return sqlFilter(tenantId, modelCode, userId, baseRowFilter, true);
    }
    public String sqlFilter(Long tenantId, String modelCode, Long userId, String baseRowFilter, boolean applyDomain) {
        try {return sqlFilterInternal(tenantId,modelCode,userId,baseRowFilter,applyDomain);}
        catch(RuntimeException | Error failure) {invalidateCanonicalRead();throw failure;}
    }
    private String sqlFilterInternal(Long tenantId, String modelCode, Long userId, String baseRowFilter, boolean applyDomain) {
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
                // A nested scan can share an outer traversal path as well as its budget.
                // Clear at both candidate boundaries; a completed candidate never proves another.
                current.get().terminals.clear();
                try { require(modelCode,record); permitted.add(pid); }
                catch(AccessDeniedException | MetaRecordNotFoundException denied) {
                    // Hidden candidates retain their original semantics, but an observed denial
                    // cannot leave an earlier canonical witness usable by this or a parent operation.
                    invalidateCanonicalRead();
                }
                finally { current.get().terminals.clear(); }
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
