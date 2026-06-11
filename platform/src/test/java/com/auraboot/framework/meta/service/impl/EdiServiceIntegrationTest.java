package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.EdiMessageType;
import com.auraboot.framework.meta.entity.EdiPartner;
import com.auraboot.framework.meta.entity.EdiTransaction;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.EdiTransactionMapper;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack integration test for {@link EdiService}.
 *
 * <p>Part of OSS coverage initiative #8/#9 (tracker:
 * {@code docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md}). {@code EdiService}
 * was a near-zero (0.3%) class in {@code meta/service/impl}; this exercises the real
 * service against the real shared database (no mocked mappers/bridges, per AGENTS.md
 * §2.2 seam discipline) covering: partner CRUD, message-type CRUD, outbound/inbound
 * message processing, the five protocol parsers, model field mapping, transaction
 * history and the retry state machine.
 *
 * <p>Uses the {@code integration-test} profile (shared Postgres on :5432). All data is
 * created under a dedicated {@code covedi-test-tenant} and hard-deleted in
 * {@link #tearDown()} (the entities are {@code @TableLogic} soft-delete, so cleanup uses
 * raw SQL) to keep the shared DB clean.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("EdiService Real-Stack Integration Test")
class EdiServiceIntegrationTest {

    private static final String CODE_PREFIX = "covedi";
    /** Stable per-class-run nonce so codes are unique across re-runs (alnum only). */
    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    private EdiService ediService;
    @Autowired
    private EdiTransactionMapper transactionMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantMemberService tenantMemberService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger seq = new AtomicInteger();
    private User testUser;
    private Tenant testTenant;

    private String uniqueCode(String label) {
        return CODE_PREFIX + RUN + "_" + seq.incrementAndGet() + "_" + label;
    }

