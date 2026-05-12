# AI Features (AuraBot & ChatBI)

AuraBoot integrates AI capabilities throughout the platform under the **AuraBot** brand. From conversational data analysis to autonomous AI workers, every AI feature shares a unified LLM provider infrastructure.

## Goal

By the end of this guide you will understand AuraBoot's AI feature set and be able to configure an LLM provider, use ChatBI for natural language data queries, and interact with the AuraBot copilot.

## Prerequisites

- AuraBoot running locally
- An API key from at least one supported LLM provider
- Admin account access

## AI Feature Map

```
+------------------------------------------------------------------+
|                     User-Facing AI Features                       |
+------------------+------------------+----------------------------+
|   AuraBot Panel  |    ChatBI        |  RAG Knowledge Base         |
|   (Cmd+J)        |    (/chat-bi)    |  (Document Q&A)             |
|   SSE streaming  |    NL -> Charts  |  Vector search + answers    |
+--------+---------+--------+---------+-------------+--------------+
         |                  |                       |
+--------v------------------v-----------------------v--------------+
|                    Agent Control Plane (ACP)                      |
|   Agent Definitions | Tools | Skills | Memory | Missions          |
|   Plan-Execute-Adapt loop | Approval policies | Cost governance   |
+------------------------------------------------------------------+
|                    LLM Provider Infrastructure                    |
|   CloudConfig (ab_cloud_config) | Multi-provider support          |
|   Auto-discovery | Encrypted API key storage                     |
+------------------------------------------------------------------+
```

## 1. LLM Provider Configuration

All AI features share a single provider configuration stored in `ab_cloud_config`.
Use lower-case `config_level` / `service_type` values (`platform`, `tenant`, `llm`).
The admin API normalizes `serviceType` on lookup, but stored rows and examples should stay lower-case.
LLM `apiKey` values are encrypted at rest and masked in admin responses.

### Supported Providers

| Provider | Code | API Format | Default Model |
|----------|------|-----------|---------------|
| Anthropic (Claude) | `anthropic` | Messages API | `claude-sonnet-4-6` |
| OpenAI | `openai` | Chat Completions | `gpt-4o` |
| DeepSeek | `deepseek` | Chat Completions | `deepseek-chat` |
| Alibaba Qwen | `qianwen` | Chat Completions | `qwen-plus` |
| Zhipu GLM | `zhipu` | Chat Completions | `glm-4` |
| Moonshot | `moonshot` | Chat Completions | `moonshot-v1-8k` |
| Groq | `groq` | Chat Completions | `llama-3.3-70b-versatile` |
| Mistral AI | `mistral` | Chat Completions | `mistral-large-latest` |

Any OpenAI Chat Completions-compatible provider can be added as a custom provider.

### Configure via UI

