-- ============================================================
-- CS Agent Seed Data: Customer Service Agent + Approval Policy + Custom Tool
-- Run: psql -h localhost -U ghj -d aura_boot -f scripts/seed-cs-agent.sql
-- Idempotent: uses ON CONFLICT to skip duplicates
-- Tenant: uses the tenant that has CRM commands published
-- ============================================================

-- Helper: find the tenant that has CRM plugin installed (has crm: commands)
-- If none found, fall back to MIN(id) from non-System tenant
DO $$
DECLARE
    v_tid BIGINT;
BEGIN
    SELECT DISTINCT tenant_id INTO v_tid
    FROM ab_command_definition WHERE code LIKE 'crm:%' LIMIT 1;
    IF v_tid IS NULL THEN
        SELECT MIN(id) INTO v_tid FROM ab_tenant WHERE name != 'System';
    END IF;
    IF v_tid IS NULL THEN
        SELECT MIN(id) INTO v_tid FROM ab_tenant;
    END IF;
    PERFORM set_config('app.seed_tenant_id', v_tid::TEXT, false);
    RAISE NOTICE 'Using tenant_id: %', v_tid;
END $$;

-- ============================================================
-- CUSTOM TOOL: send_customer_reply
-- ============================================================

INSERT INTO ab_agent_tool (
    pid, tenant_id, tool_code, tool_type, tool_name, tool_description,
    input_schema, requires_approval, risk_level, autonomy_level,
    tool_status, auto_generated, created_at, updated_at
)
VALUES (
    'tool_send_cs_reply_001',
    current_setting('app.seed_tenant_id')::BIGINT,
    'send_customer_reply',
    'built_in',
    'Send Customer Reply Email',
    'Send a professional reply email to the customer regarding their complaint. This action sends an actual email and requires approval before execution.',
    '{"type":"object","properties":{"complaint_id":{"type":"string","description":"The complaint record ID (pid) to associate the reply with"},"recipient_email":{"type":"string","format":"email","description":"Customer email address to send the reply to"},"reply_subject":{"type":"string","description":"Email subject line, typically Re: [original subject]"},"reply_body":{"type":"string","description":"Full professional response body addressing the customer issue"}},"required":["complaint_id","recipient_email","reply_subject","reply_body"]}',
    true,
    'L2',
    'yellow',
    'active',
    false,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (pid) DO NOTHING;

-- ============================================================
-- APPROVAL POLICY: CS Agent Reply Approval
-- ============================================================

INSERT INTO ab_approval_policy (
    pid, tenant_id, policy_name, description,
    trigger_rules, approver_rules,
    auto_approve, timeout_hours, timeout_action,
    policy_status, created_at, updated_at
)
VALUES (
    'policy_cs_reply_001',
    current_setting('app.seed_tenant_id')::BIGINT,
    'CS Agent Reply Approval',
    'Requires human approval before the CS Agent sends any customer reply email. Ensures reply quality and prevents unauthorized communications.',
    '[{"type":"tool_call","pattern":"custom:send_customer_reply"}]',
    '[{"type":"role","roleCode":"TENANT_ADMIN"}]',
    false,
    24,
    'reject',
    'active',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (pid) DO NOTHING;

-- ============================================================
-- AGENT DEFINITION: cs_agent
-- ============================================================

INSERT INTO ab_agent_definition (
    pid, tenant_id, agent_code, name, description,
    agent_type, model,
    system_prompt, tools,
    execution_timeout_seconds,
    status, visibility,
    created_at, updated_at
)
VALUES (
    'agent_cs_001',
    current_setting('app.seed_tenant_id')::BIGINT,
    'cs_agent',
    'Customer Service Agent',
    'Automated customer service agent that processes inbound customer emails, creates and manages complaints in CRM, and drafts professional replies pending human approval.',
    'reactive',
    'deepseek',
    'You are a professional customer service agent. When processing an inbound customer email:

1. IDENTIFY: Look up the customer by their email address using get:crm_contact. Then get their account using get:crm_account.
2. HISTORY: Check their complaint history using list:crm_complaint with account_id filter.
3. ASSESS: Analyze the email content to determine if this is a new issue or relates to an existing complaint.
4. CREATE: If this is a new issue, create a complaint via cmd:crm:create_complaint with:
   - crm_cmp_account_id: the customer account ID
   - crm_cmp_contact_id: the contact ID (if found)
   - crm_cmp_date: today''s date (YYYY-MM-DD format)
   - crm_cmp_type: choose from product_defect, service_issue, billing_dispute, delivery_problem, other
   - crm_cmp_severity: choose from low, medium, high, critical based on urgency
   - crm_cmp_description: summarize the customer''s issue
5. INVESTIGATE: Transition to investigating via cmd:crm:investigate_complaint.
6. REPLY: Draft a professional, empathetic reply. Use custom:send_customer_reply with:
   - complaint_id: the complaint record ID
   - recipient_email: customer email address
   - reply_subject: Re: [original subject]
   - reply_body: professional response addressing the issue
7. RESOLVE: Resolve via cmd:crm:resolve_complaint with root_cause and corrective_action.
8. CLOSE: Close via cmd:crm:close_complaint.

Always be professional. Reference the customer by name if known. If they have previous complaints, acknowledge them.',
    'get:crm_account,get:crm_contact,list:crm_complaint,get:crm_complaint,cmd:crm:create_complaint,cmd:crm:update_complaint,cmd:crm:investigate_complaint,cmd:crm:resolve_complaint,cmd:crm:close_complaint,nq:crm_sla_status_breakdown,custom:send_customer_reply',
    120,
    'active',
    'private',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (pid) DO NOTHING;

-- Verify inserts
DO $$
DECLARE
    v_tid BIGINT;
    v_agent_count INT;
    v_policy_count INT;
    v_tool_count INT;
BEGIN
    v_tid := current_setting('app.seed_tenant_id')::BIGINT;

    SELECT COUNT(*) INTO v_agent_count FROM ab_agent_definition
        WHERE agent_code = 'cs_agent' AND tenant_id = v_tid
          AND (deleted_flag = FALSE OR deleted_flag IS NULL);

    SELECT COUNT(*) INTO v_policy_count FROM ab_approval_policy
        WHERE policy_name = 'CS Agent Reply Approval' AND tenant_id = v_tid
          AND (deleted_flag = FALSE OR deleted_flag IS NULL);

    SELECT COUNT(*) INTO v_tool_count FROM ab_agent_tool
        WHERE tool_code = 'send_customer_reply' AND tenant_id = v_tid
          AND (deleted_flag = FALSE OR deleted_flag IS NULL);

    RAISE NOTICE 'CS Agent seed complete — agent: %, approval_policy: %, custom_tool: %',
        v_agent_count, v_policy_count, v_tool_count;
END $$;
