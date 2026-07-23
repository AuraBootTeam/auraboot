package com.auraboot.framework.application.bootstrap.seeder;

import com.auraboot.framework.agent.service.SystemAgentUserProvisioner;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds platform-level built-in Agent Skills and Agent Profile templates.
 * <p>
 * Uses the system tenant (tenant_id = 1).
 * AgentSkillService.listSkills() includes system-tenant skills as platform defaults.
 * <p>
 * Skills:
 * - approval_workflow  : approve/reject BPM tasks, query pending approvals
 * - data_entry_assistant: form fill, batch import, field validation
 * - report_analysis    : generate reports, describe charts, trend analysis
 * - crm_operations     : query contacts/leads/opportunities, log activities
 * - ops_inspector      : system monitoring, scheduled tasks, anomaly detection
 * <p>
 * Agent Profile Templates (3):
 * - aurabot_internal   : full-featured internal assistant (all skills)
 * - approval_assistant : focused on approval workflows, strict policy compliance
 * - customer_service   : external-facing, minimal permissions, RAG knowledge base
 * <p>
 * soul_profile is stored as structured JSONB with fields:
 * persona, values, tone, tone_description, boundaries, greeting, language_preference.
 * Parsed at runtime by SoulProfileParser.
 * <p>
 * Agent Identity (Microsoft Entra Agent ID pattern):
 * Each agent template is bound to a dedicated SYSTEM_AGENT user in ab_user.
 * The user email follows the convention: agent-{agentCode}@system.auraboot.local
 * This enables created_by/updated_by attribution for all agent-executed operations.
 * SYSTEM_AGENT users are excluded from normal human user lists via user_type filtering.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTemplateSeeder {

    private static final long SYSTEM_TENANT_ID = SystemTenantContextExecutor.SYSTEM_TENANT_ID;

    /** Email domain for synthetic agent system users — never used for login. */
    // Defined by SystemAgentUserProvisioner, which provisions the same account on demand when a
    // tenant-created agent is enrolled. Two literals would drift into two users for one agent.
    public static final String AGENT_USER_EMAIL_DOMAIN = SystemAgentUserProvisioner.EMAIL_DOMAIN;

    private final JdbcTemplate jdbcTemplate;

    public void seed() {
        seedSkills();
        seedAgentProfiles();
        bindAgentSystemUsers();
    }

    // =========================================================================
    // Skills
    // =========================================================================

    private void seedSkills() {
        // Always run upsert so subsequent boots refresh execution_config (e.g. when
        // we change the report_analysis thinking budget). The ON CONFLICT clause
        // guarantees idempotency and only touches execution_config + updated_at.
        String sql = """
                INSERT INTO ab_agent_skill
                (pid, tenant_id, skill_code, skill_name, skill_description, skill_level,
                 skill_category, skill_icon, skill_tools, prompt_template, execution_config,
                 is_builtin, skill_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, TRUE, 'active')
                ON CONFLICT (tenant_id, skill_code) DO UPDATE SET
                    execution_config = EXCLUDED.execution_config,
                    updated_at = CURRENT_TIMESTAMP
                """;

        // Default execution_config for skills without special opt-in.
        String defaultExecutionConfig = "{}";
        // report_analysis is a multi-hop reasoning skill — opt it in to Anthropic
        // Extended Thinking by default. Read by StepLoopService.resolveThinkingConfig
        // (mirrors ab_agent_definition.execution_config contract).
        String reportAnalysisExecutionConfig =
                "{\"thinking_enabled\":true,\"thinking_budget_tokens\":8000}";

        Object[][] skills = {
            // approval_workflow
            {
                UniqueIdGenerator.generate(), SYSTEM_TENANT_ID,
                "approval_workflow", "审批工作流助手", "处理审批任务：查询待审批列表、审批通过/拒绝、催办提醒",
                "workflow", "approval", "IconClipboardCheck",
                "[\"query_pending_approvals\",\"approve_task\",\"reject_task\",\"notify_approver\"]",
                """
                你是审批助手。职责：
                1. 帮用户查询待审批事项
                2. 根据用户指令执行审批通过或拒绝操作
                3. 审批金额超过10万时，提醒需总经理二次确认
                4. 拒绝时必须要求填写拒绝原因
                始终用中文回复，审批操作前先确认关键信息。

                Sub-agent delegation: when the user asks for an independent
                side-task that can run in parallel with the current approval
                conversation (for example, "also generate a monthly approval
                summary report while I keep reviewing this batch"), call the
                tool `platform.delegate_task` with `{ "subtaskMessage":
                "<clear description of the side-task>" }` and optionally
                `agentCode` to override the child agent. The call requires
                user approval before the child run starts; the parent
                conversation does not block on the child. Do NOT delegate the
                user's primary approval intent itself.
                """,
                defaultExecutionConfig
            },
            // data_entry_assistant
            {
                UniqueIdGenerator.generate(), SYSTEM_TENANT_ID,
                "data_entry_assistant", "数据录入助手", "协助填写表单、批量导入数据、字段验证和纠错",
                "workflow", "productivity", "IconForms",
                "[\"validate_form_data\",\"batch_import\",\"suggest_field_values\"]",
                """
                你是数据录入助手。职责：
                1. 引导用户逐步填写表单字段
                2. 对输入数据进行格式验证和合规检查
                3. 发现错误时给出具体修正建议
                4. 支持从Excel/CSV批量导入并预检数据质量
                始终保持耐心，对错误给出清晰的修正指引。

                Sub-agent delegation: when the user requests a long-running
                independent side-task (for example, "kick off a bulk import
                of this 50k-row CSV in the background while I keep entering
                today's records"), invoke `platform.delegate_task` with
                `{ "subtaskMessage": "<describe the import / validation
                job>" }` and optionally `agentCode`. The call requires
                user approval before the child run starts; the parent
                conversation does not block on the child. Use only for
                clearly separable side-tasks, not for the field the user
                is currently editing.
                """,
                defaultExecutionConfig
            },
            // report_analysis — opt-in Extended Thinking for multi-hop analysis
            {
                UniqueIdGenerator.generate(), SYSTEM_TENANT_ID,
                "report_analysis", "报表分析助手", "生成业务报表、解读图表数据、发现趋势与异常",
                "workflow", "analytics", "IconChartBar",
                "[\"generate_report\",\"query_aggregated_data\",\"describe_chart\",\"detect_anomaly\"]",
                """
                你是数据分析助手。职责：
                1. 根据用户需求查询并汇总业务数据
                2. 用简洁的语言解读图表和趋势
                3. 主动指出数据异常和关键变化点
                4. 提供数据驱动的业务建议
                数据查询结果优先以表格形式呈现，关键数字加粗标注。

                Sub-agent delegation: when the user asks for an independent
                heavy analysis side-task (for example, "delegate a full-year
                aggregation across all regions while I keep reviewing this
                quarter's chart"), call `platform.delegate_task` with
                `{ "subtaskMessage": "<describe the aggregation / report
                job>" }` and optionally `agentCode`. The call requires user
                approval before the child run starts; the parent
                conversation does not block on the child. Do not delegate
                cheap inline queries that finish quickly in the current
                turn.
                """,
                reportAnalysisExecutionConfig
            },
            // crm_operations
            {
                UniqueIdGenerator.generate(), SYSTEM_TENANT_ID,
                "crm_operations", "CRM销售助手", "查询客户/线索/商机信息，记录跟进活动，推进销售流程",
                "workflow", "crm", "IconUsers",
                "[\"query_customers\",\"query_leads\",\"query_opportunities\",\"log_activity\",\"update_stage\"]",
                """
                你是销售助手。职责：
                1. 快速查询客户、线索和商机信息
                2. 帮助记录拜访、通话、邮件等跟进活动
                3. 根据商机阶段给出推进建议
                4. 汇总销售漏斗数据和关键指标
                理解销售场景，用简洁的语言总结关键信息，避免信息过载。

                Sub-agent delegation: when the user asks for an independent
                bulk side-task (for example, "delegate a bulk export of all
                opportunities in stage=Negotiation while I keep logging
                today's calls"), invoke `platform.delegate_task` with
                `{ "subtaskMessage": "<describe the export / batch update
                job>" }` and optionally `agentCode`. The call requires user
                approval before the child run starts; the parent
                conversation does not block on the child. Do not delegate
                single-record lookups or activity logs.
                """,
                defaultExecutionConfig
            },
            // ops_inspector
            {
                UniqueIdGenerator.generate(), SYSTEM_TENANT_ID,
                "ops_inspector", "运营巡检助手", "系统健康检查、定时任务监控、异常告警和运营统计",
                "workflow", "operations", "IconActivityHeartbeat",
                "[\"check_system_health\",\"list_scheduled_tasks\",\"query_error_logs\",\"generate_ops_report\"]",
                """
                你是运营巡检助手。职责：
                1. 定期检查系统关键指标（任务执行率、错误率、响应时间）
                2. 发现异常时立即告警并给出排查建议
                3. 汇总运营日报/周报数据
                4. 监控定时任务执行状态
                对异常情况保持敏感，用数字和事实说话，不做主观猜测。

                Sub-agent delegation: when the user asks for an independent
                deep-dive side-task that should run alongside the current
                inspection (for example, "delegate a full error-log scan
                across the last 7 days while I keep watching today's
                metrics"), call `platform.delegate_task` with
                `{ "subtaskMessage": "<describe the scan / report job>" }`
                and optionally `agentCode`. The call requires user
                approval before the child run starts; the parent
                conversation does not block on the child. Do not delegate
                routine health checks that complete in the current turn.
                """,
                defaultExecutionConfig
            },
        };

        int count = 0;
        for (Object[] skill : skills) {
            count += jdbcTemplate.update(sql, skill[0], skill[1], skill[2], skill[3],
                    skill[4], skill[5], skill[6], skill[7], skill[8], skill[9], skill[10]);
        }
        log.info("AgentTemplateSeeder: upserted {} built-in skills (re-applies execution_config)", count);

        seedOrchestrationSkills();
    }

    /**
     * Orchestration-mode skills are the "asset layer" of a digital employee:
     * a governed playbook (prompt_template) that the LLM follows within a
     * bounded, real tool set (skill_tools) and a step ceiling (max_steps). See
     * DDR-2026-07-23 "digital employee boundary and the skill asset layer".
     *
     * <p>Unlike the five template skills above (which wrap a single tool and
     * carry placeholder tool codes), these reference <em>real registered tool
     * codes</em> resolved by {@code DslToolProvider} ({@code nq:}/{@code cmd:}
     * prefixes) so they actually run against the system of record. This first
     * one — the quarterly customer-structure review — is deliberately
     * read-only ({@code actionability=read_only}, no create/update tool in
     * {@code skill_tools}): it analyses real accounts and <em>proposes</em>
     * follow-up actions in prose, honouring the "propose, don't execute"
     * boundary. The heavy reasoning is delegated to the LLM; the platform owns
     * the governed read, the step ceiling, and the audit trail.
     */
    private void seedOrchestrationSkills() {
        String sql = """
                INSERT INTO ab_agent_skill
                (pid, tenant_id, skill_code, skill_name, skill_description, skill_level,
                 skill_category, skill_icon, skill_tools, prompt_template, execution_mode,
                 max_steps, actionability, output_type, declared_effects, is_builtin, skill_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'orchestration',
                        ?, 'read_only', 'text', ?::jsonb, TRUE, 'active')
                ON CONFLICT (tenant_id, skill_code) DO UPDATE SET
                    skill_name = EXCLUDED.skill_name,
                    skill_description = EXCLUDED.skill_description,
                    skill_tools = EXCLUDED.skill_tools,
                    prompt_template = EXCLUDED.prompt_template,
                    execution_mode = EXCLUDED.execution_mode,
                    max_steps = EXCLUDED.max_steps,
                    actionability = EXCLUDED.actionability,
                    declared_effects = EXCLUDED.declared_effects,
                    updated_at = CURRENT_TIMESTAMP
                """;

        String reviewPlaybook = """
                你是「客户运营复盘专员」。这是一个固定的季度客户结构复盘 playbook,严格按步骤执行,不要跳步、不要即兴发挥:

                1. 调用 nq:crm_account_list 拉取全部客户。只使用查询返回的真实记录,严禁编造客户名、数字或评级。
                2. 按行业分组统计:每个行业的客户数与占比。
                3. 按评级分组统计:A/B/C 各级客户数与占比,并算出 A 级客户占比。
                4. 识别结构性风险:① 客户过度集中在单一行业(占比 > 40%);② 低评级(C)客户占比偏高;③ 某行业只剩 1 家客户(一旦流失即出现空白)。
                5. 输出一份结构化复盘:
                   - 【总览】客户总数、行业分布、评级分布(关键数字加粗)
                   - 【结构风险】逐条列出识别到的风险,并指名具体是哪些客户或行业
                   - 【建议动作】针对薄弱行业与低评级客户,给出可执行的拓客/升级建议,每条以「建议:」开头

                边界(必须遵守):
                - 你只做分析与「提议」。禁止调用任何写入/创建/修改类工具;所有拓客动作以文字建议给出,由人决定是否执行。
                - 所有结论必须基于 nq:crm_account_list 返回的真实数据,数字精确到实际值。
                - 用简体中文回复。
                """;

        int count = jdbcTemplate.update(sql,
                UniqueIdGenerator.generate(), SYSTEM_TENANT_ID,
                "crm_quarterly_review", "季度客户结构复盘",
                "拉取全部客户,按行业与评级分析结构集中度与风险,产出结构化复盘并提议拓客动作(只读,不自动执行)",
                "workflow", "crm", "IconReportAnalytics",
                "[\"nq:crm_account_list\"]",
                reviewPlaybook,
                6,
                "[\"read:crm_account\"]");
        log.info("AgentTemplateSeeder: upserted {} orchestration skill(s)", count);
    }

    // =========================================================================
    // Agent Profile Templates
    // =========================================================================

    private void seedAgentProfiles() {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_definition WHERE tenant_id = ? " +
                "AND deleted_flag = FALSE",
                Integer.class,
                SYSTEM_TENANT_ID);
        if (existing != null && existing > 0) {
            log.info("AgentTemplateSeeder: skipped agent profiles (already seeded: {})", existing);
            return;
        }

        String sql = """
                INSERT INTO ab_agent_definition
                (pid, tenant_id, agent_code, name, description, agent_type, model,
                 system_prompt, skills, soul_profile, communication_style, status)
                VALUES (?, ?, ?, ?, ?, 'reactive', ?, ?, ?, ?::jsonb, ?, 'active')
                ON CONFLICT DO NOTHING
                """;

        Object[][] agents = {
            // Template 1: AuraBot Internal — full-featured internal assistant
            {
                UniqueIdGenerator.generate(), SYSTEM_TENANT_ID,
                "tpl_aurabot_internal", "AuraBot 全功能助手",
                "内部全功能AI助手模板，适合管理员和超级用户。掌握所有内置技能，可执行任意操作。",
                "claude-sonnet-4-6",
                """
                你是 AuraBot，{{tenantName}} 的智能企业助手。
                你帮助用户理解业务数据、建议操作方案，并在授权范围内执行操作。
                始终用用户的语言回复（默认中文）。回答简洁、专业、有帮助。
                对于写操作，执行前先描述将要做的事，获得用户确认后再执行。
                """,
                "approval_workflow,data_entry_assistant,report_analysis,crm_operations,ops_inspector,crm_quarterly_review",
                """
                {
                  "persona": "AuraBot, a versatile enterprise AI assistant focused on process optimization",
                  "values": ["efficiency", "data-driven", "compliance-first"],
                  "tone": "professional",
                  "tone_description": "Professional yet approachable, concise, avoids unnecessary filler",
                  "boundaries": [
                    "Never perform operations beyond authorized scope — proactively inform the user",
                    "Never repeat sensitive data in output"
                  ],
                  "greeting": "Hello, I'm AuraBot. How can I help you today?",
                  "language_preference": "zh-CN"
                }
                """,
                "professional"
            },
            // Template 2: Approval Assistant — focused on BPM approval workflows
            {
                UniqueIdGenerator.generate(), SYSTEM_TENANT_ID,
                "tpl_approval_assistant", "审批助手",
                "专注审批流程的AI助手模板。严守审批政策边界，适合业务审批场景。",
                "claude-haiku-4-5-20251001",
                """
                你是审批助手，专门协助处理 {{tenantName}} 的审批工作。
                你的职责：
                1. 快速查询待审批事项，摘要关键信息
                2. 根据审批政策给出建议（通过/拒绝/转交）
                3. 执行审批操作（需用户最终确认）
                4. 超时提醒和催办
                审批政策边界：金额超10万需总经理审批；拒绝必须填写原因。
                """,
                "approval_workflow",
                """
                {
                  "persona": "A specialized approval workflow assistant, focused on policy compliance",
                  "values": ["accuracy", "policy-compliance", "timeliness"],
                  "tone": "formal",
                  "tone_description": "Formal and precise, always cites relevant policies",
                  "boundaries": [
                    "Only process approvals within delegated authority",
                    "Always explain rejection reasons clearly"
                  ],
                  "greeting": "I'm your Approval Assistant. I can help review and process pending approvals.",
                  "language_preference": "zh-CN"
                }
                """,
                "formal"
            },
            // Template 3: Customer Service Bot — external-facing, minimal permissions
            {
                UniqueIdGenerator.generate(), SYSTEM_TENANT_ID,
                "tpl_customer_service", "客服机器人",
                "对外部署的客服AI助手模板。权限最小化，专注客户问题解决，支持RAG知识库问答。",
                "claude-haiku-4-5-20251001",
                """
                你是 {{tenantName}} 的客服助手，负责解答客户咨询和处理常见问题。
                你的职责：
                1. 耐心解答产品和服务相关问题
                2. 查询客户订单状态和历史记录
                3. 记录客户反馈和投诉
                4. 无法解决时引导转接人工客服
                只处理客户服务相关问题，不涉及内部管理数据。
                """,
                "crm_operations",
                """
                {
                  "persona": "A patient and empathetic customer service agent",
                  "values": ["customer-first", "patience", "problem-resolution"],
                  "tone": "friendly",
                  "tone_description": "Warm and patient, acknowledges customer frustration before problem-solving",
                  "boundaries": [
                    "Never share internal system details with customers",
                    "Escalate to human agent when unable to resolve within 3 attempts"
                  ],
                  "greeting": "Hi! I'm here to help. What can I do for you?",
                  "language_preference": "zh-CN"
                }
                """,
                "friendly"
            },
        };

        int count = 0;
        for (Object[] agent : agents) {
            count += jdbcTemplate.update(sql, agent[0], agent[1], agent[2], agent[3],
                    agent[4], agent[5], agent[6], agent[7], agent[8], agent[9]);
        }
        log.info("AgentTemplateSeeder: seeded {} agent profile templates (skipped {} existing)",
                count, agents.length - count);
    }

    // =========================================================================
    // Agent Identity — bind each agent template to a dedicated SYSTEM_AGENT user
    // =========================================================================

    /**
     * For each agent template in tenant 0, ensures a dedicated SYSTEM_AGENT user exists
     * and links it via system_user_id.
     * <p>
     * Conventions:
     * <ul>
     *   <li>Email: {@code agent-{agentCode}@system.auraboot.local}</li>
     *   <li>nick_name: {@code "Agent: {agentName}"}</li>
     *   <li>user_type: {@code "system_agent"} — excluded from human user lists</li>
     *   <li>is_enabled = FALSE — cannot log in, JWT auth will reject these accounts</li>
     * </ul>
     * Idempotent: re-runs safely, existing bindings are skipped.
     */
    private void bindAgentSystemUsers() {
        // Load agent templates that don't yet have a system_user_id, plus stale
        // bindings whose user row was removed by reset/test cleanup.
        var agentsToProcess = jdbcTemplate.queryForList(
                "SELECT a.id, a.agent_code, a.name FROM ab_agent_definition a " +
                "LEFT JOIN ab_user u ON u.id = a.system_user_id " +
                "WHERE a.tenant_id = ? AND a.deleted_flag = FALSE " +
                "AND (a.system_user_id IS NULL OR u.id IS NULL)",
                SYSTEM_TENANT_ID);

        if (agentsToProcess.isEmpty()) {
            log.info("AgentTemplateSeeder: skipped system user binding (all agents already bound)");
            return;
        }

        int bound = 0;
        for (var agent : agentsToProcess) {
            Long agentId       = ((Number) agent.get("id")).longValue();
            String code        = (String) agent.get("agent_code");
            String name        = (String) agent.get("name");
            String email       = "agent-" + code + AGENT_USER_EMAIL_DOMAIN;
            String displayName = "Agent: " + name;

            Long userId = ensureSystemAgentUser(email, displayName);
            jdbcTemplate.update(
                    "UPDATE ab_agent_definition SET system_user_id = ? WHERE id = ?",
                    userId, agentId);
            log.info("AgentTemplateSeeder: bound agent '{}' -> system user id={} email={}",
                    code, userId, email);
            bound++;
        }
        log.info("AgentTemplateSeeder: bound {} agent(s) to system users", bound);
    }

    /**
     * Creates or retrieves the SYSTEM_AGENT user for a given agent email.
     * The user has no password, is_enabled=FALSE (cannot log in), and user_type=SYSTEM_AGENT.
     *
     * @return the user ID (existing or newly created)
     */
    private Long ensureSystemAgentUser(String email, String displayName) {
        // Check if the system user already exists
        var rows = jdbcTemplate.queryForList(
                "SELECT id FROM ab_user WHERE email = ? LIMIT 1", email);
        if (!rows.isEmpty()) {
            Long userId = ((Number) rows.get(0).get("id")).longValue();
            jdbcTemplate.update("""
                    UPDATE ab_user
                       SET nick_name = ?,
                           is_enabled = FALSE,
                           user_type = 'system_agent',
                           updated_at = NOW()
                     WHERE id = ?
                    """, displayName, userId);
            return userId;
        }

        // Generate Snowflake ID consistent with MyBatis Plus ASSIGN_ID strategy
        long newId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        String pid = UniqueIdGenerator.generate();

        jdbcTemplate.update("""
                INSERT INTO ab_user
                    (id, pid, email, nick_name, is_enabled, is_account_non_expired,
                     is_account_non_locked, is_credentials_non_expired,
                     user_type, created_at, updated_at)
                VALUES (?, ?, ?, ?, FALSE, TRUE, TRUE, TRUE, 'system_agent', NOW(), NOW())
                """,
                newId, pid, email, displayName);

        return newId;
    }
}
