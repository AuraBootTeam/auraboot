# Event-Driven Automation Rules

Automate repetitive business logic with AuraBoot's automation engine. Define triggers, conditions, and actions to react to data changes, scheduled events, and external webhooks -- without writing code.

## Goal

By the end of this guide you will be able to create an automation rule that triggers when an opportunity status changes to "Won", automatically creates a project record, and sends a notification to the team.

## Prerequisites

- AuraBoot running locally
- At least one published model with data
- Admin account access

## How Automation Works

Every automation rule follows the **Trigger -> Condition -> Action** pattern:

```
+-------------------+     +--------------------+     +-------------------+
|     Trigger       | --> |    Condition        | --> |     Actions       |
|                   |     |    (optional)       |     |                   |
| Record created    |     | SpEL expression     |     | Update record     |
| Record updated    |     | #record.amount>1000 |     | Create record     |
| Field changed     |     |                    |     | Send notification |
| Status changed    |     |                    |     | Call API          |
| Scheduled (cron)  |     |                    |     | Execute command   |
| Webhook received  |     |                    |     | Send webhook      |
+-------------------+     +--------------------+     +-------------------+
```

Automations execute **asynchronously** after the triggering operation completes. They are independent of the Command system and BPM workflows.

### When to Use What

| System | Best For | Trigger |
|--------|----------|---------|
| **Automation** | Lightweight reactions to data events | Async, after data change |
| **Binding Rules** | Field validation and auto-fill within a command | Sync, during command execution |
| **BPM Workflows** | Multi-step human approval processes | Process start / task completion |

## Trigger Types

### Record Created

Fires when a new record is created in the specified model.

```json
{
  "triggerType": "ON_RECORD_CREATE",
  "triggerConfig": {
    "modelCode": "crm_opportunity"
  },
  "triggerCondition": "#record.crm_opp_amount > 10000"
}
```

### Record Updated

Fires when any field on a record is updated. Optionally watch specific fields.

```json
{
  "triggerType": "ON_RECORD_UPDATE",
  "triggerConfig": {
    "modelCode": "crm_opportunity",
    "watchFields": ["crm_opp_status", "crm_opp_priority"]
  }
}
```

### Field Changed

Fires when a specific field changes from one value to another.

```json
{
  "triggerType": "ON_FIELD_CHANGE",
  "triggerConfig": {
    "modelCode": "crm_opportunity",
    "fieldCode": "crm_opp_status",
    "from": "negotiation",
    "to": "won"
  }
}
```

### Status Changed

Fires on state machine transitions.

```json
{
  "triggerType": "ON_STATE_CHANGE",
  "triggerConfig": {
    "modelCode": "crm_opportunity",
    "from": "negotiation",
    "to": "won"
  }
}
```

### Scheduled (Cron)

Fires on a cron schedule. Checked every 60 seconds by the `AutomationScheduler`.

```json
{
  "triggerType": "SCHEDULED",
  "triggerConfig": {
    "cron": "0 9 * * MON-FRI",
    "timezone": "Asia/Shanghai"
  }
}
```

### Webhook

Fires when an external system sends an HTTP POST to the automation webhook endpoint.

```json
{
  "triggerType": "WEBHOOK",
  "triggerConfig": {
    "secret": "my_webhook_secret",
    "validationMode": "SIGNATURE"
  }
}
```

The endpoint is: `POST /api/automations/webhooks/{automationPid}`

Validation modes:
- `SIGNATURE` -- HMAC-SHA256 signature in `X-Webhook-Signature` header
- `TOKEN` -- Pre-shared token in `X-Webhook-Token` header

### BPM Event

Fires when a BPM process event occurs (e.g., task completed, process finished).

```json
{
  "triggerType": "ON_BPM_EVENT",
  "triggerConfig": {
    "eventType": "TASK_COMPLETED",
    "processKey": "purchase_approval"
  }
}
```

### Inactivity

Fires when a record has not been updated within a specified time window.

```json
{
  "triggerType": "ON_INACTIVITY",
  "triggerConfig": {
    "modelCode": "crm_lead",
    "inactivityDays": 7,
    "checkField": "updated_at"
  }
}
```

## Condition Expressions (SpEL)

Conditions use Spring Expression Language (SpEL). The `#record` variable refers to the triggering record.

| Expression | Meaning |
|-----------|---------|
| `#record.amount > 10000` | Amount exceeds 10,000 |
| `#record.status == 'won'` | Status is "won" |
| `#record.priority == 'high' && #record.amount > 5000` | High priority AND large amount |
| `#record.assignee != null` | Assignee is set |
| `#record.tags.contains('vip')` | Tags include "vip" |

If no condition is specified, the automation fires on every trigger match.

