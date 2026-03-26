package com.auraboot.framework.datasync;

import com.auraboot.framework.application.tenant.MetaContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * REST endpoint for data sync subscription management.
 * Frontend calls subscribe after SSE connection established.
 */
@Slf4j
@RestController
@RequestMapping("/api/data-sync")
@RequiredArgsConstructor
public class DataSyncController {

    private final DataSyncSseRegistry sseRegistry;

    @PostMapping("/subscribe")
    public void subscribe(@RequestBody SubscribeRequest request) {
        // Permission filtering could be added here in the future
        sseRegistry.subscribe(request.getConnectionId(), request.getModelCodes());
        log.debug("DataSync: connection {} subscribed to {}", request.getConnectionId(), request.getModelCodes());
    }

    @Data
    public static class SubscribeRequest {
        private Long connectionId;
        private Set<String> modelCodes;
    }
}
