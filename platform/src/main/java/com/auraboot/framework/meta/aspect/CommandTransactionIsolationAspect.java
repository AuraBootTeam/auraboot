package com.auraboot.framework.meta.aspect;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.exception.CommandNotFoundException;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.impl.CommandMetadataCacheService;
import com.auraboot.framework.meta.transaction.CommandTransactionIsolationPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Selects an opt-in transaction before the ordinary transactional executor advice. */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public final class CommandTransactionIsolationAspect {
    private final CommandMetadataCacheService metadata;
    private final CommandService commands;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactions;
    public CommandTransactionIsolationAspect(@Lazy CommandMetadataCacheService metadata,@Lazy CommandService commands,
            ObjectMapper json,PlatformTransactionManager transactions) {
        this.metadata=metadata;this.commands=commands;this.json=json;this.transactions=transactions;
    }
    @Around("execution(* com.auraboot.framework.meta.service.CommandExecutor+.execute(..)) && args(code,request)")
    public Object execute(ProceedingJoinPoint invocation,String code,CommandExecuteRequest request) throws Throwable {
        if(!MetaContext.exists() || MetaContext.getCurrentTenantId()==null)return invocation.proceed();
        Map<String,Object> config=configuration(code);
        if(!CommandTransactionIsolationPolicy.requiresSerializable(config))return invocation.proceed();
        if(TransactionSynchronizationManager.isActualTransactionActive())CommandTransactionIsolationPolicy.assertActive(config);
        var transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        try {
            return transaction.execute(status->{
                try { return invocation.proceed(); }
                catch(RuntimeException | Error failure) { throw failure; }
                // CATCH: bridge AOP's checked Throwable through TransactionCallback without committing on failure.
                catch(Throwable failure) { throw new InvocationFailure(failure); }
            });
        } catch(InvocationFailure failure) { throw failure.getCause(); }
    }
    private Map<String,Object> configuration(String code) {
        var command=metadata.findCurrentCommandByCode(code);String effectiveCode,raw;
        if(command!=null) { effectiveCode=command.getCode();raw=command.getExecutionConfig(); }
        else {
            try {
                var released=commands.findByCode(code);
                if(released==null)throw new IllegalStateException("COMMAND_TRANSACTION_DEFINITION_INVALID");
                effectiveCode=released.getCode();raw=released.getExecutionConfig();
            } catch(CommandNotFoundException absent) {
                // CATCH: preserve the ordinary pipeline's unknown-command response/audit; no retry or alternate lookup.
                return Map.of();
            }
        }
        if(!code.equals(effectiveCode))throw new IllegalStateException("COMMAND_TRANSACTION_DEFINITION_INVALID");
        if(raw==null || raw.isBlank())return Map.of();
        try {
            Map<String,Object> parsed=json.readValue(raw,new TypeReference<Map<String,Object>>(){});
            return parsed==null?Map.of():parsed;
        } catch(JsonProcessingException invalid) { throw new IllegalArgumentException("COMMAND_TRANSACTION_POLICY_INVALID"); }
    }
    private static final class InvocationFailure extends RuntimeException {
        private InvocationFailure(Throwable failure) { super("COMMAND_TRANSACTION_INVOCATION_FAILED",failure); }
    }
}
