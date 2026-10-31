package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.integration.InstalledIntegrationBinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;

/** Independent, finite data contract from the verified installed command's server metadata. */
final class InstalledMachineAuthority implements AutoCloseable {
    private record Fields(Set<String> read, Set<String> create, Set<String> update) { }
    private static final ThreadLocal<InstalledMachineAuthority> CURRENT=new ThreadLocal<>();
    private final Map<String,Fields> fields;
    private final Map<String,MachineDataAccessContext.Decision> reads;
    private final MachineDataAccessContext.Scope scope;
    private final boolean query;

    static InstalledMachineAuthority open(InstalledIntegrationBinding binding,String config,boolean query) {
        if(!MachineDataAccessContext.isActive()) return null;
        if(CURRENT.get()!=null) throw denied("Nested installed machine authority denied");
        var principal=MachineDataAccessContext.principal();
        if(!Objects.equals(principal.tenantId(),binding.tenantId())
                || !Objects.equals(principal.installationPid(),binding.installationPid())
                || !Objects.equals(principal.applicationPid(),binding.applicationPid()))
            throw denied("Installed machine identity mismatch");
        Map<String,Fields> fields=new LinkedHashMap<>();
        boolean managedReferences=false;
        try {
            JsonNode parameters=new ObjectMapper().readTree(config).path("handlerParams");
            JsonNode declared=parameters.path("installedIntegration");
            managedReferences=declared.has("bindingLookup");
            if(managedReferences && (declared.has("bindings") || !declared.path("bindingLookup").isObject()
                    || !declared.path("bindingLookup").path("version").isIntegralNumber()
                    || !declared.path("bindingLookup").path("version").canConvertToInt()
                    || declared.path("bindingLookup").path("version").intValue()!=1
                    || !binding.sourceCode().equals(declared.path("sourceCode").asText())))throw denied("Invalid managed binding authority");
            JsonNode contract=parameters.path("installedMachineData");
            if(!contract.isMissingNode()) {
                if(!contract.isObject() || contract.size()>64) throw denied("Finite installed machine data contract required");
                contract.fields().forEachRemaining(entry->{
                    JsonNode policy=entry.getValue();
                    if(!policy.isObject()) throw denied("Invalid installed model policy");
                    policy.fieldNames().forEachRemaining(action->{
                        if(!Set.of("read","create","update").contains(action)) throw denied("Unsupported installed data action");
                    });
                    Set<String> read=fieldSet(policy.path("read"));
                    Set<String> create=fieldSet(policy.path("create"));
                    Set<String> update=fieldSet(policy.path("update"));
                    if(!read.contains("pid") || !read.containsAll(create) || !read.containsAll(update))
                        throw denied("Installed write fields must be explicitly readable");
                    fields.put(entry.getKey(),new Fields(read,create,update));
                });
            }
        } catch(com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new AccessDeniedException("Invalid installed machine data contract",invalid);
        }
        return new InstalledMachineAuthority(fields,query,managedReferences);
    }

    private InstalledMachineAuthority(Map<String,Fields> fields,boolean query,boolean managedReferences) {
        this.fields=Map.copyOf(fields); this.query=query;
        Map<String,MachineDataAccessContext.Decision> decisions=new LinkedHashMap<>();
        fields.forEach((model,policy)->decisions.put(model,decision(model,"read",policy.read(),Set.of(),List.of())));
        reads=Map.copyOf(decisions);
        scope=managedReferences?MachineDataAccessContext.authorizeRequiredReferenceReads(reads)
                :MachineDataAccessContext.authorizeWithoutRequiredReferenceReads(reads);
        CURRENT.set(this);
    }

    private static Set<String> fieldSet(JsonNode values) {
        if(values.isMissingNode()) return Set.of();
        if(!values.isArray() || values.size()>512) throw denied("Finite installed field list required");
        Set<String> result=new LinkedHashSet<>();
        values.forEach(value->{
            if(!value.isTextual() || !value.asText().matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                    || !result.add(value.asText())) throw denied("Invalid installed field identifier");
        });
        return Set.copyOf(result);
    }

    static void requireCompareField(String model,String field) {
        if(MachineDataAccessContext.isActive()
                && !MachineDataAccessContext.requireDecision(model).readableFields().contains(field))
            throw denied("Installed compare field denied");
    }

    static <T> T mutate(String model,String pid,String action,Collection<String> input,Supplier<T> work) {
        InstalledIntegrationExecution.verifyDataAuthority();
        if(!MachineDataAccessContext.isActive()) return work.get();
        InstalledMachineAuthority authority=CURRENT.get();
        if(authority==null || authority.query) throw denied("Verified installed mutation contract required");
        Fields fields=authority.fields.get(model);
        if(fields==null || (!"create".equals(action) && !MetaContext.hasCommandPermitScopeFor(model))) throw denied("Installed mutation model denied");
        Set<String> allowed="create".equals(action)?fields.create():"update".equals(action)?fields.update():Set.of();
        if(allowed.isEmpty() || input==null || input.stream().anyMatch(field->!allowed.contains(field) && !"_expectedVersion".equals(field)))
            throw denied("Installed mutation field denied");
        List<MachineDataAccessContext.Constraint> constraints;
        Long version=null;
        if("create".equals(action)) constraints=List.of();
        else {
            if(pid==null || !pid.equals(MetaContext.getCommandPermitTargetFor(model))) throw denied("Installed mutation target denied");
            version=MetaContext.getCommandExpectedVersion(model,pid);
            if(version==null || version<1) throw denied("Installed mutation requires verified target version");
            constraints=List.of(new MachineDataAccessContext.Constraint("pid","pid",List.of(pid)));
        }
        Map<String,MachineDataAccessContext.Decision> decisions=new LinkedHashMap<>(authority.reads);
        decisions.put(model,decision(model,action,fields.read(),allowed,constraints));
        try(var scope=MachineDataAccessContext.authorize(decisions)) {
            if(version==null) return MetaContext.runWithCommandPermitPlan("ALL",null,model,null,work);
            try(var expected=MachineDataAccessContext.withExpectedVersion(model,pid,version)) { return work.get(); }
        }
    }

    private static MachineDataAccessContext.Decision decision(String model,String action,Set<String> read,
            Set<String> write,List<MachineDataAccessContext.Constraint> constraints) {
        var principal=MachineDataAccessContext.principal();
        try {
            return new MachineDataAccessContext.Decision(principal.tenantId(),principal.installationPid(),model,Set.of(action),read,write,constraints);
        } catch(IllegalArgumentException invalid) {
            throw new AccessDeniedException("Invalid installed data authority",invalid);
        }
    }
    private static AccessDeniedException denied(String message) { return new AccessDeniedException(message); }
    @Override public void close() {
        if(CURRENT.get()!=this) throw new IllegalStateException("Installed machine scopes must close in order");
        try {scope.close();} finally {CURRENT.remove();}
    }
}
