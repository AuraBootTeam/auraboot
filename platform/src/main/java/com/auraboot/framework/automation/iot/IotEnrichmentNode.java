package com.auraboot.framework.automation.iot;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * IoT rule node — merges device + product metadata onto the process variables
 * so downstream transformation / action nodes can reference fields like
 * {@code #deviceMeta['site']} or {@code #productMeta['model']}.
 *
 * <p>Looks resolution through optional accessor SPIs ({@link BackgroundDeviceAccessor},
 * {@link BackgroundProductAccessor}). Both are wired as {@link ObjectProvider}s so
 * the spike can run with no IoT plugin installed — accessor absent → enrichment
 * is a no-op for that dimension, downstream nodes still execute.
 *
 * <p>Honors {@link IotRuleContextKeys#DROPPED}: if the upstream filter dropped
 * the run, enrichment is skipped (defense in depth in case the rule omits the
 * gateway).
 *
 * <p>Action type code: {@code iot_enrichment}.
 */
@Slf4j
@Component
public class IotEnrichmentNode implements ActionExecutor {

    public static final String TYPE = "iot_enrichment";

    private final ObjectProvider<BackgroundDeviceAccessor> deviceAccessor;
    private final ObjectProvider<BackgroundProductAccessor> productAccessor;

    public IotEnrichmentNode(ObjectProvider<BackgroundDeviceAccessor> deviceAccessor,
                             ObjectProvider<BackgroundProductAccessor> productAccessor) {
        this.deviceAccessor = deviceAccessor;
        this.productAccessor = productAccessor;
    }

    @Override
    public boolean supports(String actionType) {
        return TYPE.equals(actionType);
    }

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        if (Boolean.TRUE.equals(context.get(IotRuleContextKeys.DROPPED))) {
            return Map.of("enriched", false, "reason", "dropped upstream");
        }
        Map<String, Object> config = action.getConfig() != null ? action.getConfig() : Map.of();
        boolean withDevice = !Boolean.FALSE.equals(config.get("includeDevice"));
        boolean withProduct = !Boolean.FALSE.equals(config.get("includeProduct"));

        Map<String, Object> result = new HashMap<>();

        if (withDevice) {
            Object deviceIdObj = context.get(IotRuleContextKeys.DEVICE_ID);
            BackgroundDeviceAccessor accessor = deviceAccessor.getIfAvailable();
            if (deviceIdObj != null && accessor != null) {
                Map<String, Object> meta = accessor.findDeviceMetadata(deviceIdObj.toString());
                if (meta != null && !meta.isEmpty()) {
                    context.put(IotRuleContextKeys.DEVICE_META, meta);
                    // Promote productId from device metadata if not yet bound — typical
                    // for telemetry that only carries deviceId on the wire.
                    if (!context.containsKey(IotRuleContextKeys.PRODUCT_ID)
                            && meta.containsKey("productId")) {
                        context.put(IotRuleContextKeys.PRODUCT_ID, meta.get("productId"));
                    }
                    result.put("device", true);
                }
            }
        }

        if (withProduct) {
            Object productIdObj = context.get(IotRuleContextKeys.PRODUCT_ID);
            BackgroundProductAccessor accessor = productAccessor.getIfAvailable();
            if (productIdObj != null && accessor != null) {
                Map<String, Object> meta = accessor.findProductMetadata(productIdObj.toString());
                if (meta != null && !meta.isEmpty()) {
                    context.put(IotRuleContextKeys.PRODUCT_META, meta);
                    result.put("product", true);
                }
            }
        }

        result.put("enriched", !result.isEmpty());
        return result;
    }
}