    @BeforeEach
    void setUp() {
        String testEmail = "covedi-test@auraboot.com";
        testUser = userService.findByEmail(testEmail);
        if (testUser == null) {
            testUser = userService.signUp(testEmail, "test-password-123");
        }

        String testTenantName = "covedi-test-tenant";
        testTenant = tenantService.findByName(testTenantName);
        if (testTenant == null) {
            Tenant tenant = new Tenant();
            tenant.setPid(UniqueIdGenerator.generate());
            tenant.setName(testTenantName);
            tenant.setDisplayName("EDI Coverage Test Tenant");
            tenant.setStatus("active");
            tenant.setContactEmail("admin@covedi-test.com");
            tenant.setDescription("Test tenant for EDI-domain coverage IT");
            tenant.setDeletedFlag(false);
            tenant.setCreatedAt(Instant.now());
            tenant.setUpdatedAt(Instant.now());
            testTenant = tenantService.createTenant(tenant);
        }

        TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }

        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
        wipeTenantEdiData();
    }

    @AfterEach
    void tearDown() {
        try {
            wipeTenantEdiData();
        } catch (Exception e) {
            log.warn("EDI cleanup failed: {}", e.getMessage());
        } finally {
            MetaContext.clear();
        }
    }

    /** Hard-delete (bypassing the {@code @TableLogic} soft delete) all EDI rows for the dedicated test tenant. */
    private void wipeTenantEdiData() {
        Long tid = testTenant.getId();
        jdbcTemplate.update("DELETE FROM ab_edi_transaction WHERE tenant_id = ?", tid);
        jdbcTemplate.update("DELETE FROM ab_edi_message_type WHERE tenant_id = ?", tid);
        jdbcTemplate.update("DELETE FROM ab_edi_partner WHERE tenant_id = ?", tid);
    }

    // ---------- factory helpers ----------

    private EdiPartner newPartner(String code, String type, String protocol, boolean enabled) {
        EdiPartner p = new EdiPartner();
        p.setPartnerCode(code);
        p.setPartnerName("Name-" + code);
        p.setPartnerType(type);
        p.setProtocol(protocol);
        p.setEnabled(enabled);
        return p;
    }

    private EdiPartner createPartner(String type, String protocol, boolean enabled) {
        return ediService.createPartner(newPartner(uniqueCode("p"), type, protocol, enabled));
    }

    private EdiMessageType newType(String code, String direction, String protocol, String mappingTemplate) {
        EdiMessageType t = new EdiMessageType();
        t.setTypeCode(code);
        t.setTypeName("Type-" + code);
        t.setDirection(direction);
        t.setProtocol(protocol);
        t.setEnabled(true);
        t.setModelCode("pe_purchase_order");
        t.setMappingTemplate(mappingTemplate);
        return t;
    }

    private EdiMessageType createType(String direction, String protocol, String mappingTemplate) {
        return ediService.createMessageType(newType(uniqueCode("t"), direction, protocol, mappingTemplate));
    }

    // ==================== Partner CRUD ====================

    @Test
    @DisplayName("createPartner persists and is retrievable by id and code")
    void createAndFindPartner() {
        EdiPartner created = createPartner("customer", "json_api", true);
        assertNotNull(created.getId());
        assertEquals(testTenant.getId(), created.getTenantId());

        EdiPartner byId = ediService.getPartner(created.getId());
        assertEquals(created.getPartnerCode(), byId.getPartnerCode());

        EdiPartner byCode = ediService.getPartnerByCode(created.getPartnerCode());
        assertEquals(created.getId(), byCode.getId());

        assertFalse(ediService.listPartners().isEmpty());
    }

    @Test
    @DisplayName("createPartner rejects duplicate code")
    void createPartnerDuplicate() {
        EdiPartner first = createPartner("supplier", "edi_x12", true);
        EdiPartner dup = newPartner(first.getPartnerCode(), "customer", "json_api", true);
        assertThrows(MetaServiceException.class, () -> ediService.createPartner(dup));
    }

    @Test
    @DisplayName("createPartner rejects invalid partner type and protocol")
    void createPartnerValidation() {
        EdiPartner badType = newPartner(uniqueCode("bt"), "vendor", "json_api", true);
        assertThrows(MetaServiceException.class, () -> ediService.createPartner(badType));

        EdiPartner badProto = newPartner(uniqueCode("bp"), "customer", "ftp", true);
        assertThrows(MetaServiceException.class, () -> ediService.createPartner(badProto));
    }

    @Test
    @DisplayName("getPartner / getPartnerByCode throw when not found")
    void getPartnerNotFound() {
        assertThrows(MetaServiceException.class, () -> ediService.getPartner(-999999L));
        assertThrows(MetaServiceException.class, () -> ediService.getPartnerByCode("no-such-code"));
    }

    @Test
    @DisplayName("updatePartner mutates every updatable field")
    void updatePartner() {
        EdiPartner created = createPartner("customer", "json_api", true);

        EdiPartner updates = new EdiPartner();
        updates.setPartnerName("Renamed");
        updates.setPartnerType("supplier");
        updates.setProtocol("edifact");
        updates.setEndpointUrl("https://partner.example.com/edi");
        updates.setAuthType("basic");
        updates.setAuthConfig("{\"user\":\"u\"}");
        updates.setSenderId("SENDER1");
        updates.setReceiverId("RECV1");
        updates.setEnabled(false);

        ediService.updatePartner(created.getId(), updates);

        EdiPartner reloaded = ediService.getPartner(created.getId());
        assertEquals("Renamed", reloaded.getPartnerName());
        assertEquals("supplier", reloaded.getPartnerType());
        assertEquals("edifact", reloaded.getProtocol());
        assertEquals("https://partner.example.com/edi", reloaded.getEndpointUrl());
        assertEquals("basic", reloaded.getAuthType());
        assertEquals("SENDER1", reloaded.getSenderId());
        assertEquals("RECV1", reloaded.getReceiverId());
        assertFalse(reloaded.getEnabled());
    }

    @Test
    @DisplayName("updatePartner rejects invalid type/protocol")
    void updatePartnerValidation() {
        EdiPartner created = createPartner("customer", "json_api", true);

        EdiPartner badType = new EdiPartner();
        badType.setPartnerType("nope");
        assertThrows(MetaServiceException.class, () -> ediService.updatePartner(created.getId(), badType));

        EdiPartner badProto = new EdiPartner();
        badProto.setProtocol("nope");
        assertThrows(MetaServiceException.class, () -> ediService.updatePartner(created.getId(), badProto));
    }

    @Test
    @DisplayName("deletePartner soft-deletes (no longer retrievable)")
    void deletePartner() {
        EdiPartner created = createPartner("customer", "json_api", true);
        ediService.deletePartner(created.getId());
        assertThrows(MetaServiceException.class, () -> ediService.getPartner(created.getId()));
    }

    // ==================== Message Type CRUD ====================

    @Test
    @DisplayName("createMessageType persists and is retrievable by id and code")
    void createAndFindMessageType() {
        EdiMessageType created = createType("outbound", "json_api", null);
        assertNotNull(created.getId());
        assertEquals(testTenant.getId(), created.getTenantId());

        EdiMessageType byId = ediService.getMessageType(created.getId());
        assertEquals(created.getTypeCode(), byId.getTypeCode());

        EdiMessageType byCode = ediService.getMessageTypeByCode(created.getTypeCode());
        assertEquals(created.getId(), byCode.getId());

        assertFalse(ediService.listMessageTypes().isEmpty());
    }

    @Test
    @DisplayName("createMessageType rejects duplicate code")
    void createMessageTypeDuplicate() {
        EdiMessageType first = createType("inbound", "cxml", null);
        EdiMessageType dup = newType(first.getTypeCode(), "outbound", "json_api", null);
        assertThrows(MetaServiceException.class, () -> ediService.createMessageType(dup));
    }

    @Test
    @DisplayName("createMessageType rejects invalid direction and protocol")
    void createMessageTypeValidation() {
        EdiMessageType badDir = newType(uniqueCode("bd"), "sideways", "json_api", null);
        assertThrows(MetaServiceException.class, () -> ediService.createMessageType(badDir));

        EdiMessageType badProto = newType(uniqueCode("bpr"), "inbound", "smtp", null);
        assertThrows(MetaServiceException.class, () -> ediService.createMessageType(badProto));
    }

    @Test
    @DisplayName("getMessageType / getMessageTypeByCode throw when not found")
    void getMessageTypeNotFound() {
        assertThrows(MetaServiceException.class, () -> ediService.getMessageType(-999999L));
        assertThrows(MetaServiceException.class, () -> ediService.getMessageTypeByCode("no-such-type"));
    }

    @Test
    @DisplayName("updateMessageType mutates every updatable field")
    void updateMessageType() {
        EdiMessageType created = createType("inbound", "json_api", null);

        EdiMessageType updates = new EdiMessageType();
        updates.setTypeName("Renamed Type");
        updates.setDirection("outbound");
        updates.setProtocol("edi_x12");
        updates.setModelCode("pe_shipment");
        updates.setMappingTemplate("{\"po\":\"pe_po\"}");
        updates.setXsltTemplate("<xsl/>");
        updates.setValidationRules("{\"required\":[\"po\"]}");
        updates.setEnabled(false);

        ediService.updateMessageType(created.getId(), updates);

        EdiMessageType reloaded = ediService.getMessageType(created.getId());
        assertEquals("Renamed Type", reloaded.getTypeName());
        assertEquals("outbound", reloaded.getDirection());
        assertEquals("edi_x12", reloaded.getProtocol());
        assertEquals("pe_shipment", reloaded.getModelCode());
        // jsonb storage normalizes whitespace ("{\"po\": \"pe_po\"}"), so compare space-insensitively
        assertEquals("{\"po\":\"pe_po\"}", reloaded.getMappingTemplate().replace(" ", ""));
        assertEquals("<xsl/>", reloaded.getXsltTemplate());
        assertFalse(reloaded.getEnabled());
    }

    @Test
    @DisplayName("updateMessageType rejects invalid direction/protocol")
    void updateMessageTypeValidation() {
        EdiMessageType created = createType("inbound", "json_api", null);

        EdiMessageType badDir = new EdiMessageType();
        badDir.setDirection("nope");
        assertThrows(MetaServiceException.class, () -> ediService.updateMessageType(created.getId(), badDir));

        EdiMessageType badProto = new EdiMessageType();
        badProto.setProtocol("nope");
        assertThrows(MetaServiceException.class, () -> ediService.updateMessageType(created.getId(), badProto));
    }

    @Test
    @DisplayName("deleteMessageType soft-deletes (no longer retrievable)")
    void deleteMessageType() {
        EdiMessageType created = createType("inbound", "json_api", null);
        ediService.deleteMessageType(created.getId());
        assertThrows(MetaServiceException.class, () -> ediService.getMessageType(created.getId()));
    }

    // ==================== Outbound (sendMessage) ====================

    @Test
    @DisplayName("sendMessage serializes via mapping template and completes the transaction")
    void sendMessageHappy() {
        EdiPartner partner = createPartner("customer", "json_api", true);
        // mapping: edi field -> model field
        EdiMessageType type = createType("outbound", "json_api", "{\"po_number\":\"pe_po\",\"qty\":\"pe_qty\",\"urgent\":\"pe_urgent\"}");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pe_po", "PO-1001");
        data.put("pe_qty", 42);
        data.put("pe_urgent", true);

        EdiTransaction txn = ediService.sendMessage(partner.getId(), type.getTypeCode(), data);

        assertNotNull(txn.getTransactionNo());
        assertEquals("outbound", txn.getDirection());
        assertEquals(StatusConstants.COMPLETED, txn.getStatus());
        assertNotNull(txn.getProcessedAt());
        // serialized content uses the reverse-mapped EDI field names
        assertTrue(txn.getRawContent().contains("po_number"));
        assertTrue(txn.getRawContent().contains("PO-1001"));

        EdiTransaction reloaded = ediService.getTransaction(txn.getTransactionNo());
        assertEquals(StatusConstants.COMPLETED, reloaded.getStatus());
    }

    @Test
    @DisplayName("sendMessage without mapping template serializes raw data")
    void sendMessageNoTemplate() {
        EdiPartner partner = createPartner("customer", "json_api", true);
        EdiMessageType type = createType("outbound", "json_api", null);

        Map<String, Object> data = Map.of("pe_po", "PO-2002");
        EdiTransaction txn = ediService.sendMessage(partner.getId(), type.getTypeCode(), data);

        assertEquals(StatusConstants.COMPLETED, txn.getStatus());
        assertTrue(txn.getRawContent().contains("pe_po"));
        assertTrue(txn.getRawContent().contains("PO-2002"));
    }

    @Test
    @DisplayName("sendMessage rejects a disabled partner")
    void sendMessageDisabledPartner() {
        EdiPartner partner = createPartner("customer", "json_api", false);
        EdiMessageType type = createType("outbound", "json_api", null);
        assertThrows(MetaServiceException.class,
                () -> ediService.sendMessage(partner.getId(), type.getTypeCode(), Map.of("a", "b")));
    }

    @Test
    @DisplayName("sendMessage rejects a non-outbound message type")
    void sendMessageWrongDirection() {
        EdiPartner partner = createPartner("customer", "json_api", true);
        EdiMessageType type = createType("inbound", "json_api", null);
        assertThrows(MetaServiceException.class,
                () -> ediService.sendMessage(partner.getId(), type.getTypeCode(), Map.of("a", "b")));
    }

    @Test
    @DisplayName("sendMessage serializes String/Number/Boolean/other value types")
    void sendMessageValueTypes() {
        EdiPartner partner = createPartner("customer", "json_api", true);
        EdiMessageType type = createType("outbound", "json_api",
                "{\"s\":\"m_s\",\"n\":\"m_n\",\"b\":\"m_b\",\"o\":\"m_o\"}");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("m_s", "text");
        data.put("m_n", 3.14);
        data.put("m_b", false);
        data.put("m_o", List.of("a", "b")); // non-primitive -> toString()

        EdiTransaction txn = ediService.sendMessage(partner.getId(), type.getTypeCode(), data);
        String content = txn.getRawContent();
        assertTrue(content.contains("\"s\":\"text\""));
        assertTrue(content.contains("\"n\":3.14"));
        assertTrue(content.contains("\"b\":false"));
        assertTrue(content.contains("\"o\":"));
    }

    // ==================== Inbound (receiveMessage) ====================

    @Test
    @DisplayName("receiveMessage parses inbound JSON and completes the transaction")
    void receiveMessageHappy() {
        EdiPartner partner = createPartner("supplier", "json_api", true);
        createType("inbound", "json_api", "{\"po_number\":\"pe_po\"}");

        EdiTransaction txn = ediService.receiveMessage(partner.getId(), "{\"po_number\":\"PO-3003\"}");

        assertEquals("inbound", txn.getDirection());
        assertEquals(StatusConstants.COMPLETED, txn.getStatus());
        assertNotNull(txn.getProcessedAt());

        EdiTransaction reloaded = ediService.getTransaction(txn.getTransactionNo());
        assertEquals(StatusConstants.COMPLETED, reloaded.getStatus());
        assertTrue(reloaded.getParsedData().contains("po_number"));
    }

    @Test
    @DisplayName("receiveMessage rejects a disabled partner")
    void receiveMessageDisabledPartner() {
        EdiPartner partner = createPartner("supplier", "json_api", false);
        assertThrows(MetaServiceException.class,
                () -> ediService.receiveMessage(partner.getId(), "{}"));
    }

    @Test
    @DisplayName("receiveMessage throws when no inbound type matches the partner protocol")
    void receiveMessageNoMatch() {
        EdiPartner partner = createPartner("supplier", "edifact", true);
        // only a json_api inbound type exists -> no edifact match
        createType("inbound", "json_api", null);
        assertThrows(MetaServiceException.class,
                () -> ediService.receiveMessage(partner.getId(), "UNB+UNOA"));
    }

    @Test
    @DisplayName("receiveMessage records FAILED status when parsing fails")
    void receiveMessageParseFailure() {
        EdiPartner partner = createPartner("supplier", "json_api", true);
        createType("inbound", "json_api", null);

        EdiTransaction txn = ediService.receiveMessage(partner.getId(), "this is not valid json {{{");
        assertEquals(StatusConstants.FAILED, txn.getStatus());
        assertNotNull(txn.getErrorMessage());
    }

    // ==================== parseMessage (protocol branches) ====================

    @Test
    @DisplayName("parseMessage handles json_api, xml, x12, edifact and unknown protocols")
    void parseMessageProtocols() {
        EdiMessageType json = newType("t-json", "inbound", "json_api", null);
        Map<String, Object> jsonParsed = ediService.parseMessage(json, "{\"a\":1,\"b\":\"x\"}");
        assertEquals(1, jsonParsed.get("a"));
        assertEquals("x", jsonParsed.get("b"));

        EdiMessageType cxml = newType("t-cxml", "inbound", "cxml", "{\"po\":\"pe_po\",\"qty\":\"pe_qty\"}");
        Map<String, Object> xmlParsed = ediService.parseMessage(cxml, "<root><po>PO-9</po><qty>5</qty></root>");
        assertEquals("PO-9", xmlParsed.get("po"));
        assertEquals("5", xmlParsed.get("qty"));

        EdiMessageType customXml = newType("t-cx", "inbound", "custom_xml", "{\"po\":\"pe_po\"}");
        assertEquals("PO-9", ediService.parseMessage(customXml, "<r><po>PO-9</po></r>").get("po"));

        // xml with no template matches -> raw_xml fallback
        EdiMessageType cxmlNoMatch = newType("t-cxml2", "inbound", "cxml", "{\"missing\":\"x\"}");
        Map<String, Object> fallback = ediService.parseMessage(cxmlNoMatch, "<root>nothing</root>");
        assertTrue(fallback.containsKey("raw_xml"));

        EdiMessageType x12 = newType("t-x12", "inbound", "edi_x12", null);
        Map<String, Object> x12Parsed = ediService.parseMessage(x12, "ISA*00*  *ZZ*SENDER~ST*856*0001~");
        assertTrue(x12Parsed.containsKey("ISA"));
        assertTrue(x12Parsed.containsKey("ST"));

        EdiMessageType edifact = newType("t-ef", "inbound", "edifact", null);
        Map<String, Object> efParsed = ediService.parseMessage(edifact, "UNB+UNOA:1+SENDER'UNH+1+ORDERS'");
        assertTrue(efParsed.containsKey("UNB"));
        assertTrue(efParsed.containsKey("UNH"));

        EdiMessageType unknown = newType("t-unk", "inbound", "json_api", null);
        unknown.setProtocol("weird_protocol"); // bypass create-time validation
        Map<String, Object> raw = ediService.parseMessage(unknown, "blob");
        assertEquals("blob", raw.get("raw"));
    }

    @Test
    @DisplayName("parseMessage throws on invalid JSON content")
    void parseMessageInvalidJson() {
        EdiMessageType json = newType("t-bad", "inbound", "json_api", null);
        assertThrows(MetaServiceException.class, () -> ediService.parseMessage(json, "not-json"));
    }

    // ==================== mapToModel ====================

    @Test
    @DisplayName("mapToModel maps edi fields to model fields via the template")
    void mapToModelWithTemplate() {
        EdiMessageType type = newType("t-map", "inbound", "json_api", "{\"po_number\":\"pe_po\",\"ship_date\":\"pe_ship\"}");
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("po_number", "PO-1");
        parsed.put("ship_date", "2026-06-11");
        parsed.put("ignored", "x");

        Map<String, Object> model = ediService.mapToModel(type, parsed);
        assertEquals("PO-1", model.get("pe_po"));
        assertEquals("2026-06-11", model.get("pe_ship"));
        assertFalse(model.containsKey("ignored"));
    }

    @Test
    @DisplayName("mapToModel returns parsed data as-is when template is null")
    void mapToModelNoTemplate() {
        EdiMessageType type = newType("t-nomap", "inbound", "json_api", null);
        Map<String, Object> parsed = Map.of("a", "b");
        assertEquals(parsed, ediService.mapToModel(type, parsed));
    }

    @Test
    @DisplayName("mapToModel throws on an invalid mapping template")
    void mapToModelInvalidTemplate() {
        EdiMessageType type = newType("t-badmap", "inbound", "json_api", "not-json-template");
        assertThrows(MetaServiceException.class, () -> ediService.mapToModel(type, Map.of("a", "b")));
    }

    // ==================== Transaction history ====================

    @Test
    @DisplayName("transaction history queries by partner, status and transaction number")
    void transactionHistory() {
        EdiPartner partner = createPartner("customer", "json_api", true);
        EdiMessageType type = createType("outbound", "json_api", null);
        EdiTransaction txn = ediService.sendMessage(partner.getId(), type.getTypeCode(), Map.of("a", "b"));

        List<EdiTransaction> byPartner = ediService.getTransactionHistory(partner.getId(), null, 10);
        assertFalse(byPartner.isEmpty());

        List<EdiTransaction> byPartnerStatus =
                ediService.getTransactionHistory(partner.getId(), StatusConstants.COMPLETED, 10);
        assertFalse(byPartnerStatus.isEmpty());

        List<EdiTransaction> byStatus = ediService.getTransactionsByStatus(StatusConstants.COMPLETED, 10);
        assertFalse(byStatus.isEmpty());

        assertEquals(txn.getTransactionNo(), ediService.getTransaction(txn.getTransactionNo()).getTransactionNo());
    }

    @Test
    @DisplayName("getTransaction throws when not found")
    void getTransactionNotFound() {
        assertThrows(MetaServiceException.class, () -> ediService.getTransaction("no-such-txn"));
    }

    // ==================== retryTransaction (state machine) ====================

    @Test
    @DisplayName("retryTransaction rejects a non-FAILED transaction")
    void retryNonFailed() {
        EdiPartner partner = createPartner("customer", "json_api", true);
        EdiMessageType type = createType("outbound", "json_api", null);
        EdiTransaction txn = ediService.sendMessage(partner.getId(), type.getTypeCode(), Map.of("a", "b"));
        assertThrows(MetaServiceException.class, () -> ediService.retryTransaction(txn.getTransactionNo()));
    }

    @Test
    @DisplayName("retryTransaction re-processes a FAILED inbound transaction successfully")
    void retryInboundSuccess() {
        EdiMessageType type = createType("inbound", "json_api", "{\"po_number\":\"pe_po\"}");
        EdiPartner partner = createPartner("supplier", "json_api", true);

        String txnNo = insertFailedTransaction("inbound", partner.getId(), type.getId(),
                "{\"po_number\":\"PO-OK\"}", 0);

        EdiTransaction retried = ediService.retryTransaction(txnNo);
        assertEquals(StatusConstants.COMPLETED, retried.getStatus());
        assertNull(retried.getErrorMessage());
        assertEquals(Integer.valueOf(1), retried.getRetryCount());
        assertNotNull(retried.getParsedData());
    }

    @Test
    @DisplayName("retryTransaction keeps a FAILED inbound transaction failed when content is still invalid")
    void retryInboundStillFails() {
        EdiMessageType type = createType("inbound", "json_api", null);
        EdiPartner partner = createPartner("supplier", "json_api", true);

        String txnNo = insertFailedTransaction("inbound", partner.getId(), type.getId(), "still-bad", 0);

        EdiTransaction retried = ediService.retryTransaction(txnNo);
        assertEquals(StatusConstants.FAILED, retried.getStatus());
        assertNotNull(retried.getErrorMessage());
        assertEquals(Integer.valueOf(1), retried.getRetryCount());
    }

    @Test
    @DisplayName("retryTransaction re-sends a FAILED outbound transaction")
    void retryOutboundSuccess() {
        EdiMessageType type = createType("outbound", "json_api", null);
        EdiPartner partner = createPartner("customer", "json_api", true);

        String txnNo = insertFailedTransaction("outbound", partner.getId(), type.getId(), "{}", 0);

        EdiTransaction retried = ediService.retryTransaction(txnNo);
        assertEquals(StatusConstants.COMPLETED, retried.getStatus());
        assertEquals(Integer.valueOf(1), retried.getRetryCount());
    }

    @Test
    @DisplayName("retryTransaction rejects once the max retry count is exceeded")
    void retryMaxExceeded() {
        EdiMessageType type = createType("outbound", "json_api", null);
        EdiPartner partner = createPartner("customer", "json_api", true);

        String txnNo = insertFailedTransaction("outbound", partner.getId(), type.getId(), "{}", 3);
        assertThrows(MetaServiceException.class, () -> ediService.retryTransaction(txnNo));
    }

    /** Insert a FAILED transaction fixture directly (the only way to reach the retry path). */
    private String insertFailedTransaction(String direction, Long partnerId, Long messageTypeId,
                                           String rawContent, int retryCount) {
        EdiTransaction t = new EdiTransaction();
        String txnNo = CODE_PREFIX + RUN + "-fix-" + seq.incrementAndGet();
        t.setTenantId(testTenant.getId());
        t.setTransactionNo(txnNo);
        t.setPartnerId(partnerId);
        t.setMessageTypeId(messageTypeId);
        t.setDirection(direction);
        t.setStatus(StatusConstants.FAILED);
        t.setRawContent(rawContent);
        t.setErrorMessage("seeded failure");
        t.setRetryCount(retryCount);
        transactionMapper.insert(t);
        return txnNo;
    }
}
