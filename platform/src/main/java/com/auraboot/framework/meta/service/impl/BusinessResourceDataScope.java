package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.Action;
import org.springframework.security.access.AccessDeniedException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** One native data operation per host loan; nested services cannot borrow its model permit. */
public final class BusinessResourceDataScope {
    private static final ThreadLocal<Lease> CURRENT = new ThreadLocal<>();
    private static final class Lease {
        final Action action;
        final String model, pid;
        final Object input;
        final Runnable verify, reject;
        boolean consumed, readback;
        String createdPid;
        Lease(Action action,String model,String pid,Object input,Runnable verify,Runnable reject) {
            this.action=action;this.model=model;this.pid=pid;this.input=input;this.verify=verify;this.reject=reject;
        }
        void deny() { reject.run(); throw new AccessDeniedException("Nested data operation is outside the business resource loan"); }
    }
    private BusinessResourceDataScope() {}
    public static <T> T withLoan(Action action,String model,String pid,Object input,Runnable verify,Runnable reject,Supplier<T> operation) {
        var previous=CURRENT.get();var lease=new Lease(action,model,pid,input,verify,reject);CURRENT.set(lease);
        try {
            verify.run();T result=operation.get();
            if(CURRENT.get()!=lease) lease.deny();
            verify.run();return result;
        } finally {if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    static void read(String model,String pid) {
        var lease=CURRENT.get();if(lease==null)return;lease.verify.run();
        if(!Objects.equals(model,lease.model))lease.deny();
        if(lease.action==Action.READ && !lease.consumed && Objects.equals(pid,lease.pid)) {lease.consumed=true;return;}
        // Native update needs its own before/after row; create needs only its generated result row.
        if(lease.consumed && (lease.action==Action.UPDATE && Objects.equals(pid,lease.pid)
                || lease.action==Action.CREATE && Objects.equals(pid,lease.createdPid)))return;
        lease.deny();
    }
    static void query(String model,Object request) { consume(Action.QUERY,model,null,request); }
    static void create(String model,Map<String,Object> input) { consume(Action.CREATE,model,null,input); }
    static void update(String model,String pid,Map<String,Object> input) { consume(Action.UPDATE,model,pid,input); }
    private static void consume(Action action,String model,String pid,Object input) {
        var lease=CURRENT.get();if(lease==null)return;lease.verify.run();
        if(lease.consumed || lease.action!=action || !Objects.equals(model,lease.model)
                || !Objects.equals(pid,lease.pid) || !Objects.equals(input,lease.input))lease.deny();
        lease.consumed=true;
    }
    static void created(String model,String pid) {
        var lease=CURRENT.get();if(lease==null)return;lease.verify.run();
        if(lease.action!=Action.CREATE || !lease.consumed || !Objects.equals(model,lease.model)
                || lease.createdPid!=null || pid==null || pid.isBlank())lease.deny();
        lease.createdPid=pid;
    }
    /** Core's own exact post-write snapshot retains the resource bound instead of a global ALL. */
    static <T> T readback(String model,String pid,Supplier<T> read) {
        var lease=CURRENT.get();
        if(lease==null)return com.auraboot.framework.application.tenant.MetaContext.runWithCommandPermitScope("ALL",read);
        if(!lease.consumed || !Objects.equals(model,lease.model)
                || !(lease.action==Action.UPDATE && Objects.equals(pid,lease.pid)
                || lease.action==Action.CREATE && Objects.equals(pid,lease.createdPid)))lease.deny();
        boolean previous=lease.readback;lease.readback=true;
        try {return com.auraboot.framework.application.tenant.MetaContext.runWithCommandPermitPlan("TARGET",null,model,pid,read);}
        finally {lease.readback=previous;}
    }
    /** Observation only: the factory still validates its actor, witness and physical transaction. */
    public static boolean matchesReadbackPermit(String model,String pid) {
        var lease=CURRENT.get();
        return lease!=null && lease.readback && Objects.equals(model,lease.model) && Objects.equals(pid,lease.pid)
                && "TARGET".equals(com.auraboot.framework.application.tenant.MetaContext.getCommandPermitScopeFor(model))
                && Objects.equals(lease.action==Action.CREATE?lease.createdPid:lease.pid,
                    com.auraboot.framework.application.tenant.MetaContext.getCommandPermitTargetFor(model));
    }
    static void unsupported() {var lease=CURRENT.get();if(lease!=null)lease.deny();}
}
