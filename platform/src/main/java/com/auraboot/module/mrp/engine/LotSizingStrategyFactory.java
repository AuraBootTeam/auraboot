package com.auraboot.module.mrp.engine;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class LotSizingStrategyFactory {

    private final Map<String, LotSizingStrategy> strategies;
    private final LotSizingStrategy defaultStrategy;

    public LotSizingStrategyFactory(List<LotSizingStrategy> strategyList) {
        this.strategies = strategyList.stream()
            .collect(Collectors.toMap(LotSizingStrategy::name, Function.identity()));
        this.defaultStrategy = strategies.getOrDefault("lfl", strategyList.get(0));
    }

    public LotSizingStrategy getStrategy(String name) {
        return strategies.getOrDefault(name, defaultStrategy);
    }
}
