package com.auraboot.framework.organization.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request payloads for the department admin operations:
 * sort (batch reorder), set-commander, and the delete pre-check response.
 */
public final class DepartmentAdminRequests {

    private DepartmentAdminRequests() {
    }

    public record SortItem(
        @NotBlank String pid,
        int order
    ) {}

    public record SortRequest(
        @NotEmpty
        @Size(max = 200)
        @Valid
        List<SortItem> items
    ) {}

    public record SetCommanderRequest(
        @NotBlank String employeePid
    ) {}

    public record DeleteBlocker(
        String type,
        long count,
        String message
    ) {}

    public record DeleteCheckResponse(
        boolean canDelete,
        List<DeleteBlocker> blockers
    ) {}
}
