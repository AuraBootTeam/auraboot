package com.auraboot.framework.common.dto;

import com.auraboot.framework.common.constant.ResponseCode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;

@Data
public class ApiResponse<T> implements Serializable {

    /** 唯一真理源 */
    private String code;

    /** 面向用户的消息 / i18n key */
    private String message;

    /** 成功时的业务数据 */
    private T data;

    /** 错误上下文（结构化） */
    private Object context;

    private long timestamp;

    @JsonIgnore
    public boolean isSuccess() {
        return ResponseCode.OK.getCode().equals(this.code);
    }

    private ApiResponse() {}

    public static <T> ApiResponse<T> success(String msg, T data) {
        return of(ResponseCode.OK, msg, data, null);
    }

    public static <T> ApiResponse<T> success(T data) {
        return of(ResponseCode.OK, data, null);
    }

    public static ApiResponse<Void> success() {
        return of(ResponseCode.OK, null, null);
    }


    public static <T> ApiResponse<T> ok(T data) {
        return of(ResponseCode.OK, data, null);
    }

    public static ApiResponse<Void> ok() {
        return of(ResponseCode.OK, null, null);
    }


    public static <T> ApiResponse<T> errorWithContext(ResponseCode code,T context) {
        return of(code, null, context);
    }

    public static ApiResponse<Void> error(ResponseCode code, Object context) {
        return of(code, null, context);
    }

    public static <T> ApiResponse<T> error(ResponseCode code, String message, T context) {
        return of(code, message, null, context);
    }

    public static <T> ApiResponse<T> error(String message) {
        return of(ResponseCode.SystemError, message, null, null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return of(String.valueOf(code), message, null, null);
    }

    public static <T> ApiResponse<T> error(int code, String message, T context) {
        return of(String.valueOf(code), message, null, context);
    }

    public static <T> ApiResponse<T> failure(String context) {
        return of(ResponseCode.SystemError, null, context);
    }


    private static <T> ApiResponse<T> of(
            ResponseCode code,
            T data,
            Object context
    ) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code.getCode();
        r.message = code.getDesc();
        r.data = data;
        r.context = context;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    private static <T> ApiResponse<T> of(
            ResponseCode code,
            String message,
            T data,
            Object context
    ) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code.getCode();
        r.message = message != null ? message : code.getDesc();
        r.data = data;
        r.context = context;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    private static <T> ApiResponse<T> of(
            String code,
            String message,
            T data,
            Object context
    ) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.message = message;
        r.data = data;
        r.context = context;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

}