## Action Types

### Update Record

Update fields on the triggering record or a related record.

```json
{
  "type": "UPDATE_RECORD",
  "sequence": 1,
  "label": "Mark as high priority",
  "config": {
    "modelCode": "crm_opportunity",
    "recordId": "${record.pid}",
    "fields": {
      "crm_opp_priority": "high",
      "crm_opp_follow_up_date": "${now()}"
    }
  }
}
```

### Create Record

Create a new record in any model.

```json
{
  "type": "CREATE_RECORD",
  "sequence": 1,
  "label": "Create follow-up task",
  "config": {
    "modelCode": "pm_task",
    "fields": {
      "pm_task_title": "Follow up: ${record.crm_opp_name}",
      "pm_task_assignee": "${record.crm_opp_owner}",
      "pm_task_due_date": "${now()}"
    }
  }
}
```

### Execute Command

Execute a DSL Command against the current or another record.

```json
{
  "type": "EXECUTE_COMMAND",
  "sequence": 2,
  "label": "Activate the project",
  "config": {
    "commandCode": "pm:activate_project",
    "targetRecordId": "${actions[0].result.pid}"
  }
}
```

### Send Notification

Send an in-app notification to specific users.

```json
{
  "type": "SEND_NOTIFICATION",
  "sequence": 3,
  "label": "Notify the team",
  "config": {
    "type": "IN_APP",
    "title": "Deal Won: ${record.crm_opp_name}",
    "content": "Opportunity worth $${record.crm_opp_amount} has been closed as Won.",
    "recipients": ["${record.crm_opp_owner}", "sales_manager_001"]
  }
}
```

### Call External API

Make an HTTP request to an external service.

```json
{
  "type": "CALL_API",
  "sequence": 4,
  "label": "Notify CRM webhook",
  "config": {
    "url": "https://api.example.com/hooks/deal-won",
    "method": "POST",
    "headers": {
      "X-API-Key": "secret_key",
      "Content-Type": "application/json"
    },
    "body": {
      "event": "deal_won",
      "opportunityId": "${record.pid}",
      "amount": "${record.crm_opp_amount}"
    },
    "timeoutSeconds": 30
  }
}
```

### Send Webhook

Dispatch a webhook event through the platform's webhook system.

```json
{
  "type": "SEND_WEBHOOK",
  "sequence": 5,
  "label": "Dispatch webhook event",
  "config": {
    "eventType": "opportunity.won",
    "payload": {
      "id": "${record.pid}",
      "name": "${record.crm_opp_name}"
    }
  }
}
```

## Control Nodes

### Condition Branch

Add conditional logic within the action sequence:

```json
{
  "type": "CONDITION",
  "sequence": 2,
  "config": {
    "expression": "#record.crm_opp_amount > 50000"
  }
}
```

### Delay

Pause execution for a specified duration (max 5 minutes):

```json
{
  "type": "DELAY",
  "sequence": 3,
  "config": {
    "seconds": 60
  }
}
```

### Loop

Repeat actions (max 100 iterations):

```json
{
  "type": "LOOP",
  "sequence": 4,
  "config": {
    "iterationsKey": "loopCount",
    "maxIterations": 10
  }
}
```

## Step-by-Step: Create via UI (Automation Designer)

### 1. Open the Automation Editor

Navigate to **Settings > Automations** in the sidebar.

Click **Create Automation**.

### 2. Name and Select Model

- **Name**: "Won Opportunity -> Create Project + Notify"
- **Model**: Select `crm_opportunity`

### 3. Configure Trigger

Select trigger type: **On State Change**

Configure:
- From: `negotiation`
- To: `won`

### 4. Add Condition (Optional)

Add SpEL condition: `#record.crm_opp_amount > 5000`

### 5. Add Actions

The Automation Designer uses a **React Flow canvas** where you visually connect action nodes:

1. Add a **Create Record** action (creates a project)
2. Add a **Send Notification** action (notifies the team)
3. Connect them in sequence

### 6. Enable and Save

Toggle the automation to **Enabled** and click **Save**.

## Complete Example: DSL Configuration

Here is the complete automation rule as stored in the database:

