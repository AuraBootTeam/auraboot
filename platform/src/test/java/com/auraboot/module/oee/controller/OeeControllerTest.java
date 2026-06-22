package com.auraboot.module.oee.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.module.oee.dto.OeeEquipmentRef;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;
import com.auraboot.module.oee.engine.OeeCalculationEngine;
import com.auraboot.module.oee.port.OeeDataQueryPort;
import com.auraboot.module.oee.service.OeeFleetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc-level contract test for the OEE user/API entry points. Security and permission
 * interception are covered by the permission module; this verifies routing, ApiResponse envelope,
 * dashboard-friendly records shape, and telemetry-backed data values.
 */
class OeeControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        OeeCalculationEngine engine = new OeeCalculationEngine();
        OeeDataQueryPort port = new OeeDataQueryPort() {
            @Override
            public OeeInputs fetch(OeeRequest request) {
                if ("eq-idle".equals(request.getEquipmentId())) {
                    return OeeInputs.builder()
                        .calendarHours(BigDecimal.ZERO)
                        .downtimes(List.of())
                        .actualQty(BigDecimal.ZERO)
                        .defectQty(BigDecimal.ZERO)
                        .capacityPerHour(new BigDecimal("100"))
                        .build();
                }
                return telemetryEqInputs();
            }

            @Override
            public List<OeeEquipmentRef> listEquipment(Long tenantId) {
                return List.of(
                    OeeEquipmentRef.builder().equipmentId("eq-telemetry").code("EQ-T").name("Telemetry Line").build(),
                    OeeEquipmentRef.builder().equipmentId("eq-idle").code("EQ-Z").name("Idle Line").build());
            }
        };
        OeeFleetService fleetService = new OeeFleetService(engine, port);
        mvc = MockMvcBuilders.standaloneSetup(new OeeController(engine, port, fleetService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
        MetaContext.setContext(1L, 100L, "oee-user-pid", "oee-user");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void equipmentEndpoint_returnsTelemetryBackedRatesAndLosses() throws Exception {
        JsonNode root = getJson("/api/manufacturing/oee/equipment/eq-telemetry");

        assertEquals("0", root.get("code").asText());
        assertDecimal("0.750000", root.at("/data/availability"));
        assertDecimal("0.900000", root.at("/data/performance"));
        assertDecimal("0.950000", root.at("/data/quality"));
        assertDecimal("0.641250", root.at("/data/oee"));
        assertDecimal("1", root.at("/data/losses/breakdownHours"));
        assertDecimal("2", root.at("/data/losses/setupHours"));
        assertDecimal("60", root.at("/data/losses/speedLossUnits"));
        assertDecimal("27", root.at("/data/losses/processDefectUnits"));
    }

    @Test
    void fleetEndpoint_returnsRecordsShapeForDashboardBinding() throws Exception {
        JsonNode root = getJson("/api/manufacturing/oee/fleet");

        assertEquals("0", root.get("code").asText());
        JsonNode records = root.at("/data/records");
        assertTrue(records.isArray(), "fleet endpoint must expose data.records for dashboard api dataSource");
        assertEquals(2, records.size());
        assertEquals("Telemetry Line", records.get(0).get("name").asText());
        assertDecimal("75.0", records.get(0).get("availabilityPct"));
        assertDecimal("90.0", records.get(0).get("performancePct"));
        assertDecimal("95.0", records.get(0).get("qualityPct"));
        assertDecimal("64.1", records.get(0).get("oeePct"));
        assertDecimal("0.0", records.get(1).get("oeePct"));
    }

    @Test
    void fleetSummaryEndpoint_returnsSingleSummaryRecordForKpiCards() throws Exception {
        JsonNode root = getJson("/api/manufacturing/oee/fleet/summary");

        assertEquals("0", root.get("code").asText());
        JsonNode records = root.at("/data/records");
        assertTrue(records.isArray(), "summary endpoint must expose a single data.records row");
        assertEquals(1, records.size());
        assertEquals(2, records.get(0).get("equipmentCount").asInt());
        assertEquals(1, records.get(0).get("equipmentWithDataCount").asInt());
        assertDecimal("64.1", records.get(0).get("oeePct"));
        assertDecimal("1", records.get(0).get("breakdownHours"));
        assertDecimal("2", records.get(0).get("setupHours"));
    }

    @Test
    void invalidWindow_returnsApiErrorInsteadOfSilentEmptyData() throws Exception {
        MvcResult result = mvc.perform(get("/api/manufacturing/oee/fleet")
                .param("start", "2026-06-02T00:00:00")
                .param("end", "2026-06-01T00:00:00")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertNotEquals("0", root.get("code").asText());
        assertEquals("Window start must be before window end", root.get("message").asText());
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)
                .param("start", "2026-06-01T00:00:00")
                .param("end", "2026-06-02T00:00:00")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private OeeInputs telemetryEqInputs() {
        return OeeInputs.builder()
            .calendarHours(new BigDecimal("10"))
            .downtimes(List.of(
                OeeInputs.Downtime.builder().type("planned").hours(new BigDecimal("2")).build(),
                OeeInputs.Downtime.builder().type("breakdown").hours(new BigDecimal("1")).build()))
            .actualQty(new BigDecimal("999"))
            .defectQty(new BigDecimal("999"))
            .capacityPerHour(new BigDecimal("100"))
            .telemetryOperatingHours(new BigDecimal("6"))
            .telemetryOutputQty(new BigDecimal("540"))
            .telemetryGoodQty(new BigDecimal("513"))
            .build();
    }

    private void assertDecimal(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()),
            "expected " + expected + ", got " + actual);
    }
}
