package com.auraboot.framework.consistency.exception;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.consistency.dto.ConsistencyViolation;
import com.auraboot.framework.exception.RootUnCheckedException;
import lombok.Getter;

import java.util.List;

/**
 * Exception thrown when consistency rule violations are detected.
 */
public class ConsistencyViolationException extends RootUnCheckedException {

    @Getter
    private final List<ConsistencyViolation> violations;

    public ConsistencyViolationException(List<ConsistencyViolation> violations) {
        super(ResponseCode.CommonValidationFailed, buildMessage(violations));
        this.violations = violations;
    }

    private static String buildMessage(List<ConsistencyViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            return "Consistency validation failed";
        }
        StringBuilder sb = new StringBuilder("Consistency violations: ");
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(violations.get(i).getMessage());
        }
        return sb.toString();
    }
}
