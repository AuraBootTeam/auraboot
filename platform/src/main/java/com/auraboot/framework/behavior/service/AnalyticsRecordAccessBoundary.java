package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Keeps raw suggestion facts behind their domain authorization and presentation boundary. */
@Aspect
@Component
@Order(0)
public class AnalyticsRecordAccessBoundary {
    private final ThreadLocal<Principal> domainRead = new ThreadLocal<>();
    private record Principal(Long tenant, Long user) {}
    private final ThreadLocal<Boolean> semanticRead = new ThreadLocal<>();
    private final ThreadLocal<Boolean> namedRead = new ThreadLocal<>();
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.auraboot.framework.meta.service.MetaModelService metadata;
    private final com.auraboot.framework.meta.service.SecureSqlRewriter sqlSources =
            new com.auraboot.framework.meta.service.SecureSqlRewriter();


    @Around("execution(* com.auraboot.framework.behavior.service.AnalyticsSuggestionCommandHandler.execute(..))"
            + " || execution(* com.auraboot.framework.behavior.service.AnalyticsExecutionSourceService.resolve(..))"
            + " || execution(* com.auraboot.framework.behavior.controller.AnalyticsSuggestionController.list(..))")
    public Object domainOperation(ProceedingJoinPoint call) throws Throwable {
        Principal previous = domainRead.get();
        Principal current = principal();
        if (current == null || (previous != null && !previous.equals(current))) throw denied();
        domainRead.set(current);
        try {
            // This scope never supplies model, command, record or source-query permission.
            return call.proceed();
        } finally {
            if (previous == null) domainRead.remove();
            else domainRead.set(previous);
        }
    }

    @Around("execution(* com.auraboot.framework.meta.service.DynamicDataService+.*(..))")
    public Object dynamicOperation(ProceedingJoinPoint call) throws Throwable {
        Object[] args = call.getArgs();
        if (args.length > 0 && args[0] instanceof String model && protectedModel(model)
                && (domainRead.get() == null || !domainRead.get().equals(principal()))) {
            throw denied();
        }
        return call.proceed();
    }

    @Around("execution(* com.auraboot.framework.meta.service.AggregateQueryService+.execute(..))")
    public Object aggregateOperation(ProceedingJoinPoint call) throws Throwable {
        if (call.getArgs()[0] instanceof AggregateQueryRequest query && protectedModel(query.getModelCode())) {
            // Raw facts are not business-query sources, even inside a suggestion operation.
            throw denied();
        }
        if (call.getArgs()[0] instanceof AggregateQueryRequest query && "namedQuery".equals(query.getType())) {
            return namedOperation(call);
        }
        return call.proceed();
    }

    @Around("execution(* com.auraboot.framework.meta.service.NamedQueryService+.executeQuery(..))"
            + " || execution(* com.auraboot.framework.meta.service.NamedQueryService+.testQuery(..))"
            + " || execution(* com.auraboot.framework.meta.service.NamedQueryService+.exportData(..))")
    public Object namedOperation(ProceedingJoinPoint call) throws Throwable {
        Boolean previous = namedRead.get();
        namedRead.set(true);
        try {
            return call.proceed();
        } finally {
            if (previous == null) namedRead.remove();
            else namedRead.set(previous);
        }
    }

    @Around("execution(* com.auraboot.framework.meta.mapper.DynamicDataMapper.selectByQuery*(..))"
            + " || execution(* com.auraboot.framework.meta.mapper.DynamicDataMapper.countByQuery*(..))")
    public Object namedSql(ProceedingJoinPoint call) throws Throwable {
        if (Boolean.TRUE.equals(namedRead.get())) {
            java.util.Set<String> protectedTables = new java.util.HashSet<>();
            for (String model : java.util.List.of(AnalyticsSuggestionCommandHandler.VERSION, AnalyticsSuggestionCommandHandler.ADOPTION)) {
                protectedTables.add(normalizeTable(com.auraboot.framework.meta.constant.SystemFieldConstants.generateTableName(model)));
                metadata.getModelDefinition(model).ifPresent(definition -> protectedTables.add(normalizeTable(definition.getTableName())));
            }
            for (String table : sqlSources.referencedTables((String) call.getArgs()[0])) {
                if (protectedTables.contains(normalizeTable(table))) throw denied();
            }
        }
        return call.proceed();
    }

    private static String normalizeTable(String table) {
        String name = java.util.Objects.requireNonNull(table, "Physical source table is required").replace("\"", "");
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
    }

    @Around("execution(* com.auraboot.framework.semantic.service.SemanticQueryService.executeQuery(..))"
            + " || execution(* com.auraboot.framework.semantic.service.SemanticQueryService.explainQuery(..))"
            + " || execution(* com.auraboot.framework.semantic.service.SemanticQueryService.validateQuery(..))")
    public Object semanticOperation(ProceedingJoinPoint call) throws Throwable {
        Boolean previous = semanticRead.get();
        semanticRead.set(true);
        try {
            return call.proceed();
        } finally {
            if (previous == null) semanticRead.remove();
            else semanticRead.set(previous);
        }
    }

    @Around("execution(* com.auraboot.framework.meta.service.MetaModelService+.getTableName(..))")
    public Object resolveSemanticSource(ProceedingJoinPoint call) throws Throwable {
        if (Boolean.TRUE.equals(semanticRead.get()) && call.getArgs()[0] instanceof String model
                && protectedModel(model)) throw denied();
        return call.proceed();
    }

    private static boolean protectedModel(String model) {
        return AnalyticsSuggestionCommandHandler.VERSION.equals(model)
                || AnalyticsSuggestionCommandHandler.ADOPTION.equals(model);
    }

    private static Principal principal() {
        if (!MetaContext.exists()) return null;
        Long tenant = MetaContext.getCurrentTenantId();
        Long user = MetaContext.getCurrentUserId();
        return tenant == null || user == null ? null : new Principal(tenant, user);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Raw analytics records require the authorized analytics service");
    }
}
