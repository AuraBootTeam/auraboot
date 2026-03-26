package com.auraboot.module.mrp.adapter;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.module.bitemporal.entity.BiTemporalRecord;
import com.auraboot.module.bitemporal.service.BiTemporalService;
import com.auraboot.module.mrp.dto.AlternativeMaterialDto;
import com.auraboot.module.mrp.dto.BomLineDto;
import com.auraboot.module.mrp.port.BomQueryPort;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class DynamicTableBomAdapter implements BomQueryPort {

    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private BiTemporalService biTemporalService;

    @Override
    public Map<Long, Integer> computeLowLevelCodes() {
        Long tenantId = getTenantId();

        // Load all BOM parent-child relationships
        String sql = "SELECT bl.pe_bom_line_product_id AS child_id, b.pe_bom_product_id AS parent_id " +
            "FROM mt_pe_bom_line bl " +
            "JOIN mt_pe_bom b ON bl.pe_bom_line_bom_id = b.id AND b.tenant_id = ? " +
            "WHERE bl.tenant_id = ?";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tenantId, tenantId);

        // Build adjacency: parent -> children
        Map<Long, Set<Long>> parentToChildren = new HashMap<>();
        Set<Long> allMaterials = new HashSet<>();

        for (Map<String, Object> row : rows) {
            Long childId = ((Number) row.get("child_id")).longValue();
            Long parentId = ((Number) row.get("parent_id")).longValue();
            parentToChildren.computeIfAbsent(parentId, k -> new HashSet<>()).add(childId);
            allMaterials.add(parentId);
            allMaterials.add(childId);
        }

        // Compute LLC using BFS from top-level items
        Map<Long, Integer> llc = new HashMap<>();
        for (Long material : allMaterials) {
            llc.put(material, 0);
        }

        // BFS: propagate levels downward
        boolean changed = true;
        int maxIterations = allMaterials.size() + 1; // safety limit
        int iteration = 0;
        while (changed && iteration < maxIterations) {
            changed = false;
            iteration++;
            for (Map.Entry<Long, Set<Long>> entry : parentToChildren.entrySet()) {
                Long parent = entry.getKey();
                int parentLevel = llc.getOrDefault(parent, 0);
                for (Long child : entry.getValue()) {
                    if (llc.getOrDefault(child, 0) <= parentLevel) {
                        llc.put(child, parentLevel + 1);
                        changed = true;
                    }
                }
            }
        }

        return llc;
    }

    @Override
    public List<BomLineDto> getBomLines(Long materialId, LocalDate effectiveDate) {
        // 1. Get base BOM lines from dynamic table
        List<BomLineDto> baseBomLines = getBaseBomLines(materialId);

        // 2. Try bi-temporal overlay if service is available and effectiveDate is provided
        if (biTemporalService != null && effectiveDate != null && !baseBomLines.isEmpty()) {
            return applyBiTemporalOverlay(baseBomLines, materialId, effectiveDate);
        }

        return baseBomLines;
    }

    /**
     * Query BOM lines from the dynamic table (original behavior).
     */
    private List<BomLineDto> getBaseBomLines(Long materialId) {
        Long tenantId = getTenantId();
        String sql = "SELECT bl.id, bl.pe_bom_line_product_id, bl.pe_bom_line_qty_per, " +
            "bl.pe_bom_line_loss_rate, bl.pe_bom_line_ref_designator " +
            "FROM mt_pe_bom_line bl " +
            "JOIN mt_pe_bom b ON bl.pe_bom_line_bom_id = b.id AND b.tenant_id = ? " +
            "WHERE b.pe_bom_product_id = ? AND b.pe_bom_status = 'enabled' AND bl.tenant_id = ?";

        return jdbcTemplate.query(sql, (rs, rowNum) -> BomLineDto.builder()
            .id(rs.getLong("id"))
            .parentMaterialId(materialId)
            .childMaterialId(rs.getLong("pe_bom_line_product_id"))
            .quantityPer(rs.getBigDecimal("pe_bom_line_qty_per") != null ? rs.getBigDecimal("pe_bom_line_qty_per") : BigDecimal.ONE)
            .lossRate(rs.getBigDecimal("pe_bom_line_loss_rate") != null ? rs.getBigDecimal("pe_bom_line_loss_rate") : BigDecimal.ZERO)
            .refDesignator(rs.getString("pe_bom_line_ref_designator"))
            .build(),
            tenantId, materialId, tenantId);
    }

    /**
     * Apply bi-temporal overlay: for each BOM line, check if a versioned record exists
     * at the effective date. If so, override quantity and loss rate from the version.
     */
    private List<BomLineDto> applyBiTemporalOverlay(List<BomLineDto> baseBomLines,
                                                     Long materialId,
                                                     LocalDate effectiveDate) {
        LocalDateTime validTime = effectiveDate.atStartOfDay();

        // Bulk-fetch all BOM_LINE bi-temporal records at the effective date
        List<BiTemporalRecord> btRecords = biTemporalService.getAllByTypeAsOf("bom_line", validTime);

        if (btRecords.isEmpty()) {
            log.debug("No bi-temporal BOM_LINE records found for date {}, using base data", effectiveDate);
            return baseBomLines;
        }

        // Index by entityId for fast lookup
        Map<String, BiTemporalRecord> btIndex = new HashMap<>();
        for (BiTemporalRecord record : btRecords) {
            btIndex.put(record.getEntityId(), record);
        }

        // Apply overlay
        List<BomLineDto> result = new ArrayList<>();
        int overlayCount = 0;

        for (BomLineDto baseLine : baseBomLines) {
            String lineIdStr = String.valueOf(baseLine.getId());
            BiTemporalRecord btRecord = btIndex.get(lineIdStr);

            if (btRecord != null && btRecord.getPayload() != null) {
                // Override with bi-temporal data
                JsonNode payload = btRecord.getPayload();
                BigDecimal quantity = extractDecimal(payload, "quantity", baseLine.getQuantityPer());
                BigDecimal lossRate = extractDecimal(payload, "lossRate", baseLine.getLossRate());

                result.add(BomLineDto.builder()
                        .id(baseLine.getId())
                        .parentMaterialId(materialId)
                        .childMaterialId(baseLine.getChildMaterialId())
                        .quantityPer(quantity)
                        .lossRate(lossRate)
                        .refDesignator(baseLine.getRefDesignator())
                        .build());
                overlayCount++;
            } else {
                // Use base data as-is
                result.add(baseLine);
            }
        }

        if (overlayCount > 0) {
            log.debug("Applied bi-temporal overlay to {}/{} BOM lines for material {} at {}",
                    overlayCount, baseBomLines.size(), materialId, effectiveDate);
        }

        return result;
    }

    /**
     * Extract a decimal value from a JsonNode, with fallback.
     */
    private BigDecimal extractDecimal(JsonNode node, String field, BigDecimal fallback) {
        if (node.has(field) && !node.get(field).isNull()) {
            try {
                return new BigDecimal(node.get(field).asText());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    @Override
    public boolean hasBom(Long materialId) {
        Long tenantId = getTenantId();
        String sql = "SELECT COUNT(*) FROM mt_pe_bom WHERE pe_bom_product_id = ? AND pe_bom_status = 'enabled' AND tenant_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, materialId, tenantId);
        return count != null && count > 0;
    }

    @Override
    public String getLotSizingPolicy(Long materialId) {
        Long tenantId = getTenantId();
        try {
            String sql = "SELECT pe_prd_lot_policy FROM mt_prod_product WHERE id = ? AND tenant_id = ?";
            String policy = jdbcTemplate.queryForObject(sql, String.class, materialId, tenantId);
            return (policy != null && !policy.isEmpty()) ? policy : "lfl";
        } catch (Exception e) {
            log.debug("Could not retrieve lot sizing policy for material {}, defaulting to LFL", materialId);
            return "lfl";
        }
    }

    @Override
    public int getLeadTime(Long materialId) {
        Long tenantId = getTenantId();
        try {
            String sql = "SELECT pe_prd_lead_time_days FROM mt_prod_product WHERE id = ? AND tenant_id = ?";
            Integer leadTime = jdbcTemplate.queryForObject(sql, Integer.class, materialId, tenantId);
            return (leadTime != null && leadTime > 0) ? leadTime : 30;
        } catch (Exception e) {
            log.debug("Could not retrieve lead time for material {}, defaulting to 30", materialId);
            return 30;
        }
    }

    @Override
    public BigDecimal getMoq(Long materialId) {
        Long tenantId = getTenantId();
        try {
            String sql = "SELECT pe_prd_moq FROM mt_prod_product WHERE id = ? AND tenant_id = ?";
            BigDecimal moq = jdbcTemplate.queryForObject(sql, BigDecimal.class, materialId, tenantId);
            return (moq != null && moq.compareTo(BigDecimal.ZERO) > 0) ? moq : BigDecimal.ONE;
        } catch (Exception e) {
            log.debug("Could not retrieve MOQ for material {}, defaulting to 1", materialId);
            return BigDecimal.ONE;
        }
    }

    @Override
    public List<AlternativeMaterialDto> getAlternatives(Long bomLineId) {
        Long tenantId = getTenantId();
        String sql = "SELECT id, pe_am_bom_line_id, pe_am_material_id, pe_am_material_name, " +
            "pe_am_priority, pe_am_conversion_factor " +
            "FROM mt_pe_alternative_material " +
            "WHERE pe_am_bom_line_id = ? AND tenant_id = ? ORDER BY pe_am_priority ASC";

        return jdbcTemplate.query(sql, (rs, rowNum) -> AlternativeMaterialDto.builder()
            .id(rs.getLong("id"))
            .bomLineId(rs.getLong("pe_am_bom_line_id"))
            .materialId(rs.getLong("pe_am_material_id"))
            .materialName(rs.getString("pe_am_material_name"))
            .priority(rs.getInt("pe_am_priority"))
            .conversionFactor(rs.getBigDecimal("pe_am_conversion_factor") != null ? rs.getBigDecimal("pe_am_conversion_factor") : BigDecimal.ONE)
            .build(),
            bomLineId, tenantId);
    }

    private Long getTenantId() {
        return MetaContext.getCurrentTenantId();
    }
}
