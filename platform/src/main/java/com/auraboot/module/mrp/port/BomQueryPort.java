package com.auraboot.module.mrp.port;

import com.auraboot.module.mrp.dto.AlternativeMaterialDto;
import com.auraboot.module.mrp.dto.BomLineDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Port interface for querying BOM (Bill of Materials) data.
 * Decouples MRP algorithm from AuraBoot's dynamic table infrastructure.
 */
public interface BomQueryPort {

    Map<Long, Integer> computeLowLevelCodes();

    List<BomLineDto> getBomLines(Long materialId, LocalDate effectiveDate);

    boolean hasBom(Long materialId);

    String getLotSizingPolicy(Long materialId);

    int getLeadTime(Long materialId);

    BigDecimal getMoq(Long materialId);

    List<AlternativeMaterialDto> getAlternatives(Long bomLineId);
}
