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
