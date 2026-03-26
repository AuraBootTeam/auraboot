package com.auraboot.module.mrp.engine;

import com.auraboot.module.mrp.dto.AlternativeMaterialDto;
import com.auraboot.module.mrp.port.BomQueryPort;
import com.auraboot.module.mrp.port.InventoryQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AlternativeMaterialResolver {

    private final BomQueryPort bomPort;
    private final InventoryQueryPort inventoryPort;

    /**
     * Resolve the best material to use for a BOM line requirement.
     * Strategy: try primary material first, then alternatives by priority.
     */
    public ResolvedMaterial resolve(Long bomLineId, Long primaryMaterialId, BigDecimal requiredQty) {
        BigDecimal primaryAvailable = inventoryPort.getAvailableQty(primaryMaterialId);
        if (primaryAvailable.compareTo(requiredQty) >= 0) {
            return new ResolvedMaterial(primaryMaterialId, false, BigDecimal.ONE);
        }

        List<AlternativeMaterialDto> alternatives = bomPort.getAlternatives(bomLineId);
        for (AlternativeMaterialDto alt : alternatives) {
            BigDecimal altAvailable = inventoryPort.getAvailableQty(alt.getMaterialId());
            BigDecimal adjustedRequired = requiredQty.multiply(alt.getConversionFactor());
            if (altAvailable.compareTo(adjustedRequired) >= 0) {
                return new ResolvedMaterial(alt.getMaterialId(), true, alt.getConversionFactor());
            }
        }

        return new ResolvedMaterial(primaryMaterialId, false, BigDecimal.ONE);
    }
}
