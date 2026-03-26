package com.auraboot.framework.exception;

import com.auraboot.framework.common.constant.ResponseCode;
import lombok.Getter;

/**
 * Exception thrown when a user lacks the required permission to perform an operation.
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Getter
public class PermissionDeniedException extends RootUnCheckedException {

    private String permissionCode;
    private String userMessage;

    public PermissionDeniedException(ResponseCode responseCode) {
        super(responseCode);
    }

    public PermissionDeniedException(ResponseCode responseCode, Object context) {
        super(responseCode, context);
    }

    public PermissionDeniedException(ResponseCode responseCode, String permissionCode, String userMessage) {
        super(responseCode);
        this.permissionCode = permissionCode;
        this.userMessage = userMessage;
    }
}