1. Navigate to **AuraBot Management > LLM Providers** (`/aurabot/providers`)
2. The page shows a card grid of configured providers
3. Click **Add Provider** and select from the preset list (or choose "Custom OpenAI Compatible")
4. Enter:
   - **API Key** (automatically encrypted at rest)
   - **Base URL** (optional, defaults to provider's standard URL)
   - **Default Model** (optional, defaults to provider's recommended model)
   - **Max Tokens** (optional, default 4096)
5. Click **Test Connection** to verify
6. Click **Save**

### Configure via Database

```sql
INSERT INTO ab_cloud_config (pid, config_level, service_type, provider_code, config, enabled)
VALUES (
  '01EXAMPLE', 'platform', 'llm', 'anthropic',
  '{"apiKey": "sk-ant-xxx", "apiFormat": "messages", "baseUrl": "https://api.anthropic.com", "defaultModel": "claude-sonnet-4-6", "maxTokens": 4096}',
  true
);
```

OpenAI-compatible providers use `apiFormat: "chat_completions"`:

```sql
INSERT INTO ab_cloud_config (pid, config_level, service_type, provider_code, config, enabled)
VALUES (
  '01EXAMPLE2', 'tenant', 'llm', 'deepseek',
  '{"apiKey": "sk-xxx", "apiFormat": "chat_completions", "baseUrl": "https://api.deepseek.com", "defaultModel": "deepseek-chat", "maxTokens": 4096}',
  true
);
```

### Configure via application.yml (Anthropic only)

```yaml
agent:
  anthropic:
    api-key: ${ANTHROPIC_API_KEY:}
    base-url: https://api.anthropic.com
    default-model: claude-sonnet-4-6
    max-tokens: 4096
```

### Resolution Priority

```
1. CloudConfig (ab_cloud_config, service_type='llm')
   |
   v (not found)
2. application.yml (agent.anthropic.*, Anthropic only)
   |
   v (not found)
3. null -> AI features disabled with "LLM provider not configured" message
```

### Automatic Provider Detection

When an Agent definition specifies a model name, the system auto-detects the provider:

| Model contains | Detected provider |
|---------------|-------------------|
| `claude` | `anthropic` |
| `gpt`, `o1`, `o3`, `o4` | `openai` |
| `deepseek` | `deepseek` |
| `qwen` | `qianwen` |
| `glm` | `zhipu` |
| `moonshot` | `moonshot` |

### Validate Provider Configuration

The cloud-config test endpoint validates LLM config shape without making a paid model call:

```bash
curl -X POST "$BASE_URL/api/admin/cloud-config/$PID/test" \
  -H "Authorization: Bearer $TOKEN"
```

Expected response for a structurally valid provider:

```json
{"code":"0","data":{"status":"ok","message":"LLM provider config validated for apiFormat=messages"}}
```

For live smoke, configure a real key, open AuraBot, send a short prompt, and confirm `/api/agent/providers/configured` lists the tenant provider. Missing keys should return an explicit "LLM provider not configured" error instead of falling back silently.

## 2. AuraBot -- In-App AI Copilot

AuraBot is a context-aware AI assistant that lives as a slide-in panel on the right side of the screen.

### Opening AuraBot

- Click the **AI icon** in the header bar (always visible)
- Or press `Cmd+J` (macOS) / `Ctrl+J` (Windows/Linux)

### Context Awareness

AuraBot automatically detects the current page context:

| Context | Source | Example |
|---------|--------|---------|
| Model code | URL / Page schema | `crm_opportunity` |
| Page kind | Page schema | `list`, `form`, `detail` |
| Record ID | URL parameters | Viewing a specific record |
| Field list | Model metadata | All fields with names and types |

Based on context, AuraBot suggests relevant quick actions like "Explain this record", "Help fill this form", or "Summarize this list".

### Tool Calling

AuraBot includes a **5-round tool loop** that can:

| Tool Category | Prefix | Behavior |
|--------------|--------|----------|
| Named queries | `nq_*` | Auto-executed (read-only) |
| List queries | `list_*` | Auto-executed (read-only) |
| Record lookups | `get_*` | Auto-executed (read-only) |
| Platform tools | `platform_*` | Auto-executed (SQL, model listing) |
| DSL Commands | `cmd_*` | **Requires user confirmation** (write operations) |
| Create Model | `platform_create_model` | **Requires user confirmation** |

**Read operations** execute automatically and show results in collapsible cards.

**Write operations** pause and show an amber confirmation card with a "Confirm" / "Cancel" button. The user must explicitly approve before execution.

### Example Conversation

```
User: How many opportunities are in each status?

AuraBot: [Executing tool: nq_crm_opportunity_by_status...]
         [ToolResultCard: bar chart showing New: 12, Qualified: 8, Won: 5, Lost: 3]

         Based on your CRM data, you have 28 total opportunities:
         - 12 in New status
         - 8 in Qualified
         - 5 Won
         - 3 Lost

User: Create a task to follow up on the qualified ones.

AuraBot: [ConfirmCard: Execute cmd:create_pm_task with title="Follow up qualified opps"?]
         [Confirm] [Cancel]

User: [clicks Confirm]

AuraBot: Task "Follow up qualified opps" has been created successfully.
```

### SSE Event Protocol

AuraBot communicates via Server-Sent Events:

| Event | Payload | Purpose |
|-------|---------|---------|
| `chunk` | `{content}` | Streaming text token |
| `tool_start` | `{toolId, toolName, input}` | Tool execution started |
| `tool_result` | `{toolId, result, success}` | Read-only tool result |
| `confirm_required` | `{toolId, toolName, description, input}` | Write operation needs approval |
| `done` | `{content}` | Stream complete |
| `error` | `{message}` | Error occurred |

### API Endpoint

```
POST /api/ai/aurabot/chat/stream
Content-Type: application/json
Authorization: Bearer {token}

{
  "message": "How many leads were created this week?",
  "sessionId": "session_abc123",
  "context": {
    "modelCode": "crm_lead",
    "kind": "list"
  }
}
```

Returns: `text/event-stream`

## 3. ChatBI -- Natural Language Data Analysis

ChatBI lets users ask questions about their data in plain English and get chart visualizations back.

### How It Works

```
Natural Language Question
  -> Model Resolution (auto-detect or explicit)
  -> Intent Parsing (keyword-based)
  -> SQL Generation
  -> Query Execution
  -> Chart Recommendation
  -> Visual Result
```

### Using ChatBI

Navigate to `/chat-bi` or use the ChatBI input in the AuraBot panel.

**Example queries:**

| Question | What happens |
|----------|-------------|
| "How many leads by status?" | COUNT grouped by status -> bar chart |
| "Total revenue by region" | SUM of amount grouped by region -> bar chart |
| "Top 10 accounts by deal value" | Top N sorted descending -> bar chart |
| "Monthly sales trend" | Time-series aggregation -> line chart |
| "Distribution of opportunity stages" | COUNT with few groups -> pie chart |
| "Show me all open tasks" | No aggregation -> data table |

### API

```
POST /api/ai/chat-bi/query
Content-Type: application/json

{
  "question": "top 10 orders by amount",
  "modelCode": "sales_order"
}
```

The `modelCode` field is optional. If omitted, ChatBI scans the first 100 published models and matches by keyword.

### Response

```json
{
  "interpretation": "Querying Sales Order - top 10 by sl_amount descending.",
  "modelCode": "sales_order",
  "columns": ["sl_order_code", "sl_amount"],
  "records": [
    { "sl_order_code": "SO-001", "sl_amount": 150000 },
    { "sl_order_code": "SO-002", "sl_amount": 120000 }
  ],
  "chartType": "bar",
  "chartConfig": {
    "type": "bar",
    "labelField": "sl_order_code",
    "valueField": "sl_amount"
  },
  "sql": "SELECT sl_order_code, sl_amount FROM mt_sales_order ORDER BY sl_amount DESC LIMIT 10",
  "total": 10
}
```

### Chart Type Selection Logic

| Condition | Chart Type |
|-----------|-----------|
| Trend query (time-based) | `line` |
| Aggregation + groupBy with <= 8 groups + COUNT | `pie` |
| Aggregation + groupBy (other) | `bar` |
| Aggregation only (no groupBy) | `bar` |
| No aggregation | `table` |

### Intent Keywords

| Intent | Keywords |
|--------|----------|
| COUNT | count, how many, number of, total count, quantity |
| SUM | sum, total, revenue, amount, value, sales |
| AVG | average, avg, mean, typical |
| GROUP | by, group by, per, breakdown, each, category, status, type |
| TOP | top, highest, largest, biggest, most |
| BOTTOM | bottom, lowest, smallest, least |
| TREND | trend, over time, monthly, daily, weekly, timeline, history |

## 4. RAG Knowledge Base

Upload documents and let users ask questions that are answered using vector search over your content.

### Capabilities

- Upload PDF, Word, Markdown, and text files
- Automatic chunking and vector embedding
- Semantic search across document content
- AI-generated answers with source citations

### Setup

1. Navigate to **AuraBot Management > Knowledge Base**
2. Create a new knowledge base with a name and description
3. Upload documents (drag-and-drop supported)
4. Documents are automatically chunked, embedded, and indexed
5. Users can query the knowledge base through AuraBot or a dedicated search interface

## 5. Agent Control Plane (ACP)

The ACP is a full-featured agent orchestration system for building autonomous AI workers.

### Key Components

| Component | Description | Route |
|-----------|-------------|-------|
| Agent Definitions | Define agent persona, tools, and guardrails | `/dynamic/agent-definition` |
| Tools | Register tools agents can use | `/dynamic/agent-tool` |
| Skills | Reusable agent capabilities | `/dynamic/agent-skill` |
| Memory | Agent conversation and context memory | `/dynamic/agent-memory` |
| Missions | High-level objectives for agents | `/dynamic/mission` |
| Tasks | Individual work items for agents | `/dynamic/agent-task` |
| Runs | Execution logs of agent work | `/dynamic/agent-run` |
| Schedules | Cron-based agent triggers | `/dynamic/agent-schedule` |
| Approvals | Human-in-the-loop approval for agent actions | `/dynamic/agent-approval` |
| Policies | Governance rules for agent behavior | `/dynamic/approval-policy` |

### ACP vs AuraBot

| Dimension | AuraBot (Copilot) | Agent Control Plane |
|-----------|-------------------|---------------------|
| Purpose | General-purpose, zero-config | Domain-specific, trained agents |
| Interaction | User-initiated chat, SSE streaming | System/schedule triggered, async |
| Tool calling | Light (max 5 rounds) | Full Plan-Execute-Adapt loop |
| Governance | None | Approval policies + cost limits |
| Context | Current page/model/record | Task description + semantic frame |
| Entry point | Cmd+J / Header icon | Mission Control / API / Scheduler |

## 6. AI Employees

AI Employees are pre-configured agents that handle specific business functions autonomously:

- **Customer Service Agent** -- Responds to support tickets using knowledge base
- **Data Entry Agent** -- Processes incoming documents and creates records
- **Report Agent** -- Generates periodic reports on schedule
- **Notification Agent** -- Monitors conditions and sends alerts

Configure AI Employees through the Agent Definition interface with appropriate tools, skills, and governance policies.

## Complete Example: Setting Up ChatBI for Sales Analysis

### Step 1: Configure LLM Provider

Navigate to `/aurabot/providers` and add your OpenAI or Anthropic API key.

### Step 2: Verify Models

Ensure your sales models are published:

```bash
aura dsl show sales_order
```

### Step 3: Test ChatBI

Navigate to `/chat-bi` and try these queries:

```
"How many sales orders by status?"
"Total revenue this quarter"
"Top 5 customers by order value"
"Monthly sales trend for 2026"
"Average order value by product category"
```

### Step 4: Build a Dashboard

Use the ChatBI results as inspiration to build a permanent dashboard:

1. Note the chart types and data fields that work best
2. Create NamedQueries for the most useful aggregations
3. Build a [Dashboard](dashboards.md) page with stat cards and charts

## Security

### Data Access Controls

- All AI queries respect the same tenant isolation and permission model as regular API calls
- ChatBI queries go through `TenantLineInterceptor` (automatic tenant_id filtering)
- AuraBot tool execution checks permissions before running commands
- Write operations always require explicit user confirmation

### API Key Management

- API keys are encrypted at rest in `ab_cloud_config` using `CloudConfigServiceImpl`
- Keys support Platform-level and Tenant-level configuration (tenant overrides platform)
- No API keys are exposed in frontend responses or logs

### Prompt Templates

Customize system prompts through **AuraBot Management > Prompt Templates** (`/aurabot/prompts`):

- Handlebars-style variable substitution (`{{modelName}}`, `{{fieldList}}`)
- Supports tenant-level overrides
- Real-time preview with sample data

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| "LLM provider not configured" | No API key in CloudConfig or application.yml | Add provider at `/aurabot/providers` |
| ChatBI returns no results | Model not published or no data | Run `aura query {model}` to verify data exists |
| ChatBI wrong model detected | Ambiguous keywords | Provide explicit `modelCode` in the query |
| AuraBot tool not found | Model has no commands or named queries | Publish the model and import the plugin |
| Confirm card not showing | SSE connection issue | Check browser console for WebSocket/SSE errors |
| Slow responses | LLM provider latency | Try a faster provider (Groq, DeepSeek) or smaller model |

## Next Steps

- [Dashboards](dashboards.md) -- Build permanent visualizations from ChatBI insights
- [Automation Rules](automation-rules.md) -- Combine AI insights with automated actions
- [BPM Workflows](bpm-workflows.md) -- Add human approval to AI-suggested actions
