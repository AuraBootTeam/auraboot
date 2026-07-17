package com.auraboot.framework.observability.clienterror;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Front-end error reporting + read.
 *
 * <p>{@code POST} is open to any authenticated user (they report their own browser
 * errors; tenant/user are taken from the session, not the payload). {@code GET} is
 * gated by {@link MetaPermission#COMMAND_READ} — the same read permission the error
 * board uses — so only troubleshooters list them.
 */
@RestController
@RequestMapping("/api/client-errors")
@RequiredArgsConstructor
public class WebClientErrorController {

    private final WebClientErrorService webClientErrorService;

    @PostMapping
    public ApiResponse<Void> report(@RequestBody WebClientErrorRequest request) {
        webClientErrorService.record(request);
        return ApiResponse.success(null);
    }

    @GetMapping
    @RequirePermission(MetaPermission.COMMAND_READ)
    public ApiResponse<PaginationResult<WebClientError>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(webClientErrorService.page(pageNum, pageSize));
    }
}
