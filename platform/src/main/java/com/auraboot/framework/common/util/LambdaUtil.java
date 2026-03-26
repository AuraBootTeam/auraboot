package com.auraboot.framework.common.util;

import com.auraboot.framework.exception.BusinessException;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;

public class LambdaUtil {
    public static <T> String getFieldName(SFunction<T, ?> function) {
        try {
            Method method = function.getClass().getDeclaredMethod("writeReplace");
            method.setAccessible(true);
            SerializedLambda serializedLambda = (SerializedLambda) method.invoke(function);
            String methodName = serializedLambda.getImplMethodName();
            
            if (methodName.startsWith("get")) {
                String fieldName = methodName.substring(3);
                return fieldName.substring(0, 1).toLowerCase() + fieldName.substring(1);
            }
            return methodName;
        } catch (Exception e) {
            throw new BusinessException("Failed to resolve lambda field name", e);
        }
    }
}