package com.auraboot.framework.exception;

import com.auraboot.framework.common.constant.ResponseCode;

/**
 * 验证异常类
 * 用于处理业务验证失败的情况
 * 
 * @author AuraBoot Team
 * @since 1.0.0
 */
public class ValidationException extends RootUnCheckedException {
    
    /**
     * 构造函数
     * 
     * @param responseCode 响应码
     */
    public ValidationException(ResponseCode responseCode) {
        super(responseCode);
    }
    
    /**
     * 构造函数
     * 
     * @param responseCode 响应码
     * @param context 上下文信息（可以是错误消息或异常）
     */
    public ValidationException(ResponseCode responseCode, Object context) {
        super(responseCode, context);
    }
}