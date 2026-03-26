package com.auraboot.framework.notification.routing;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.event.AuraEvent;
import com.auraboot.framework.meta.service.WatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Default recipient resolver supporting OPERATOR, RECORD_OWNER, and WATCHERS strategies.
 *
 * <ul>
 *   <li><b>OPERATOR</b> — resolves to the current user from {@link MetaContext}</li>
 *   <li><b>RECORD_OWNER</b> — extracts {@code created_by} from the event payload</li>
 *   <li><b>WATCHERS</b> — resolves all users watching the record via {@link WatchService}</li>
 * </ul>
 *
 * For strategies other than WATCHERS, watchers are still appended as additional recipients
 * when a modelCode and recordId are present on the event (union behavior).
 *
 * @since 6.0.0
 */
@Slf4j
@Component
public class DefaultRecipientResolver implements RecipientResolver {

    @Autowired(required = false)
    private WatchService watchService;

    @Override
    public List<Long> resolve(AuraEvent event, String strategy, String strategyConfig) {
        if (strategy == null) {
            strategy = "operator";
        }

        List<Long> baseRecipients = switch (strategy.toLowerCase()) {
            case "operator" -> resolveOperator(event);
            case "record_owner" -> resolveRecordOwner(event);
            case "watchers" -> resolveWatchers(event);
            default -> {
                log.warn("Unknown recipient strategy: {}", strategy);
                yield List.of();
            }
        };

        // For non-watcher strategies, merge in watchers as additional recipients
        if (!"watchers".equalsIgnoreCase(strategy)) {
            List<Long> watchers = resolveWatchers(event);
            if (!watchers.isEmpty()) {
                Set<Long> merged = new LinkedHashSet<>(baseRecipients);
                merged.addAll(watchers);
                return new ArrayList<>(merged);
            }
        }

        return baseRecipients;
    }

    private List<Long> resolveOperator(AuraEvent event) {
        try {
            if (!MetaContext.exists()) {
                return List.of();
            }
            Long userId = MetaContext.getCurrentUserId();
            return userId != null ? List.of(userId) : List.of();
        } catch (Exception e) {
            log.debug("Failed to resolve OPERATOR recipient: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Long> resolveRecordOwner(AuraEvent event) {
        Object createdBy = event.getPayload().get("created_by");
        if (createdBy instanceof Number n) {
            return List.of(n.longValue());
        }
        if (createdBy instanceof String s) {
            try {
                return List.of(Long.parseLong(s));
            } catch (NumberFormatException e) {
                log.debug("Cannot parse created_by as Long: {}", s);
                return List.of();
            }
        }
        return List.of();
    }

    /**
     * Resolve watchers for the record referenced by the event.
     * Returns empty list if WatchService is unavailable or event lacks model/record context.
     */
    private List<Long> resolveWatchers(AuraEvent event) {
        if (watchService == null) {
            return List.of();
        }
        String modelCode = event.getModelCode();
        String recordId = event.getRecordId();
        if (modelCode == null || modelCode.isBlank() || recordId == null || recordId.isBlank()) {
            return List.of();
        }
        try {
            Long recordIdLong = Long.parseLong(recordId);
            return watchService.getWatchers(modelCode, recordIdLong);
        } catch (NumberFormatException e) {
            log.debug("Cannot parse recordId as Long for watcher resolution: {}", recordId);
            return List.of();
        } catch (Exception e) {
            log.warn("Failed to resolve WATCHERS recipients: {}", e.getMessage());
            return List.of();
        }
    }
}
