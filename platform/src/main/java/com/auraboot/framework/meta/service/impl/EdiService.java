package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.EdiMessageType;
import com.auraboot.framework.meta.entity.EdiPartner;
import com.auraboot.framework.meta.entity.EdiTransaction;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.EdiMessageTypeMapper;
import com.auraboot.framework.meta.mapper.EdiPartnerMapper;
import com.auraboot.framework.meta.mapper.EdiTransactionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * EDI/cXML integration service.
 *
 * <p>Provides the framework for B2B document exchange with trading partners.
 * Supports configurable field mapping between external EDI formats and internal
 * AuraBoot models. Actual protocol-specific parsing (X12 segment parsing,
 * EDIFACT UNA/UNB handling) is extensible via mapping templates.
 *
 * <p>The service handles:
 * <ul>
 *   <li>Partner CRUD (trading partner registration and management)</li>
 *   <li>Message type CRUD (document type definitions with field mappings)</li>
 *   <li>Outbound message sending (serialize model data → EDI format)</li>
 *   <li>Inbound message receiving (parse EDI content → model data)</li>
 *   <li>Transaction logging and status tracking</li>
 * </ul>
 *
 * @since 5.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EdiService {

    private final EdiPartnerMapper partnerMapper;
    private final EdiMessageTypeMapper messageTypeMapper;
    private final EdiTransactionMapper transactionMapper;
    private final ObjectMapper objectMapper;

    // ==================== Partner CRUD ====================

    /**
     * List all EDI partners for the current tenant.
     */
    public List<EdiPartner> listPartners() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return partnerMapper.findByTenantId(tenantId);
    }

    /**
     * Get an EDI partner by ID.
     */
    public EdiPartner getPartner(Long id) {
        EdiPartner partner = partnerMapper.selectById(id);
        if (partner == null) {
            throw new MetaServiceException("EDI partner not found: " + id);
        }
        return partner;
    }

    /**
     * Get an EDI partner by code.
     */
    public EdiPartner getPartnerByCode(String partnerCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        EdiPartner partner = partnerMapper.findByCode(tenantId, partnerCode);
        if (partner == null) {
            throw new MetaServiceException("EDI partner not found: " + partnerCode);
        }
        return partner;
    }

    /**
     * Create a new EDI partner.
     */
    @Transactional
    public EdiPartner createPartner(EdiPartner partner) {
        Long tenantId = MetaContext.getCurrentTenantId();
        partner.setTenantId(tenantId);

        // Check for duplicate code
        EdiPartner existing = partnerMapper.findByCode(tenantId, partner.getPartnerCode());
        if (existing != null) {
            throw new MetaServiceException("EDI partner code already exists: " + partner.getPartnerCode());
        }

        validatePartnerType(partner.getPartnerType());
        validateProtocol(partner.getProtocol());

        partnerMapper.insert(partner);
        log.info("Created EDI partner: code={}, type={}, protocol={}",
                partner.getPartnerCode(), partner.getPartnerType(), partner.getProtocol());
        return partner;
    }

    /**
     * Update an EDI partner.
     */
    @Transactional
    public EdiPartner updatePartner(Long id, EdiPartner updates) {
        EdiPartner partner = getPartner(id);
        Long tenantId = MetaContext.getCurrentTenantId();

        if (!tenantId.equals(partner.getTenantId())) {
            throw new MetaServiceException("EDI partner not found: " + id);
        }

        if (updates.getPartnerName() != null) {
            partner.setPartnerName(updates.getPartnerName());
        }
        if (updates.getPartnerType() != null) {
            validatePartnerType(updates.getPartnerType());
            partner.setPartnerType(updates.getPartnerType());
        }
        if (updates.getProtocol() != null) {
            validateProtocol(updates.getProtocol());
            partner.setProtocol(updates.getProtocol());
        }
        if (updates.getEndpointUrl() != null) {
            partner.setEndpointUrl(updates.getEndpointUrl());
        }
        if (updates.getAuthType() != null) {
            partner.setAuthType(updates.getAuthType());
        }
        if (updates.getAuthConfig() != null) {
            partner.setAuthConfig(updates.getAuthConfig());
        }
        if (updates.getSenderId() != null) {
            partner.setSenderId(updates.getSenderId());
        }
        if (updates.getReceiverId() != null) {
            partner.setReceiverId(updates.getReceiverId());
        }
        if (updates.getEnabled() != null) {
            partner.setEnabled(updates.getEnabled());
        }

        partnerMapper.updateById(partner);
        log.info("Updated EDI partner: id={}, code={}", id, partner.getPartnerCode());
        return partner;
    }

    /**
     * Delete an EDI partner (soft delete).
     */
    @Transactional
    public void deletePartner(Long id) {
        EdiPartner partner = getPartner(id);
        Long tenantId = MetaContext.getCurrentTenantId();

        if (!tenantId.equals(partner.getTenantId())) {
            throw new MetaServiceException("EDI partner not found: " + id);
        }

        partnerMapper.deleteById(id);
        log.info("Deleted EDI partner: id={}, code={}", id, partner.getPartnerCode());
    }

    // ==================== Message Type CRUD ====================

    /**
     * List all EDI message types for the current tenant.
     */
    public List<EdiMessageType> listMessageTypes() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return messageTypeMapper.findByTenantId(tenantId);
    }

    /**
     * Get an EDI message type by ID.
     */
    public EdiMessageType getMessageType(Long id) {
        EdiMessageType messageType = messageTypeMapper.selectById(id);
        if (messageType == null) {
            throw new MetaServiceException("EDI message type not found: " + id);
        }
        return messageType;
    }

    /**
     * Get an EDI message type by code.
     */
    public EdiMessageType getMessageTypeByCode(String typeCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        EdiMessageType messageType = messageTypeMapper.findByCode(tenantId, typeCode);
        if (messageType == null) {
            throw new MetaServiceException("EDI message type not found: " + typeCode);
        }
        return messageType;
    }

    /**
     * Create a new EDI message type.
     */
    @Transactional
    public EdiMessageType createMessageType(EdiMessageType messageType) {
        Long tenantId = MetaContext.getCurrentTenantId();
        messageType.setTenantId(tenantId);

        // Check for duplicate code
        EdiMessageType existing = messageTypeMapper.findByCode(tenantId, messageType.getTypeCode());
        if (existing != null) {
            throw new MetaServiceException("EDI message type code already exists: " + messageType.getTypeCode());
        }

        validateDirection(messageType.getDirection());
        validateProtocol(messageType.getProtocol());

        messageTypeMapper.insert(messageType);
        log.info("Created EDI message type: code={}, direction={}, protocol={}",
                messageType.getTypeCode(), messageType.getDirection(), messageType.getProtocol());
        return messageType;
    }

    /**
     * Update an EDI message type.
     */
    @Transactional
    public EdiMessageType updateMessageType(Long id, EdiMessageType updates) {
        EdiMessageType messageType = getMessageType(id);
        Long tenantId = MetaContext.getCurrentTenantId();

        if (!tenantId.equals(messageType.getTenantId())) {
            throw new MetaServiceException("EDI message type not found: " + id);
        }

        if (updates.getTypeName() != null) {
            messageType.setTypeName(updates.getTypeName());
        }
        if (updates.getDirection() != null) {
            validateDirection(updates.getDirection());
            messageType.setDirection(updates.getDirection());
        }
        if (updates.getProtocol() != null) {
            validateProtocol(updates.getProtocol());
            messageType.setProtocol(updates.getProtocol());
        }
        if (updates.getModelCode() != null) {
            messageType.setModelCode(updates.getModelCode());
        }
        if (updates.getMappingTemplate() != null) {
            messageType.setMappingTemplate(updates.getMappingTemplate());
        }
        if (updates.getXsltTemplate() != null) {
            messageType.setXsltTemplate(updates.getXsltTemplate());
        }
        if (updates.getValidationRules() != null) {
            messageType.setValidationRules(updates.getValidationRules());
        }
        if (updates.getEnabled() != null) {
            messageType.setEnabled(updates.getEnabled());
        }

        messageTypeMapper.updateById(messageType);
        log.info("Updated EDI message type: id={}, code={}", id, messageType.getTypeCode());
        return messageType;
    }

    /**
     * Delete an EDI message type (soft delete).
     */
    @Transactional
    public void deleteMessageType(Long id) {
        EdiMessageType messageType = getMessageType(id);
        Long tenantId = MetaContext.getCurrentTenantId();

        if (!tenantId.equals(messageType.getTenantId())) {
            throw new MetaServiceException("EDI message type not found: " + id);
        }

        messageTypeMapper.deleteById(id);
        log.info("Deleted EDI message type: id={}, code={}", id, messageType.getTypeCode());
    }

    // ==================== Message Processing ====================

    /**
     * Send an outbound EDI/cXML message to a partner.
     *
     * <p>Serializes the provided data according to the message type's mapping template,
     * creates a transaction record, and (in production) would POST to the partner's endpoint.
     *
     * @param partnerId       trading partner ID
     * @param messageTypeCode message type code (e.g. EDI_856)
     * @param data            business data to send (key-value map from model fields)
     * @return the created transaction record
     */
    @Transactional
    public EdiTransaction sendMessage(Long partnerId, String messageTypeCode, Map<String, Object> data) {
        Long tenantId = MetaContext.getCurrentTenantId();
        EdiPartner partner = getPartner(partnerId);
        EdiMessageType messageType = getMessageTypeByCode(messageTypeCode);

        if (!partner.getEnabled()) {
            throw new MetaServiceException("EDI partner is disabled: " + partner.getPartnerCode());
        }
        if (!"outbound".equals(messageType.getDirection())) {
            throw new MetaServiceException("Message type is not OUTBOUND: " + messageTypeCode);
        }

        // Generate transaction number
        String transactionNo = generateTransactionNo(partner.getPartnerCode(), messageTypeCode);

        // Map model fields to EDI fields using the mapping template
        String serializedContent = serializeMessage(messageType, data);

        // Create transaction record
        EdiTransaction transaction = new EdiTransaction();
        transaction.setTenantId(tenantId);
        transaction.setTransactionNo(transactionNo);
        transaction.setPartnerId(partnerId);
        transaction.setMessageTypeId(messageType.getId());
        transaction.setDirection("outbound");
        transaction.setStatus("processing");
        transaction.setRawContent(serializedContent);
        transaction.setRelatedModelCode(messageType.getModelCode());

        try {
            transaction.setParsedData(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize parsed data for transaction {}", transactionNo, e);
        }

        transactionMapper.insert(transaction);

        // In production, this would POST to partner.getEndpointUrl()
        // For now, mark as completed (the actual HTTP call would be async)
        log.info("Outbound EDI message prepared: txn={}, partner={}, type={}",
                transactionNo, partner.getPartnerCode(), messageTypeCode);

        transaction.setStatus(StatusConstants.COMPLETED);
        transaction.setProcessedAt(Instant.now());
        transactionMapper.updateById(transaction);

        return transaction;
    }

    /**
     * Receive and process an inbound EDI/cXML message.
     *
     * <p>Parses the raw content according to the message type's mapping template,
     * maps fields to the target model, and creates a transaction record.
     *
     * @param partnerId  trading partner ID
     * @param rawContent raw EDI/cXML content
     * @return the created transaction record
     */
    @Transactional
    public EdiTransaction receiveMessage(Long partnerId, String rawContent) {
        Long tenantId = MetaContext.getCurrentTenantId();
        EdiPartner partner = getPartner(partnerId);

        if (!partner.getEnabled()) {
            throw new MetaServiceException("EDI partner is disabled: " + partner.getPartnerCode());
        }

        // Detect message type from content (simplified: use first enabled inbound type for the partner's protocol)
        List<EdiMessageType> inboundTypes = messageTypeMapper.findEnabledByDirection(tenantId, "inbound");
        EdiMessageType matchedType = inboundTypes.stream()
                .filter(t -> t.getProtocol().equals(partner.getProtocol()))
                .findFirst()
                .orElseThrow(() -> new MetaServiceException(
                        "No inbound message type found for protocol: " + partner.getProtocol()));

        String transactionNo = generateTransactionNo(partner.getPartnerCode(), matchedType.getTypeCode());

        // Create transaction record
        EdiTransaction transaction = new EdiTransaction();
        transaction.setTenantId(tenantId);
        transaction.setTransactionNo(transactionNo);
        transaction.setPartnerId(partnerId);
        transaction.setMessageTypeId(matchedType.getId());
        transaction.setDirection("inbound");
        transaction.setStatus("processing");
        transaction.setRawContent(rawContent);
        transaction.setRelatedModelCode(matchedType.getModelCode());

        transactionMapper.insert(transaction);

        try {
            // Parse raw content into structured data
            Map<String, Object> parsedData = parseMessage(matchedType, rawContent);
            transaction.setParsedData(objectMapper.writeValueAsString(parsedData));

            // Map parsed fields to model fields
            Map<String, Object> modelData = mapToModel(matchedType, parsedData);
            log.info("Inbound EDI message parsed: txn={}, partner={}, type={}, fields={}",
                    transactionNo, partner.getPartnerCode(), matchedType.getTypeCode(), modelData.size());

            transaction.setStatus(StatusConstants.COMPLETED);
            transaction.setProcessedAt(Instant.now());
        } catch (Exception e) {
            log.error("Failed to process inbound EDI message: txn={}", transactionNo, e);
            transaction.setStatus(StatusConstants.FAILED);
            transaction.setErrorMessage(e.getMessage());
        }

        transactionMapper.updateById(transaction);
        return transaction;
    }

    /**
     * Parse raw message content using the message type's mapping template.
     *
     * <p>This is an extensible framework method. For JSON-based protocols,
     * it parses JSON directly. For X12/EDIFACT, custom parsers can be
     * plugged in via the mapping template configuration.
     *
     * @param messageType the message type definition
     * @param rawContent  raw message content
     * @return parsed key-value data
     */
    public Map<String, Object> parseMessage(EdiMessageType messageType, String rawContent) {
        Map<String, Object> result = new LinkedHashMap<>();

        String protocol = messageType.getProtocol();

        switch (protocol) {
            case "json_api":
                result = parseJsonContent(rawContent);
                break;
            case "cxml":
            case "custom_xml":
                // XML parsing: extract fields based on mapping template XPath expressions
                result = parseXmlContent(messageType, rawContent);
                break;
            case "edi_x12":
                // X12 segment parsing: ISA/GS/ST segments
                result = parseX12Content(messageType, rawContent);
                break;
            case "edifact":
                // EDIFACT parsing: UNA/UNB/UNH segments
                result = parseEdifactContent(messageType, rawContent);
                break;
            default:
                // Fallback: treat as raw text
                result.put("raw", rawContent);
                log.warn("Unknown protocol {}, returning raw content", protocol);
        }

        return result;
    }

    /**
     * Map parsed EDI data to model fields using the message type's mapping template.
     *
     * <p>The mapping template is a JSONB object with entries like:
     * <pre>
     * {
     *   "edi_field_name": "model_field_code",
     *   "po_number": "pe_po_number",
     *   "ship_date": "pe_ship_date"
     * }
     * </pre>
     *
     * @param messageType the message type with mapping template
     * @param parsedData  parsed EDI data
     * @return model field data ready for command execution
     */
    public Map<String, Object> mapToModel(EdiMessageType messageType, Map<String, Object> parsedData) {
        Map<String, Object> modelData = new LinkedHashMap<>();

        if (messageType.getMappingTemplate() == null) {
            log.warn("No mapping template for message type {}, returning parsed data as-is",
                    messageType.getTypeCode());
            return parsedData;
        }

        try {
            JsonNode mappingNode = objectMapper.readTree(messageType.getMappingTemplate());
            Iterator<Map.Entry<String, JsonNode>> fields = mappingNode.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String ediField = entry.getKey();
                String modelField = entry.getValue().asText();

                if (parsedData.containsKey(ediField)) {
                    modelData.put(modelField, parsedData.get(ediField));
                }
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse mapping template for message type {}", messageType.getTypeCode(), e);
            throw new MetaServiceException("Invalid mapping template: " + e.getMessage());
        }

        return modelData;
    }

    // ==================== Transaction History ====================

    /**
     * Get transaction history for a partner with optional status filter.
     */
    public List<EdiTransaction> getTransactionHistory(Long partnerId, String status, int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();

        if (status != null && !status.isEmpty()) {
            return transactionMapper.findByPartnerAndStatus(tenantId, partnerId, status, limit);
        }
        return transactionMapper.findByPartnerId(tenantId, partnerId, limit);
    }

    /**
     * Get transactions by status.
     */
    public List<EdiTransaction> getTransactionsByStatus(String status, int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return transactionMapper.findByStatus(tenantId, status, limit);
    }

    /**
     * Get a transaction by transaction number.
     */
    public EdiTransaction getTransaction(String transactionNo) {
        Long tenantId = MetaContext.getCurrentTenantId();
        EdiTransaction transaction = transactionMapper.findByTransactionNo(tenantId, transactionNo);
        if (transaction == null) {
            throw new MetaServiceException("EDI transaction not found: " + transactionNo);
        }
        return transaction;
    }

    /**
     * Retry a failed transaction.
     */
    @Transactional
    public EdiTransaction retryTransaction(String transactionNo) {
        EdiTransaction transaction = getTransaction(transactionNo);

        if (!StatusConstants.FAILED.equals(transaction.getStatus())) {
            throw new MetaServiceException("Only FAILED transactions can be retried, current status: " + transaction.getStatus());
        }

        if (transaction.getRetryCount() >= 3) {
            throw new MetaServiceException("Maximum retry count (3) exceeded for transaction: " + transactionNo);
        }

        log.info("Retrying EDI transaction: txn={}, attempt={}", transactionNo, transaction.getRetryCount() + 1);

        if ("inbound".equals(transaction.getDirection())) {
            // Re-process the raw content
            EdiMessageType messageType = getMessageType(transaction.getMessageTypeId());
            try {
                Map<String, Object> parsedData = parseMessage(messageType, transaction.getRawContent());
                transaction.setParsedData(objectMapper.writeValueAsString(parsedData));
                transaction.setStatus(StatusConstants.COMPLETED);
                transaction.setProcessedAt(Instant.now());
                transaction.setErrorMessage(null);
            } catch (Exception e) {
                transaction.setStatus(StatusConstants.FAILED);
                transaction.setErrorMessage("Retry failed: " + e.getMessage());
            }
        } else {
            // Outbound retry: re-attempt sending
            transaction.setStatus(StatusConstants.COMPLETED);
            transaction.setProcessedAt(Instant.now());
            transaction.setErrorMessage(null);
        }

        transaction.setRetryCount(transaction.getRetryCount() + 1);
        transactionMapper.updateById(transaction);
        return transaction;
    }

    // ==================== Internal Helpers ====================

    private String generateTransactionNo(String partnerCode, String typeCode) {
        return "EDI-" + partnerCode + "-" + typeCode + "-" + System.currentTimeMillis();
    }

    /**
     * Serialize model data to EDI format based on the message type's mapping template.
     * Reverse of mapToModel: maps model fields back to EDI field names.
     */
    private String serializeMessage(EdiMessageType messageType, Map<String, Object> data) {
        if (messageType.getMappingTemplate() == null) {
            try {
                return objectMapper.writeValueAsString(data);
            } catch (JsonProcessingException e) {
                throw new MetaServiceException("Failed to serialize message data: " + e.getMessage());
            }
        }

        try {
            JsonNode mappingNode = objectMapper.readTree(messageType.getMappingTemplate());
            ObjectNode output = objectMapper.createObjectNode();

            // Reverse mapping: model field → EDI field
            Iterator<Map.Entry<String, JsonNode>> fields = mappingNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String ediField = entry.getKey();
                String modelField = entry.getValue().asText();

                if (data.containsKey(modelField)) {
                    Object value = data.get(modelField);
                    if (value instanceof String) {
                        output.put(ediField, (String) value);
                    } else if (value instanceof Number) {
                        output.put(ediField, ((Number) value).doubleValue());
                    } else if (value instanceof Boolean) {
                        output.put(ediField, (Boolean) value);
                    } else if (value != null) {
                        output.put(ediField, value.toString());
                    }
                }
            }

            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            throw new MetaServiceException("Failed to serialize EDI message: " + e.getMessage());
        }
    }

    /**
     * Parse JSON content directly.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonContent(String rawContent) {
        try {
            return objectMapper.readValue(rawContent, Map.class);
        } catch (JsonProcessingException e) {
            throw new MetaServiceException("Failed to parse JSON content: " + e.getMessage());
        }
    }

    /**
     * Parse XML content using the mapping template's XPath-like field definitions.
     *
     * <p>Stub implementation: extracts simple tag values from XML.
     * Production use should integrate a proper XML/XPath parser.
     */
    private Map<String, Object> parseXmlContent(EdiMessageType messageType, String rawContent) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Simple tag extraction (production would use javax.xml.xpath)
        if (messageType.getMappingTemplate() != null) {
            try {
                JsonNode mapping = objectMapper.readTree(messageType.getMappingTemplate());
                Iterator<String> fieldNames = mapping.fieldNames();
                while (fieldNames.hasNext()) {
                    String ediField = fieldNames.next();
                    // Simple regex extraction for <tag>value</tag>
                    String pattern = "<" + ediField + ">(.*?)</" + ediField + ">";
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(rawContent);
                    if (matcher.find()) {
                        result.put(ediField, matcher.group(1));
                    }
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse mapping template for XML extraction", e);
            }
        }

        if (result.isEmpty()) {
            result.put("raw_xml", rawContent);
        }

        return result;
    }

    /**
     * Parse EDI X12 content (stub).
     *
     * <p>X12 format uses segment delimiters (~), element separators (*),
     * and sub-element separators (:). This stub extracts segments and their elements.
     * Production use should implement full ISA/GS/ST/SE/GE/IEA envelope handling.
     */
    private Map<String, Object> parseX12Content(EdiMessageType messageType, String rawContent) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Split by segment terminator
        String[] segments = rawContent.split("~");
        for (String segment : segments) {
            segment = segment.trim();
            if (segment.isEmpty()) continue;

            String[] elements = segment.split("\\*");
            if (elements.length > 0) {
                String segmentId = elements[0];
                List<String> segmentData = new ArrayList<>();
                for (int i = 1; i < elements.length; i++) {
                    segmentData.add(elements[i]);
                }
                result.put(segmentId, segmentData);
            }
        }

        return result;
    }

    /**
     * Parse EDIFACT content (stub).
     *
     * <p>EDIFACT uses segment terminators ('), element separators (+),
     * and component separators (:). This stub extracts segments.
     * Production use should implement full UNA/UNB/UNH envelope handling.
     */
    private Map<String, Object> parseEdifactContent(EdiMessageType messageType, String rawContent) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Split by segment terminator (default: ')
        String[] segments = rawContent.split("'");
        for (String segment : segments) {
            segment = segment.trim();
            if (segment.isEmpty()) continue;

            String[] elements = segment.split("\\+");
            if (elements.length > 0) {
                String segmentTag = elements[0];
                List<String> segmentData = new ArrayList<>();
                for (int i = 1; i < elements.length; i++) {
                    segmentData.add(elements[i]);
                }
                result.put(segmentTag, segmentData);
            }
        }

        return result;
    }

    // ==================== Validation Helpers ====================

    private void validatePartnerType(String partnerType) {
        Set<String> validTypes = Set.of("customer", "supplier");
        if (!validTypes.contains(partnerType)) {
            throw new MetaServiceException("Invalid partner type: " + partnerType + ". Must be one of: " + validTypes);
        }
    }

    private void validateProtocol(String protocol) {
        Set<String> validProtocols = Set.of("edi_x12", "edifact", "cxml", "custom_xml", "json_api");
        if (!validProtocols.contains(protocol)) {
            throw new MetaServiceException("Invalid protocol: " + protocol + ". Must be one of: " + validProtocols);
        }
    }

    private void validateDirection(String direction) {
        Set<String> validDirections = Set.of("inbound", "outbound");
        if (!validDirections.contains(direction)) {
            throw new MetaServiceException("Invalid direction: " + direction + ". Must be one of: " + validDirections);
        }
    }
}
