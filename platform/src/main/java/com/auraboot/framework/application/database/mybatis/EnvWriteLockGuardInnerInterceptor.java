package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.environment.service.EnvLockGuard;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.springframework.context.ApplicationContext;

import java.util.Set;

/**
 * Env-layering #18-deferred / #19 — write-side lock guard for UPDATE / DELETE on
 * {@code @EnvScoped} tables. INSERT is already covered by
 * {@code AuraBootObjectHandler.fillEnvIdIfApplicable} hooked from the MetaObjectHandler
 * pathway, but UPDATE/DELETE never trigger the handler — this interceptor closes that gap.
 *
 * <p>Triggers when:
 * <ol>
 *   <li>{@link SqlCommandType} is UPDATE or DELETE</li>
 *   <li>The current {@link MetaContext#getCurrentEnvironmentId()} is non-null</li>
 *   <li>Bypass is not active (promotion.apply still flows freely)</li>
 *   <li>The SQL touches at least one env-scoped table</li>
 * </ol>
 *
 * <p>Then it asks {@link EnvLockGuard#assertWritable} which throws if that env is locked.
 * Cross-tenant / cross-env queries that don't reference an env-scoped table pass through.
 *
 * <p>Lookup of the {@link EnvLockGuard} bean is lazy via {@link ApplicationContext} to avoid
 * a circular dependency with the SqlSessionFactory bootstrap.
 */
@Slf4j
public class EnvWriteLockGuardInnerInterceptor implements InnerInterceptor {

    private final ApplicationContext applicationContext;
    private volatile EnvLockGuard cachedGuard;

    public EnvWriteLockGuardInnerInterceptor(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms, Object parameter) {
        SqlCommandType type = ms.getSqlCommandType();
        if (type != SqlCommandType.UPDATE && type != SqlCommandType.DELETE) {
            return;
        }
        if (MetaContext.isLockGuardBypassed()) {
            return;
        }
        Long envId = MetaContext.getCurrentEnvironmentId();
        if (envId == null) {
            return;
        }

        Set<String> scoped = MybatisPlusConfig.envScopedTables();
        if (scoped.isEmpty()) {
            return;
        }

        BoundSql boundSql = ms.getBoundSql(parameter);
        String sqlLower = boundSql.getSql().toLowerCase();

        boolean touchesScoped = false;
        for (String table : scoped) {
            if (matchesTable(sqlLower, table.toLowerCase())) {
                touchesScoped = true;
                break;
            }
        }
        if (!touchesScoped) {
            return;
        }

        guard().assertWritable(envId);
    }

    private EnvLockGuard guard() {
        EnvLockGuard g = cachedGuard;
        if (g == null) {
            synchronized (this) {
                if (cachedGuard == null) {
                    cachedGuard = applicationContext.getBean(EnvLockGuard.class);
                }
                g = cachedGuard;
            }
        }
        return g;
    }

    /**
     * Whole-word table match — avoids accidentally matching {@code ab_page_schema_history}
     * when looking for {@code ab_page_schema} (although both being env-scoped means either
     * match still correctly fires the guard, the precise check is cleaner).
     */
    static boolean matchesTable(String sql, String tableName) {
        if (tableName == null || tableName.isEmpty() || sql == null || sql.isEmpty()) {
            // Defensive: indexOf("", n) returns n, which would infinite-loop below.
            return false;
        }
        int idx = 0;
        while ((idx = sql.indexOf(tableName, idx)) >= 0) {
            int before = idx - 1;
            int after = idx + tableName.length();
            char beforeChar = before < 0 ? ' ' : sql.charAt(before);
            char afterChar = after >= sql.length() ? ' ' : sql.charAt(after);
            boolean leftOk = !Character.isLetterOrDigit(beforeChar) && beforeChar != '_';
            boolean rightOk = !Character.isLetterOrDigit(afterChar) && afterChar != '_';
            if (leftOk && rightOk) {
                return true;
            }
            idx = after;
        }
        return false;
    }
}
