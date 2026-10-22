package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;

/** Host-only exact-target mutations within one verified installed process invocation. */
final class InstalledRelatedWriteAccessor extends InstalledMachineDataAccessor {
    static final String OPT_IN = "installedRelatedWriteAuthorization";
    private final InstalledIntegrationExecution.WriteProof proof;
    private final RecordReadAccessor reads;
    private final Set<Identity> created = new HashSet<>();
    private record Identity(String model, String pid) {}

    InstalledRelatedWriteAccessor(DynamicDataService data,
            InstalledIntegrationExecution.WriteProof proof, RecordReadAccessor reads) {
        super(data);
        this.proof=Objects.requireNonNull(proof);
        this.reads=Objects.requireNonNull(reads);
    }

    @Override public Map<String,Object> create(String model, Map<String,Object> data) {
        proof.verify();
        Map<String,Object> result=MetaContext.runWithCommandPermitPlan("ALL",null,model,null,()->super.create(model,data));
        remember(model,result);
        return result;
    }

    @Override public Optional<Map<String,Object>> tryCreate(String model, Map<String,Object> data) {
        proof.verify();
        Optional<Map<String,Object>> result=MetaContext.runWithCommandPermitPlan("ALL",null,model,null,()->super.tryCreate(model,data));
        result.ifPresent(row->remember(model,row));
        return result;
    }

    private void remember(String model, Map<String,Object> row) {
        Object pid=row == null ? null : row.get("pid");
        if (!(pid instanceof String text) || text.isBlank())
            throw new SecurityException("Native create did not return a public identity");
        validate(model,text,row);
        created.add(new Identity(model,text));
    }

    @Override public Map<String,Object> update(String model,String pid,Map<String,Object> data) {
        return write("update",model,pid,data,()->super.update(model,pid,data));
    }

    @Override public boolean compareAndSet(String model,String pid,String field,Object expected,Object next) {
        return write("compareAndSet",model,pid,null,()->super.compareAndSet(model,pid,field,expected,next));
    }

    @Override public boolean compareAndSet(String model,String pid,String field,Object expected,Map<String,Object> next) {
        return write("compareAndSet",model,pid,null,()->super.compareAndSet(model,pid,field,expected,next));
    }

    @Override public void compareAndSetBatch(String model,String field,List<CompareAndSetUpdate> updates) {
        proof.verify();
        if (updates == null || updates.size()!=1 || updates.get(0)==null)
            throw new SecurityException("Installed related writes require a singleton CAS batch");
        CompareAndSetUpdate update=updates.get(0);
        write("compareAndSetBatch",model,update.recordId(),null,()->{
            super.compareAndSetBatch(model,field,updates);
            return null;
        });
    }

    private <T> T write(String operation,String model,String pid,Map<String,Object> input,Supplier<T> action) {
        proof.verify();
        if (proof.rootModel().equals(model)) {
            if (!proof.rootPid().equals(pid)) throw new SecurityException("Installed root target changed");
            long currentVersion=version(load(model,pid));
            if (input!=null && input.containsKey("_expectedVersion")
                    && exactVersion(input.get("_expectedVersion"))!=currentVersion)
                throw new com.auraboot.framework.meta.exception.RecordVersionConflictException(
                        "Installed root expected version does not match current record");
            return MetaContext.runWithCommandPermitPlan("TARGET",currentVersion,model,pid,action);
        }
        Map<String,Object> root=load(proof.rootModel(),proof.rootPid());
        Map<String,Object> related=load(model,pid);
        long version=version(related);
        if (input!=null && input.containsKey("_expectedVersion")
                && exactVersion(input.get("_expectedVersion"))!=version)
            throw new com.auraboot.framework.meta.exception.RecordVersionConflictException(
                    "Installed related write expected version does not match current record");
        var rootContext=new CommandHandlerExtension.TargetAuthorizationContext(proof.command(),proof.rootModel(),
                proof.rootPid(),"update",proof.binding().tenantId(),MetaContext.getCurrentUserPid(),root,reads);
        var context=new CommandHandlerExtension.InstalledRelatedWriteAuthorizationContext(rootContext,
                proof.binding(),operation,model,pid,related,created.contains(new Identity(model,pid)));
        if (!proof.handler().authorizeInstalledRelatedWrite(context))
            throw new SecurityException("Installed related write is not authorized");
        proof.verify();
        return MetaContext.runWithCommandPermitPlan("TARGET",version,model,pid,action);
    }

    private Map<String,Object> load(String model,String pid) {
        if (model==null || model.isBlank() || pid==null || pid.isBlank())
            throw new SecurityException("Installed related identity is missing");
        Map<String,Object> row=reads.getById(model,pid);
        validate(model,pid,row);
        version(row);
        return Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }

    private void validate(String model,String pid,Map<String,Object> row) {
        if (model==null || model.isBlank() || row==null || !Objects.equals(pid,row.get("pid"))
                || !Long.toString(proof.binding().tenantId()).equals(String.valueOf(row.get("tenant_id"))))
            throw new SecurityException("Installed related record is outside the tenant identity");
    }

    private static long version(Map<String,Object> row) { return exactVersion(row.get("row_version")); }
    private static long exactVersion(Object value) {
        try {
            if (!(value instanceof Number)) throw new IllegalArgumentException();
            long result=new BigDecimal(value.toString()).longValueExact();
            if (result<0) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) {
            throw new SecurityException("Installed related record requires a nonnegative integral row version");
        }
    }
}