```json
{
  "name": "Won Opportunity - Create Project and Notify",
  "description": "When an opportunity is won, create a project and notify the sales team",
  "modelCode": "crm_opportunity",
  "triggerType": "ON_STATE_CHANGE",
  "triggerConfig": {
    "modelCode": "crm_opportunity",
    "from": "negotiation",
    "to": "won"
  },
  "triggerCondition": "#record.crm_opp_amount > 5000",
  "actions": [
    {
      "type": "CREATE_RECORD",
      "sequence": 1,
      "label": "Create project from opportunity",
      "config": {
        "modelCode": "pm_project",
        "fields": {
          "pm_project_name": "Project: ${record.crm_opp_name}",
          "pm_project_code": "PRJ-${record.crm_opp_code}",
          "pm_project_budget": "${record.crm_opp_amount}",
          "pm_project_owner": "${record.crm_opp_owner}",
          "pm_project_status": "active"
        }
      }
    },
    {
      "type": "SEND_NOTIFICATION",
      "sequence": 2,
      "label": "Notify sales team",
      "config": {
        "type": "IN_APP",
        "title": "Deal Won: ${record.crm_opp_name}",
        "content": "A $${record.crm_opp_amount} deal has been closed. A project has been automatically created.",
        "recipients": ["${record.crm_opp_owner}"]
      }
    },
    {
      "type": "CALL_API",
      "sequence": 3,
      "label": "Sync to external CRM",
      "config": {
        "url": "https://hooks.example.com/deal-won",
        "method": "POST",
        "body": {
          "dealId": "${record.pid}",
          "dealName": "${record.crm_opp_name}",
          "amount": "${record.crm_opp_amount}"
        },
        "timeoutSeconds": 15
      }
    }
  ],
  "enabled": true
}
```

## Execution Logging

Every automation execution is logged in `ab_automation_log`:

```json
{
  "automationId": "01ABC...",
  "triggerType": "ON_STATE_CHANGE",
  "triggerRecordId": "01XYZ...",
  "status": "success",
  "actionResults": [
    {
      "sequence": 1,
      "actionType": "CREATE_RECORD",
      "status": "success",
      "result": { "pid": "01NEW..." },
      "durationMs": 45
    },
    {
      "sequence": 2,
      "actionType": "SEND_NOTIFICATION",
      "status": "success",
      "durationMs": 12
    }
  ]
}
```

View logs in the Automation management page or query:

```bash
aura query ab_automation_log --filter "automation_id=01ABC..."
```

## Debugging Automations

The automation system includes a visual debugger:

1. Open an automation in the designer
2. Click **Debug** mode
3. Set **breakpoints** on specific action nodes
4. Provide a **test record** or simulated trigger payload
5. **Step through** actions one by one
6. Inspect **variables** and **action results** at each step

Debug sessions are stored in `ab_automation_debug_session` and support:
- Pause/resume execution
- Variable inspection
- Breakpoint management

## Dual-Channel Trigger Architecture

Automations trigger through two independent channels:

**Channel 1: DynamicDataService** (REST API `POST /api/dynamic/`)
```
DynamicDataService.create() / update()
  -> Write to DB
  -> AutomationTriggerService.onRecordCreate/onRecordUpdate (@Async)
```

**Channel 2: CommandCompletedEvent** (Command execution)
```
CommandExecutorImpl.execute()
  -> CommandFieldMapExecutor (writes directly to DB)
  -> DomainEventPublisher.publishCommandCompleted()
  -> CommandEventBridge -> AutomationTriggerService (@Async)
```

Both channels converge on `AutomationTriggerService`, ensuring automations fire regardless of how data is created or modified.

## Best Practices

**Avoid infinite loops:**
- Do not create an "ON_RECORD_UPDATE" trigger that updates the same model without a condition guard
- Use `triggerCondition` to ensure the trigger only fires on meaningful changes
- Example: `#record.status == 'won' && #oldRecord.status != 'won'`

**Performance:**
- Keep action lists short (under 10 actions per rule)
- Use `DELAY` sparingly (max 5 minutes)
- External API calls should have reasonable timeouts (15-30 seconds)
- Scheduled automations should not run more frequently than every minute

**Error handling:**
- Failed actions are logged with error messages in `ab_automation_log`
- Subsequent actions in the sequence continue unless the failure is critical
- Monitor automation logs regularly for recurring failures

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| Automation never fires | Trigger config mismatch | Verify `modelCode` and trigger type match the data operation |
| Fires but actions fail | SpEL expression error | Check variable names (`#record.fieldCode` not `#record.field_code`) |
| Webhook not triggering | Signature validation failed | Verify HMAC secret matches; check `X-Webhook-Signature` header |
| Scheduled rule not running | Invalid cron expression | Test cron expression; ensure timezone is correct |
| Infinite loop detected | Self-referential update | Add condition guard to prevent re-triggering |
| Action references wrong record | Wrong `${record.xxx}` path | Use `${actions[N].result.pid}` to reference outputs from previous actions |

## Next Steps

- [BPM Workflows](bpm-workflows.md) -- For complex multi-step approval processes
- [Page Designer](page-designer.md) -- Build pages that display automation-created records
- [AI Copilot](ai-copilot.md) -- Use AuraBot to query automation execution logs
