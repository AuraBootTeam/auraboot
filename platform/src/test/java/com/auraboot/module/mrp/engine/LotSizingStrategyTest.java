package com.auraboot.module.mrp.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LotSizingStrategyTest {

    @Test
    void testLotForLot() {
        // net=150, moq=0 -> 150
        LotSizingStrategy strategy = new LotForLotStrategy();
        BigDecimal result = strategy.calculate(new BigDecimal("150"), BigDecimal.ZERO, Map.of());
        assertEquals(0, new BigDecimal("150").compareTo(result));
    }

    @Test
    void testLotForLotWithMoq() {
        // net=50, moq=100 -> 100
        LotSizingStrategy strategy = new LotForLotStrategy();
        BigDecimal result = strategy.calculate(new BigDecimal("50"), new BigDecimal("100"), Map.of());
        assertEquals(0, new BigDecimal("100").compareTo(result));
    }

    @Test
    void testFixedOrderQty() {
        // net=250, fixedQty=100, moq=0 -> ceil(250/100)*100 = 300
        LotSizingStrategy strategy = new FixedOrderQtyStrategy();
        BigDecimal result = strategy.calculate(new BigDecimal("250"), BigDecimal.ZERO,
            Map.of("fixedOrderQty", new BigDecimal("100")));
        assertEquals(0, new BigDecimal("300").compareTo(result));
    }

    @Test
    void testFixedOrderQtyWithMoq() {
        // net=50, fixedQty=30, moq=100 -> ceil(50/30)*30=60 < 100 -> 100
        LotSizingStrategy strategy = new FixedOrderQtyStrategy();
        BigDecimal result = strategy.calculate(new BigDecimal("50"), new BigDecimal("100"),
            Map.of("fixedOrderQty", new BigDecimal("30")));
        assertEquals(0, new BigDecimal("100").compareTo(result));
    }

    @Test
    void testEoq() {
        // D=1200, S=500, H=10 -> EOQ batch = sqrt(2*1200*500/10) = 347
        // netDemand=500 -> ceil(500/347)*347 = 2*347 = 694
        LotSizingStrategy strategy = new EconomicOrderQtyStrategy();
        BigDecimal result = strategy.calculate(new BigDecimal("500"), BigDecimal.ZERO,
            Map.of(
                "annualDemand", new BigDecimal("1200"),
                "orderCost", new BigDecimal("500"),
                "holdingCostPerUnit", new BigDecimal("10")
            ));
        assertEquals(0, new BigDecimal("694").compareTo(result));
    }

    @Test
    void testEoqWithMoq() {
        // EOQ batch=347, netDemand=200 -> ceil(200/347)*347 = 347, but moq=500 -> 500
        LotSizingStrategy strategy = new EconomicOrderQtyStrategy();
        BigDecimal result = strategy.calculate(new BigDecimal("200"), new BigDecimal("500"),
            Map.of(
                "annualDemand", new BigDecimal("1200"),
                "orderCost", new BigDecimal("500"),
                "holdingCostPerUnit", new BigDecimal("10")
            ));
        assertEquals(0, new BigDecimal("500").compareTo(result));
    }

    @Test
    void testEoqZeroNetDemand() {
        // netDemand=0 -> should return 0 (no order needed)
        LotSizingStrategy strategy = new EconomicOrderQtyStrategy();
        BigDecimal result = strategy.calculate(BigDecimal.ZERO, BigDecimal.ZERO,
            Map.of(
                "annualDemand", new BigDecimal("1200"),
                "orderCost", new BigDecimal("500"),
                "holdingCostPerUnit", new BigDecimal("10")
            ));
        assertEquals(0, BigDecimal.ZERO.compareTo(result));
    }

    @Test
    void testFactoryResolvesCorrectly() {
        LotForLotStrategy lfl = new LotForLotStrategy();
        FixedOrderQtyStrategy foq = new FixedOrderQtyStrategy();
        EconomicOrderQtyStrategy eoq = new EconomicOrderQtyStrategy();

        LotSizingStrategyFactory factory = new LotSizingStrategyFactory(
            java.util.List.of(lfl, foq, eoq)
        );

        assertSame(lfl, factory.getStrategy("lfl"));
        assertSame(foq, factory.getStrategy("foq"));
        assertSame(eoq, factory.getStrategy("eoq"));
    }
}
