package com.auraboot.module.mrp.engine;

import com.auraboot.module.mrp.dto.BomLineDto;
import com.auraboot.module.mrp.port.BomQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BomExplosionService {

    private final BomQueryPort bomPort;

    /**
     * Compute Low Level Code for all materials.
     * LLC = max depth at which a material appears in any BOM tree.
     * Level 0 = finished goods (no parent), Level 1 = direct children, etc.
     */
    public Map<Long, Integer> computeLowLevelCodes() {
        return bomPort.computeLowLevelCodes();
    }

    /**
     * Explode one level of BOM for a given material.
     * Applies quantity-per and loss rate.
     *
     * @param materialId    the parent material
     * @param parentQty     how many units of parent are needed
     * @param effectiveDate which BOM version to use
     * @return list of child demands with adjusted quantities
     */
    public List<ChildDemand> explodeOneLevel(Long materialId, BigDecimal parentQty, LocalDate effectiveDate) {
        List<BomLineDto> lines = bomPort.getBomLines(materialId, effectiveDate);
        List<ChildDemand> demands = new ArrayList<>();
        for (BomLineDto line : lines) {
            BigDecimal grossQty = parentQty.multiply(line.getQuantityPer());
            if (line.getLossRate() != null && line.getLossRate().compareTo(BigDecimal.ZERO) > 0) {
                grossQty = grossQty.divide(
                    BigDecimal.ONE.subtract(line.getLossRate()),
                    4, RoundingMode.HALF_UP
                );
            }
            demands.add(new ChildDemand(line.getChildMaterialId(), line.getChildMaterialName(), grossQty, line.getId()));
        }
        return demands;
    }
}
