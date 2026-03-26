package com.auraboot.framework.connector.controller;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.connector.dto.ApiConnectorCreateRequest;
import com.auraboot.framework.connector.entity.ApiConnector;
import com.auraboot.framework.connector.service.ApiConnectorService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST controller for API connector management.
 *
 * @since 5.1.0
 */
@RestController
@RequestMapping("/api/connectors")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.SYS_CONNECTOR_MANAGE)
@Tag(name = "API Connectors", description = "External REST API connector management — create, test, and invoke connectors")
public class ApiConnectorController {

    private static final Set<String> SENSITIVE_AUTH_FIELDS =
            Set.of("apiKey", "token", "password", "secret", "accessToken", "secretKey");

    private final ApiConnectorService connectorService;
    private final FieldEncryptionService fieldEncryptionService;

    @PostMapping
    public ApiResponse<ApiConnector> create(@Valid @RequestBody ApiConnectorCreateRequest request) {
        return ApiResponse.success(maskConnector(connectorService.create(request)));
    }

    @GetMapping
    public ApiResponse<List<ApiConnector>> list() {
        return ApiResponse.success(connectorService.listAll().stream()
                .map(this::maskConnector).toList());
    }

    @GetMapping("/{pid}")
    public ApiResponse<ApiConnector> getByPid(@PathVariable String pid) {
        ApiConnector connector = connectorService.getByPid(pid);
        if (connector == null) {
            return ApiResponse.error("Connector not found: " + pid);
        }
        return ApiResponse.success(maskConnector(connector));
    }

    @PutMapping("/{pid}")
    public ApiResponse<ApiConnector> update(@PathVariable String pid,
                                             @Valid @RequestBody ApiConnectorCreateRequest request) {
        return ApiResponse.success(maskConnector(connectorService.update(pid, request)));
    }

    @DeleteMapping("/{pid}")
    public ApiResponse<Void> delete(@PathVariable String pid) {
        connectorService.delete(pid);
        return ApiResponse.success();
    }

    /**
     * Invoke an endpoint on a connector.
     * POST /api/connectors/{pid}/invoke/{endpointCode}
     */
    @PostMapping("/{pid}/invoke/{endpointCode}")
    public ApiResponse<Map<String, Object>> invoke(@PathVariable String pid,
                                                    @PathVariable String endpointCode,
                                                    @RequestBody Map<String, Object> params) {
        Map<String, Object> result = connectorService.invoke(pid, endpointCode, params);
        return ApiResponse.success(result);
    }

    /**
     * Test connectivity.
     * POST /api/connectors/{pid}/test
     */
    @PostMapping("/{pid}/test")
    @Operation(summary = "Test connector connectivity", description = "Sends a HEAD request to the connector's base URL to verify it is reachable.")
    public ApiResponse<Void> testConnection(@PathVariable String pid) {
        boolean success = connectorService.testConnection(pid);
        if (!success) {
            return ApiResponse.error("Connection test failed");
        }
        return ApiResponse.success();
    }

    private ApiConnector maskConnector(ApiConnector connector) {
        if (connector != null && connector.getAuthConfig() != null) {
            connector.setAuthConfig(
                    fieldEncryptionService.maskJsonFields(connector.getAuthConfig(), SENSITIVE_AUTH_FIELDS));
        }
        return connector;
    }
}
