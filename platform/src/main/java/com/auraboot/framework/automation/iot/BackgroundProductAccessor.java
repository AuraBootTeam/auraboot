package com.auraboot.framework.automation.iot;

import java.util.Map;

/**
 * Optional SPI implemented by the IoT plugin to surface product (device type)
 * metadata — e.g. thresholds, GB/T severity mapping, KKS prefix, telemetry
 * schema. See the sibling {@link BackgroundDeviceAccessor} for design notes.
 */
public interface BackgroundProductAccessor {

    /**
     * @param productId stable IoT product identifier
     * @return arbitrary metadata map; {@code null} or empty when the product
     *         is unknown
     */
    Map<String, Object> findProductMetadata(String productId);
}
