package com.auraboot.framework.meta.cache;

import com.auraboot.framework.application.tenant.MetaContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 元数据缓存键生成器
 * 
 * 提供null安全的缓存键生成，解决MetaContext为空时的NPE问题。
 * 
 * 缓存键格式: {methodParams}_{tenantId}
 * 当MetaContext不存在时使用默认值: {methodParams}_no-context
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Component("metaCacheKeyGenerator")
public class MetaCacheKeyGenerator implements KeyGenerator {

    private static final String NO_CONTEXT = "no-context";
    private static final String DEFAULT_VALUE = "default";
    private static final String SEPARATOR = "_";

    @Override
    public Object generate(Object target, Method method, Object... params) {
        StringBuilder keyBuilder = new StringBuilder();
        
        // 添加方法名作为前缀（避免不同方法的缓存键冲突）
        keyBuilder.append(method.getName()).append(SEPARATOR);
        
        // 添加方法参数
        for (Object param : params) {
            if (param != null) {
                keyBuilder.append(param.toString());
            } else {
                keyBuilder.append("null");
            }
            keyBuilder.append(SEPARATOR);
        }
        
        // 添加租户上下文信息（null安全）
        appendTenantContext(keyBuilder);
        
        String key = keyBuilder.toString();
        log.debug("Generated cache key: {}", key);
        
        return key;
    }
    
    /**
     * 生成简单的缓存键（不包含方法名）
     * 
     * @param params 参数列表
     * @return 缓存键
     */
    public String generateSimpleKey(Object... params) {
        StringBuilder keyBuilder = new StringBuilder();
        
        // 添加参数
        for (Object param : params) {
            if (param != null) {
                keyBuilder.append(param.toString());
            } else {
                keyBuilder.append("null");
            }
            keyBuilder.append(SEPARATOR);
        }
        
        // 添加租户上下文信息
        appendTenantContext(keyBuilder);
        
        return keyBuilder.toString();
    }
    
    /**
     * 添加租户上下文到缓存键
     * 
     * @param keyBuilder 键构建器
     */
    private void appendTenantContext(StringBuilder keyBuilder) {
        if (MetaContext.exists()) {
            Long tenantId = MetaContext.getCurrentTenantId();

            
            keyBuilder.append(tenantId != null ? tenantId : DEFAULT_VALUE);


        } else {
            keyBuilder.append(NO_CONTEXT);
        }
    }
    
    /**
     * 获取当前租户上下文的缓存键后缀
     * 
     * @return 缓存键后缀
     */
    public static String getTenantContextSuffix() {
        if (MetaContext.exists()) {
            Long tenantId = MetaContext.getCurrentTenantId();

            
            return String.format("%s",
                tenantId != null ? tenantId : DEFAULT_VALUE
               );
        }
        return NO_CONTEXT;
    }
    
    /**
     * 检查MetaContext是否存在且有效
     * 
     * @return true如果MetaContext存在且包含有效的租户信息
     */
    public static boolean hasValidContext() {
        return MetaContext.exists() && MetaContext.getCurrentTenantId() != null;
    }
}
