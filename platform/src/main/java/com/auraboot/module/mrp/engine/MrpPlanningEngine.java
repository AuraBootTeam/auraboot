package com.auraboot.module.mrp.engine;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.module.mrp.dto.DemandEntry;
import com.auraboot.module.mrp.dto.MrpResult;
import com.auraboot.module.mrp.dto.PlannedOrderDto;
import com.auraboot.module.mrp.port.BomQueryPort;
import com.auraboot.module.mrp.port.InventoryQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MrpPlanningEngine {

    private final BomExplosionService bomExplosion;
    private final NettingService netting;
    private final LotSizingStrategyFactory lotSizingFactory;
    private final AlternativeMaterialResolver altResolver;
    private final LeadTimeService leadTimeService;
    private final BomQueryPort bomPort;
    private final InventoryQueryPort inventoryPort;
    private final TenantClock tenantClock;

    public MrpResult runMrp(List<DemandEntry> demands, int horizonDays) {
        MrpExceptionReporter reporter = new MrpExceptionReporter();
        Map<Long, Integer> llc = bomExplosion.computeLowLevelCodes();
        int maxLevel = llc.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        Map<Integer, List<DemandEntry>> demandsPerLevel = new TreeMap<>();
        demandsPerLevel.put(0, new ArrayList<>(demands));

        List<PlannedOrderDto> plannedOrders = new ArrayList<>();

        for (int level = 0; level <= maxLevel; level++) {
            List<DemandEntry> levelDemands = demandsPerLevel.getOrDefault(level, List.of());
            Map<Long, List<DemandEntry>> byMaterial = levelDemands.stream()
                .collect(Collectors.groupingBy(DemandEntry::getMaterialId));

            for (var entry : byMaterial.entrySet()) {
                Long materialId = entry.getKey();
                BigDecimal grossDemand = entry.getValue().stream()
                    .map(DemandEntry::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Netting
                BigDecimal netDemand = netting.calculateNetDemand(materialId, grossDemand);
                if (netDemand.compareTo(BigDecimal.ZERO) <= 0) continue;

                // Lot Sizing
                String policy = bomPort.getLotSizingPolicy(materialId);
                BigDecimal moq = bomPort.getMoq(materialId);
                LotSizingStrategy strategy = lotSizingFactory.getStrategy(policy);
                BigDecimal orderQty = strategy.calculate(netDemand, moq, Map.of());

                // Lead Time Offset
                LocalDate needDate = entry.getValue().stream()
                    .map(DemandEntry::getNeedDate)
                    .min(LocalDate::compareTo).orElse(tenantClock.businessDate(MetaContext.getCurrentTenantId()));
                LocalDate orderDate = leadTimeService.calculateOrderDate(materialId, needDate);
                int leadTimeDays = leadTimeService.getLeadTimeDays(materialId);

                // Exception checks
                if (orderDate.isBefore(tenantClock.businessDate(MetaContext.getCurrentTenantId()))) {
                    reporter.reportPastDue(materialId, "", orderDate, needDate);
                    orderDate = tenantClock.businessDate(MetaContext.getCurrentTenantId());
                }
                if (leadTimeService.isLongLeadTime(materialId)) {
                    reporter.reportLongLeadTime(materialId, "", leadTimeDays);
                }

                // Generate planned order
                String orderType = bomPort.hasBom(materialId) ? "production" : "purchase";
                plannedOrders.add(PlannedOrderDto.builder()
                    .materialId(materialId)
                    .orderType(orderType)
                    .orderQty(orderQty)
                    .orderDate(orderDate)
                    .needDate(needDate)
                    .leadTimeDays(leadTimeDays)
                    .lotSizingPolicy(policy)
                    .build());

                // BOM Explosion for manufactured items
                if (bomPort.hasBom(materialId)) {
                    List<ChildDemand> children = bomExplosion.explodeOneLevel(materialId, orderQty, needDate);
                    for (ChildDemand child : children) {
                        int childLevel = llc.getOrDefault(child.getMaterialId(), level + 1);
                        demandsPerLevel.computeIfAbsent(childLevel, k -> new ArrayList<>())
                            .add(new DemandEntry(child.getMaterialId(), child.getMaterialName(), child.getQuantity(), orderDate));
                    }
                }
            }
        }

        return new MrpResult(plannedOrders, reporter.getExceptions());
    }
}
