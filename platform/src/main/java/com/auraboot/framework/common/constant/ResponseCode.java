package com.auraboot.framework.common.constant;

import lombok.Getter;

import java.io.Serializable;

/**
 * Unified response codes organized by category:
 * - 0: Success
 * - 1xxxx: Validation errors
 * - 2xxxx: Authentication & security
 * - 3xxxx: Authorization & resource
 * - 4xxxx: Business logic
 * - 9xxxx: System / infrastructure
 */
public enum ResponseCode implements Serializable {

    // ── Success ──
    OK("0", "OK"),

    // ── 9xxxx: System / infrastructure ──
    SystemError("1", "Internal system error"),
    UnreachableCodePathException("2", "Unreachable code path"),
    UnsupportedFeature("3", "Unsupported feature"),

    // ── 1xxxx: Validation ──
    CommonValidationFailed("10000", "Validation failed"),
    WrongEmailFormat("10001", "Invalid email format"),
    IdentifierAlreadyBeenTaken("10002", "Email or username already registered"),
    BadParam("35000", "Bad parameter"),

    // ── 2xxxx: Authentication & security ──
    InvalidUserNameOrPassword("20000", "Invalid username or password"),
    AccountLocked("20001", "Account locked, please try again later"),
    SecurityVersionMismatch("20002", "Security credentials changed, please re-login"),
    PasswordExpired("20003", "Password expired, please change your password"),
    PasswordTooWeak("20004", "Password too weak"),
    PasswordReused("20005", "Cannot reuse recent passwords"),
    MustChangePassword("20006", "Must change password before proceeding"),

    // ── 3xxxx: Authorization & resource ──
    UserNotLoginInOrAccessTokenInvalid("403", "User not logged in or token expired"),
    MissingAuthorizationHeader("40001", "Missing Authorization header"),
    ExpiredAuthorizationHeader("400", "Authorization header expired"),
    PermissionDenied("403", "Permission denied"),
    FORBIDDEN("403", "Access forbidden"),
    Unauthorized("Unauthorized", "Unauthorized"),
    NOT_FOUND("404", "Resource not found"),

    // ── 4xxxx: Business logic ──
    PageDefinitionCantBeEmpty("30000", "Page definition cannot be empty"),
    BUSINESS_ERROR("40000", "Business error"),

    PluginConflictDetected("40001","Plugin resource conflict detected"),
    PluginImportFailed("40002","Plugin import failed"),
    PluginNotFound("40003","Plugin import log not found");

    @Getter
    private final String code;

    @Getter
    private final String desc;

    ResponseCode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
