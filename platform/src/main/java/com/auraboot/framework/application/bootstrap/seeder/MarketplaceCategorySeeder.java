package com.auraboot.framework.application.bootstrap.seeder;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketplaceCategorySeeder {
    private final JdbcTemplate jdbcTemplate;

    public void seed() {
        String sql = """
                INSERT INTO ab_marketplace_category (pid, code, display_name_zh, display_name_en, icon, sort_order)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (code) DO NOTHING
                """;

        Object[][] categories = {
            {UniqueIdGenerator.generate(), "crm", "客户关系管理", "crm", "IconUsers", 10},
            {UniqueIdGenerator.generate(), "erp", "企业资源计划", "erp", "IconBuilding", 20},
            {UniqueIdGenerator.generate(), "finance", "财务管理", "Finance", "IconCurrencyDollar", 30},
            {UniqueIdGenerator.generate(), "hr", "人力资源", "HR", "IconUserGroup", 40},
            {UniqueIdGenerator.generate(), "project-management", "项目管理", "Project Management", "IconClipboardList", 50},
            {UniqueIdGenerator.generate(), "ai", "人工智能", "AI", "IconSparkles", 60},
            {UniqueIdGenerator.generate(), "integration", "系统集成", "Integration", "IconLink", 70},
            {UniqueIdGenerator.generate(), "utility", "工具", "Utility", "IconWrench", 80},
            {UniqueIdGenerator.generate(), "industry", "行业解决方案", "Industry Solutions", "IconFactory", 90},
        };

        int count = 0;
        for (Object[] category : categories) {
            count += jdbcTemplate.update(sql, category[0], category[1], category[2], category[3], category[4], category[5]);
        }
        log.info("MarketplaceCategorySeeder: seeded {} categories (skipped {} existing)", count, categories.length - count);
    }
}
