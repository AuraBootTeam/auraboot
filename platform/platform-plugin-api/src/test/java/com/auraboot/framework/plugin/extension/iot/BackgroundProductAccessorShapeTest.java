package com.auraboot.framework.plugin.extension.iot;

import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.EventDef;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.ProductSchema;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.ProductView;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.PropertyDef;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.ServiceDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shape-only pin of the {@link BackgroundProductAccessor} SPI records
 * ({@link ProductView}, {@link ProductSchema}, {@link PropertyDef},
 * {@link EventDef}, {@link ServiceDef}), per testing-layering.md §2.1
 * (shape self-check exemption).
 *
 * Tenant-scoped product lookup and unknown-key behavior are persistence
 * semantics and are NOT asserted here; they are owed to real-stack tests
 * against {@code IotProductAccessorImpl} — tracked in workspace
 * {@code tools/fake-db-registry.tsv}.
 */
class BackgroundProductAccessorShapeTest {

    @Test
    void productViewCarriesIdentityNamingNodeTypeAndProvisioning() {
        ProductView view = new ProductView(
                "temp-product",
                Map.of("zh-CN", "温度产品", "en-US", "Temperature Product"),
                "DEVICE", "JSON", "MQTT", "ALLOW_CREATE", 100L);

        assertThat(view.productKey()).isEqualTo("temp-product");
        assertThat(view.name()).containsEntry("zh-CN", "温度产品").containsEntry("en-US", "Temperature Product");
        assertThat(view.nodeType()).isEqualTo("DEVICE");
        assertThat(view.dataFormat()).isEqualTo("JSON");
        assertThat(view.transportType()).isEqualTo("MQTT");
        assertThat(view.provisionType()).isEqualTo("ALLOW_CREATE");
        assertThat(view.tenantId()).isEqualTo(100L);
    }

    @Test
    void productViewConvenienceConstructorLeavesProvisionTypeNull() {
        ProductView legacy = new ProductView(
                "gw-product", Map.of("en-US", "Gateway Product"),
                "GATEWAY", "JSON", "MQTT", 200L);

        assertThat(legacy.provisionType()).isNull();
        assertThat(legacy.tenantId()).isEqualTo(200L);
    }

    @Test
    void productSchemaExposesPropertyEventServiceDefSurfaces() {
        ProductSchema schema = new ProductSchema(
                List.of(new PropertyDef("temperature", "float", true, "°C",
                        Map.of("min", -40.0, "max", 125.0))),
                List.of(new EventDef("over_temp", "struct", false, null,
                        Map.of("threshold", 90.0))),
                List.of(new ServiceDef("set_threshold", "struct", false, null, Map.of())));

        PropertyDef temp = schema.properties().get(0);
        assertThat(temp.identifier()).isEqualTo("temperature");
        assertThat(temp.dataType()).isEqualTo("float");
        assertThat(temp.required()).isTrue();
        assertThat(temp.unit()).isEqualTo("°C");
        assertThat(temp.range()).containsEntry("min", -40.0).containsEntry("max", 125.0);

        EventDef event = schema.events().get(0);
        assertThat(event.identifier()).isEqualTo("over_temp");
        assertThat(event.dataType()).isEqualTo("struct");
        assertThat(event.range()).containsEntry("threshold", 90.0);

        ServiceDef service = schema.services().get(0);
        assertThat(service.identifier()).isEqualTo("set_threshold");
        assertThat(service.range()).isEmpty();
    }

    @Test
    void productSchemaToleratesEmptyConceptLists() {
        ProductSchema bare = new ProductSchema(List.of(), List.of(), List.of());

        assertThat(bare.properties()).isEmpty();
        assertThat(bare.events()).isEmpty();
        assertThat(bare.services()).isEmpty();
    }
}
