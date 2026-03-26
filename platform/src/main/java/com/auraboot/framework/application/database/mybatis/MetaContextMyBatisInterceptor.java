package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * MyBatis拦截器，自动注入MetaContext中的租户信息到SQL参数中
 * 拦截Executor的query和update方法，在SQL执行前注入tenantId参数
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query", 
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update", 
                args = {MappedStatement.class, Object.class})
})
public class MetaContextMyBatisInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 如果没有MetaContext，直接放行
        if (!MetaContext.exists()) {
            return invocation.proceed();
        }

        // 获取原始参数
        Object[] args = invocation.getArgs();
        MappedStatement ms = (MappedStatement) args[0];
        Object parameter = args[1];

        // 获取MetaContext信息
        MetaContext ctx = MetaContext.get();
        
        // 如果参数为null，创建一个新的Map
        if (parameter == null) {
            Map<String, Object> newParams = new HashMap<>();
            newParams.put("tenantId", ctx.getTenantId());

            args[1] = newParams;
        } 
        // 如果参数是Map，直接添加
        else if (parameter instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> paramMap = (Map<String, Object>) parameter;
            // 只在参数不存在时添加，避免覆盖显式传入的值
            paramMap.putIfAbsent("tenantId", ctx.getTenantId());

        } 
        // 如果是实体对象，尝试通过反射设置字段
        else {
            try {
                // 尝试设置tenantId字段
                setFieldIfExists(parameter, "tenantId", ctx.getTenantId());

            } catch (Exception e) {
                // 如果反射失败，创建包装Map
                Map<String, Object> newParams = new HashMap<>();
                
                // 如果是MyBatis的ParamMap，保留所有原有的键值对
                if (parameter.getClass().getName().contains("ParamMap")) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> originalMap = (Map<String, Object>) parameter;
                        newParams.putAll(originalMap);
                    } catch (Exception ex) {
                        // 如果转换失败，将原参数作为param1
                        newParams.put("param1", parameter);
                    }
                } else {
                    // 将原参数作为param1
                    newParams.put("param1", parameter);
                }
                
                // 添加上下文参数
                newParams.putIfAbsent("tenantId", ctx.getTenantId());

                args[1] = newParams;
            }
        }

        return invocation.proceed();
    }
    
    /**
     * 通过反射设置对象字段值
     */
    private void setFieldIfExists(Object obj, String fieldName, Object value) {
        if (obj == null || value == null) {
            return;
        }
        
        try {
            Class<?> clazz = obj.getClass();
            Field field = findField(clazz, fieldName);
            if (field != null) {
                field.setAccessible(true);
                // 只在字段值为null时设置，避免覆盖已有值
                Object currentValue = field.get(obj);
                if (currentValue == null) {
                    field.set(obj, value);
                }
            }
        } catch (Exception e) {
            // 忽略反射异常，让调用方处理
            throw new BusinessException("Failed to set field " + fieldName, e);
        }
    }
    
    /**
     * 递归查找字段，包括父类
     */
    private Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {}
}
