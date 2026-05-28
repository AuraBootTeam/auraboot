package com.auraboot.framework.plugin.extension.iot;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Product / TSL-schema bridge for plugin background components that need to
 * resolve a product definition or reverse-look up its Thing Specification
 * Language (TSL) schema without coupling to the platform-internal product
 * service.
 *
 * <p>The TSL reverse-lookup ({@link #getSchema(String)}) is the runtime
 * counterpart of SDK v1 capability <em>#10 productSchema</em> — rule
 * engines, alarm formatters and decoders all need the property/event/service
 * shape to validate or render payloads.
 *
 * <p>Follows the same null-fallback SPI contract as the other
 * {@code Background*Accessor} interfaces: {@code @Autowired(required=false)}
 * returns {@code null} on older platforms; plugin code treats {@code null} as
 * "feature unavailable" and falls back to documented defaults.
 *
 * @since 2.6.0
 */
public interface BackgroundProductAccessor {

    /**
     * Look up a product header by key.
     *
     * @param tenantId   owning tenant id (must be {@code &gt; 0})
     * @param productKey tenant-unique product key (not blank)
     * @return product snapshot, or empty when not found in this tenant
     */
    Optional<ProductView> lookupByKey(long tenantId, String productKey);

    /**
     * Resolve a product's TSL schema (properties + events + services).
     *
     * <p>{@code productKey} is treated as globally unique within the
     * platform's product registry. The returned schema is read-only.
     *
     * @param productKey product key (not blank)
     * @return TSL schema, or empty when the product is not found or has no
     *         schema defined
     */
    Optional<ProductSchema> getSchema(String productKey);

    /**
     * Immutable product header.
     *
     * @param productKey      product key (tenant-unique)
     * @param name            i18n display name keyed by locale tag (e.g. {@code zh-CN}, {@code en-US});
     *                        never null, never empty
     * @param nodeType        one of {@code DEVICE / GATEWAY / SUBDEVICE}
     * @param dataFormat      one of {@code JSON / BINARY / CUSTOM}
     * @param transportType   one of {@code MQTT / COAP / HTTP / MODBUS}
     * @param tenantId        owning tenant
     */
    record ProductView(
            String productKey,
            Map<String, String> name,
            String nodeType,
            String dataFormat,
            String transportType,
            long tenantId) {
    }

    /**
     * Immutable TSL schema bundle. Property / event / service lists are
     * never null and may be empty for products that opt out of a given
     * concept.
     */
    record ProductSchema(
            List<PropertyDef> properties,
            List<EventDef> events,
            List<ServiceDef> services) {
    }

    /**
     * TSL property definition.
     *
     * @param identifier property identifier (unique within product)
     * @param dataType   TSL data type code: {@code int / float / double / bool / text / enum / struct / array / date}
     * @param required   whether the property is mandatory in upstream payloads
     * @param unit       physical unit symbol (e.g. {@code °C}, {@code %RH}); may be null
     * @param range      data-type-specific bounds; e.g. {@code {"min":0,"max":100}} for numeric,
     *                   {@code {"size":255}} for text, {@code {"items":["A","B"]}} for enum;
     *                   never null, may be empty
     */
    record PropertyDef(
            String identifier,
            String dataType,
            boolean required,
            String unit,
            Map<String, Object> range) {
    }

    /**
     * TSL event definition.
     *
     * @param identifier event identifier (unique within product)
     * @param dataType   payload data type code (see {@link PropertyDef#dataType()})
     * @param required   reserved; whether the event is mandatory in declared lifecycle
     * @param unit       optional unit symbol; may be null
     * @param range      payload schema bounds; never null, may be empty
     */
    record EventDef(
            String identifier,
            String dataType,
            boolean required,
            String unit,
            Map<String, Object> range) {
    }

    /**
     * TSL service (RPC) definition.
     *
     * @param identifier service identifier (unique within product)
     * @param dataType   reserved; service signatures may declare a wrapper
     *                   payload type
     * @param required   whether the service is part of the declared mandatory contract
     * @param unit       reserved
     * @param range      input + output parameter schema; never null, may be empty
     */
    record ServiceDef(
            String identifier,
            String dataType,
            boolean required,
            String unit,
            Map<String, Object> range) {
    }
}
