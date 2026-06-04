package com.auraboot.framework.plugin.extension.iot;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Device-lookup bridge for plugin background components (IoT control plane,
 * EMQX hook listener, rule engine worker, alarm router) that need to resolve
 * a device by its business code or global {@code iotId} without coupling to
 * the platform-internal device service.
 *
 * <p>Follows the same null-fallback SPI pattern as
 * {@link com.auraboot.framework.plugin.extension.BackgroundConnectorCredentialAccessor}:
 * a plugin {@code @Autowired(required = false)} field defaults to {@code null}
 * on older platforms, and the plugin treats {@code null} as "feature unavailable".
 *
 * <p><b>Tenant isolation:</b> {@link #lookupByCode(long, String)} requires
 * an explicit {@code tenantId}, mirroring
 * {@link com.auraboot.framework.plugin.extension.BackgroundDataAccessor}.
 * {@link #lookupByIotId(String)} is tenant-agnostic because {@code iotId} is
 * globally unique; the returned {@link DeviceView#tenantId()} is authoritative
 * and callers MUST scope follow-up reads to it.
 *
 * @since 2.6.0
 */
public interface BackgroundDeviceAccessor {

    /**
     * Look up a device by tenant-scoped business code.
     *
     * @param tenantId   owning tenant id (must be {@code &gt; 0})
     * @param deviceCode tenant-unique device code (not blank)
     * @return device snapshot, or empty when not found in this tenant
     */
    Optional<DeviceView> lookupByCode(long tenantId, String deviceCode);

    /**
     * Look up a device by its global {@code iotId} (platform-issued, unique
     * across tenants — typically a ULID).
     *
     * @param iotId globally-unique device id (not blank)
     * @return device snapshot, or empty when no device matches
     */
    Optional<DeviceView> lookupByIotId(String iotId);

    /**
     * Immutable device snapshot. New fields will be appended via additional
     * record components in future minor versions; existing components are
     * stable.
     *
     * @param recordId    primary-key value ({@code pid} column, ULID) of the
     *                    underlying dynamic-data row; required when callers need
     *                    to update the row via
     *                    {@link com.auraboot.framework.meta.service.DynamicDataService#update}
     *                    (which resolves by primary key, not by business field)
     * @param iotId       global device id
     * @param deviceCode  tenant-scoped business code
     * @param productKey  product the device belongs to
     * @param tenantId    owning tenant
     * @param status      one of {@code UNACTIVE / ONLINE / OFFLINE / DISABLE}
     * @param aclPattern  MQTT topic ACL pattern (e.g. {@code /sys/${productKey}/${deviceCode}/#})
     * @param tags        free-form tag map; never null, may be empty
     * @param lastSeenAt  last heartbeat / publish timestamp; may be null when never seen
     */
    record DeviceView(
            String recordId,
            String iotId,
            String deviceCode,
            String productKey,
            long tenantId,
            String status,
            String aclPattern,
            Map<String, String> tags,
            Instant lastSeenAt) {
    }
}
