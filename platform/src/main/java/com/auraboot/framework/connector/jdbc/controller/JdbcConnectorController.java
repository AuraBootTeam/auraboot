package com.auraboot.framework.connector.jdbc.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.connector.jdbc.dto.JdbcConnectorCreateRequest;
import com.auraboot.framework.connector.jdbc.dto.JdbcEndpointCreateRequest;
import com.auraboot.framework.connector.jdbc.entity.JdbcConnector;
import com.auraboot.framework.connector.jdbc.entity.JdbcConnectorEndpoint;
import com.auraboot.framework.connector.jdbc.service.JdbcConnectorService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for JDBC connector management.
 *
 * <p>All endpoints require {@code sys.connector.update} permission.
 * Passwords are stripped from all outbound responses (never returned to API consumers).
 *
 * @since 5.2.0
 */
@RestController
@RequestMapping("/api/jdbc-connectors")
@RequirePermission(MetaPermission.SYS_CONNECTOR_MANAGE)
@RequiredArgsConstructor
public class JdbcConnectorController {

    private final JdbcConnectorService service;

    @PostMapping
    public ApiResponse<JdbcConnector> create(@Valid @RequestBody JdbcConnectorCreateRequest req) {
        return ApiResponse.success(maskPassword(service.create(req)));
    }

    @GetMapping
    public ApiResponse<List<JdbcConnector>> list() {
        return ApiResponse.success(service.listAll().stream().map(this::maskPassword).toList());
    }

    @GetMapping("/{pid}")
    public ApiResponse<JdbcConnector> get(@PathVariable String pid) {
        return ApiResponse.success(maskPassword(service.getByPid(pid)));
    }

    @PutMapping("/{pid}")
    public ApiResponse<JdbcConnector> update(@PathVariable String pid,
                                             @Valid @RequestBody JdbcConnectorCreateRequest req) {
        return ApiResponse.success(maskPassword(service.update(pid, req)));
    }

    @DeleteMapping("/{pid}")
    public ApiResponse<Void> delete(@PathVariable String pid) {
        service.delete(pid);
        return ApiResponse.success();
    }

    @PostMapping("/{pid}/endpoints")
    public ApiResponse<JdbcConnectorEndpoint> addEndpoint(@PathVariable String pid,
                                                          @Valid @RequestBody JdbcEndpointCreateRequest req) {
        return ApiResponse.success(service.addEndpoint(pid, req));
    }

    @GetMapping("/{pid}/endpoints")
    public ApiResponse<List<JdbcConnectorEndpoint>> listEndpoints(@PathVariable String pid) {
        return ApiResponse.success(service.listEndpoints(pid));
    }

    @PostMapping("/{pid}/invoke/{endpointCode}")
    public ApiResponse<Map<String, Object>> invoke(@PathVariable String pid,
                                                   @PathVariable String endpointCode,
                                                   @RequestBody(required = false) Map<String, Object> params) {
        return ApiResponse.success(service.invoke(pid, endpointCode, params == null ? Map.of() : params));
    }

    @PostMapping("/{pid}/test")
    public ApiResponse<Boolean> testConnection(@PathVariable String pid) {
        return ApiResponse.success(service.testConnection(pid));
    }

    /** Strip encrypted password before returning to API consumers (matches ApiConnector pattern). */
    private JdbcConnector maskPassword(JdbcConnector c) {
        if (c == null) return null;
        c.setPassword(null);
        return c;
    }
}
