package com.auraboot.framework.category.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "auraboot.category")
public class CategoryProperties {

    private Map<String, Integer> maxLevel = defaultMaxLevel();

    public Map<String, Integer> getMaxLevel() {
        return maxLevel;
    }

    public void setMaxLevel(Map<String, Integer> maxLevel) {
        this.maxLevel = maxLevel == null ? defaultMaxLevel() : new LinkedHashMap<>(maxLevel);
    }

    private Map<String, Integer> defaultMaxLevel() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("default", 2);
        levels.put("commerce_product", 5);
        return levels;
    }
}
