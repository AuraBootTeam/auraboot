package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.impl.AsyncTaskExecutionContext;
import java.util.*;
import java.util.function.Supplier;

/** Host-only field proof installed after an exact handler related-write decision, never from task input. */
public final class RelatedMutationFieldScope {
    private RelatedMutationFieldScope() {}
    private static final ThreadLocal<Proof> CURRENT=new ThreadLocal<>();
    private static final class Proof {
        final Long tenant=MetaContext.getCurrentTenantId(),user=MetaContext.getCurrentUserId(),member=MetaContext.getCurrentMemberId();
        final String actor=MetaContext.getCurrentUserPid(),command=MetaContext.getAuthorizedCommandCode();
        final AsyncTaskExecutionContext.Snapshot task=AsyncTaskExecutionContext.isActive()?AsyncTaskExecutionContext.require():null;
        final String model,action; final Map<String,Object> submitted; final Set<String> observed;
        String pid;
        Proof(String model,String action,String pid,Map<String,Object> submitted,Set<String> observed) {
            this.model=model;this.action=action;this.pid=pid;this.submitted=freeze(submitted);this.observed=Set.copyOf(observed);
            if(tenant==null || user==null || member==null || actor==null || command==null || command.isBlank())
                throw new SecurityException("Related field proof requires the authenticated command identity");
            if(!("create".equals(action) && "ALL".equals(MetaContext.getCommandPermitScopeFor(model)) && pid==null)
                    && !("update".equals(action) && "TARGET".equals(MetaContext.getCommandPermitScopeFor(model))
                        && pid!=null && pid.equals(MetaContext.getCommandPermitTargetFor(model))))
                throw new SecurityException("Related field proof requires the exact native mutation permit");
        }
    }
    // Package-private: only the host's governed related accessor can install a proof.
    static <T> T within(String model,String action,String pid,Map<String,Object> submitted,Set<String> observed,Supplier<T> mutation) {
        if(!AsyncTaskExecutionContext.isActive())return mutation.get();
        Proof before=CURRENT.get();CURRENT.set(new Proof(model,action,pid,submitted,observed));
        try{return mutation.get();}
        finally{if(before==null)CURRENT.remove();else CURRENT.set(before);}
    }
    /** Called only after native row locking, canonical facts and create ownership checks. */
    public static boolean permits(String action,String model,String pid,Map<String,Object> canonical,Map<String,Object> submitted,Set<String> observed) {
        Proof proof=CURRENT.get();
        if(proof==null || proof.task==null || !AsyncTaskExecutionContext.isActive() || !proof.task.equals(AsyncTaskExecutionContext.require())
                || !Objects.equals(proof.tenant,MetaContext.getCurrentTenantId()) || !Objects.equals(proof.user,MetaContext.getCurrentUserId())
                || !Objects.equals(proof.member,MetaContext.getCurrentMemberId()) || !Objects.equals(proof.actor,MetaContext.getCurrentUserPid())
                || !Objects.equals(proof.command,MetaContext.getAuthorizedCommandCode()) || !proof.action.equals(action) || !proof.model.equals(model)
                || pid==null || pid.isBlank() || !pid.equals(canonical.get("pid"))
                || !proof.tenant.toString().equals(String.valueOf(canonical.get("tenant_id")))
                || !proof.submitted.equals(submitted) || !proof.observed.equals(observed)) return false;
        if("create".equals(action)) {
            if(!"ALL".equals(MetaContext.getCommandPermitScopeFor(model)))return false;
            if(proof.pid==null)proof.pid=pid;
        } else if(!"TARGET".equals(MetaContext.getCommandPermitScopeFor(model)) || !pid.equals(MetaContext.getCommandPermitTargetFor(model)))return false;
        return pid.equals(proof.pid);
    }
    private static Map<String,Object> freeze(Map<String,Object> values) {
        var copy=new LinkedHashMap<String,Object>();values.forEach((key,value)->copy.put(key,frozen(value)));
        return Collections.unmodifiableMap(copy);
    }
    private static Object frozen(Object value) {
        if(value instanceof Map<?,?> map) {
            var copy=new LinkedHashMap<Object,Object>();map.forEach((key,item)->copy.put(key,frozen(item)));
            return Collections.unmodifiableMap(copy);
        }
        if(value instanceof List<?> list)return list.stream().map(RelatedMutationFieldScope::frozen).toList();
        return value;
    }
}
