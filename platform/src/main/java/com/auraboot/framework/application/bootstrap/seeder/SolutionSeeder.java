package com.auraboot.framework.application.bootstrap.seeder;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class SolutionSeeder {
    private final JdbcTemplate jdbcTemplate;

    public void seed() {
        String sql = """
                INSERT INTO ab_marketplace_solution (
                    pid, code, name, name_zh, name_en, description,
                    description_zh, description_en,
                    industry, plugin_codes, icon_url,
                    price_type, status, featured, sort_order,
                    readme_markdown, tags, created_at, updated_at, published_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (code) DO NOTHING
                """;

        Timestamp now = Timestamp.from(Instant.now());

        Object[][] solutions = {
            {
                UniqueIdGenerator.generate(),
                "pcba-manufacturing",
                "PCBA Manufacturing Solution",
                "PCBA 电子制造解决方案",
                "PCBA Manufacturing Solution",
                "Complete solution for PCBA manufacturing including BOM management, process control, quality inspection, and inventory tracking.",
                "完整的 PCBA 电子制造解决方案，包含 BOM 管理、工序控制、质量检测和库存追踪。",
                "Complete solution for PCBA manufacturing including BOM management, process control, quality inspection, and inventory tracking.",
                "manufacturing",
                "[\"pcba-base\",\"pcba-crm\",\"pcba-industry\",\"inventory\",\"quality\"]",
                null,
                "free",
                "published",
                true,
                10,
                "# PCBA Manufacturing Solution\n\nA comprehensive solution for printed circuit board assembly manufacturing.\n\n## Included Plugins\n- **PCBA Base** — Core PCBA models and workflows\n- **PCBA CRM** — Customer management for electronics manufacturing\n- **PCBA Industry** — Industry-specific fields and extensions\n- **Inventory** — Stock management and warehouse operations\n- **Quality** — Quality control and inspection workflows\n\n## Use Cases\n- SMT production line management\n- BOM and component tracking\n- Incoming/outgoing quality inspection\n- Customer order and delivery management",
                "[\"pcba\",\"manufacturing\",\"electronics\",\"smt\"]",
                now, now, now
            },
            {
                UniqueIdGenerator.generate(),
                "project-management-suite",
                "Project Management Suite",
                "项目管理套件",
                "Project Management Suite",
                "Full project management toolkit with task tracking, document management, and team collaboration.",
                "完整的项目管理工具包，包含任务跟踪、文档管理和团队协作。",
                "Full project management toolkit with task tracking, document management, and team collaboration.",
                "general",
                "[\"project-management\",\"doc-knowledge\"]",
                null,
                "free",
                "published",
                true,
                20,
                "# Project Management Suite\n\nEverything you need to manage projects efficiently.\n\n## Included Plugins\n- **Project Management** — Projects, tasks, milestones, Kanban boards\n- **Document & Knowledge** — Document library, knowledge base, wiki\n\n## Use Cases\n- Software development project tracking\n- Product launch planning\n- Team task management\n- Knowledge sharing and documentation",
                "[\"project\",\"task\",\"document\",\"collaboration\"]",
                now, now, now
            },
            {
                UniqueIdGenerator.generate(),
                "crm-quick-start",
                "CRM Quick Start",
                "CRM 快速入门",
                "CRM Quick Start",
                "Get started with customer relationship management including leads, opportunities, and sales pipeline.",
                "快速开始客户关系管理，包含线索、商机和销售管道。",
                "Get started with customer relationship management including leads, opportunities, and sales pipeline.",
                "general",
                "[\"crm\",\"sales\"]",
                null,
                "free",
                "published",
                true,
                30,
                "# CRM Quick Start\n\nA lightweight CRM solution to manage your sales pipeline.\n\n## Included Plugins\n- **CRM** — Leads, contacts, accounts, opportunities, activities\n- **Sales** — Sales orders, quotes, revenue tracking\n\n## Use Cases\n- Lead management and conversion\n- Sales pipeline visualization\n- Customer account management\n- Quote and order processing",
                "[\"crm\",\"sales\",\"pipeline\",\"leads\"]",
                now, now, now
            }
        };

        int count = 0;
        for (Object[] sol : solutions) {
            count += jdbcTemplate.update(sql,
                sol[0], sol[1], sol[2], sol[3], sol[4], sol[5],
                sol[6], sol[7], sol[8], sol[9], sol[10],
                sol[11], sol[12], sol[13], sol[14], sol[15],
                sol[16], sol[17], sol[18], sol[19]);
        }
        log.info("SolutionSeeder: seeded {} solutions (skipped {} existing)", count, solutions.length - count);
    }
}
