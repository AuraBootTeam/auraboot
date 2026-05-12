# Notifications

Configure email, in-app, and webhook notifications triggered by business events, automations, and workflow tasks.

## Goal

By the end of this guide you will be able to:

- Configure notification channels (email, in-app, webhook)
- Create notification templates with variable substitution
- Set up webhook subscriptions with HMAC signing and retry
- Define notification triggers from commands, automations, and BPM tasks
- Configure user notification preferences

## Prerequisites

- AuraBoot instance running
- Admin access for notification configuration
- SMTP server credentials (for email channel)
- A target URL (for webhook channel)

---

## 1. Architecture Overview

AuraBoot's notification system is event-driven:

```
Command/BPM execution
  -> AuraEventBus (publishes event after transaction commit)
    -> NotificationRouter
      -> Template matching
      -> Recipient resolution
      -> User preference filtering
      -> Channel dispatch (in-app, email, webhook, WeChat, DingTalk, Slack)
```

Key design principles:

- **At-least-once delivery** via the Outbox Pattern
- **5-minute digest window** to batch similar notifications and prevent spam
- **Opt-out user preferences** per channel per notification category
- **Multi-tenant isolation** across the entire notification pipeline

---

## 2. Notification Channels

### In-App Notifications

In-app notifications appear in the bell icon notification center in the top navigation bar.

**Features:**
- Real-time delivery via Server-Sent Events (SSE)
- Read/unread status tracking
- Notification center with history
- Badge count on the bell icon
- Click-through to the related record

**No configuration required** -- in-app notifications work out of the box.

### Email Notifications

Email notifications use SMTP to send formatted messages.

**Configuration** (in `application.yml` or environment variables):

```yaml
spring:
  mail:
    host: smtp.example.com
    port: 587
    username: notifications@example.com
    password: ${SMTP_PASSWORD}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
```

| Environment Variable | Description | Example |
|---------------------|-------------|---------|
| `SPRING_MAIL_HOST` | SMTP server hostname | `smtp.gmail.com` |
| `SPRING_MAIL_PORT` | SMTP port | `587` |
| `SPRING_MAIL_USERNAME` | SMTP username | `noreply@example.com` |
| `SPRING_MAIL_PASSWORD` | SMTP password | (secret) |

### Webhook Notifications

Webhooks send HTTP POST requests to external URLs when events occur.

**Features:**
- HMAC-SHA256 request signing
- Exponential backoff retry on failure
- SpEL filter expressions (only matching events trigger delivery)
- Per-subscription configuration
- Complete audit trail of delivery attempts

---

## 3. Notification Templates

Templates define the content and recipients for each notification type.

### Template structure

```json
{
  "templateCode": "task_assigned",
  "eventType": "command:completed",
  "modelCode": "pm_task",
  "commandCode": "assign_task",
  "channels": ["in_app", "email"],

  "subject": "Task assigned: ${recordTitle}",
  "body": "Hi ${recipientName},\n\n${actorName} assigned you the task \"${recordTitle}\".\n\nDue date: ${pm_task_due_date}",
  "htmlBody": "<p>Hi ${recipientName},</p><p>${actorName} assigned you the task <strong>${recordTitle}</strong>.</p>",

  "recipientType": "field_value",
  "recipientField": "pm_task_assignee_user_id",

  "excludeActor": true,
  "priority": "P1"
}
```

### Available template variables

| Variable | Description |
|----------|-------------|
| `${actorName}` | Name of the user who triggered the event |
| `${actorEmail}` | Email of the triggering user |
| `${recipientName}` | Name of the notification recipient |
| `${recordTitle}` | Display title of the related record |
| `${recordPid}` | PID of the related record |
| `${modelCode}` | Model code |
| `${tenantName}` | Current tenant name |
| `${commandCode}` | The command that was executed |
| `${fieldCode}` | Any field value from the record (use the field code) |

### Recipient resolution types

| Type | Description | Example |
|------|-------------|---------|
| `field_value` | User ID from a record field | `pm_task_assignee_user_id` |
| `role_on_record` | Users with a specific role | `current_approver` |
| `role` | All users with a system role | `TENANT_ADMIN` |
| `fixed` | Hardcoded user IDs or emails | `["admin@auraboot.com"]` |

---

## 4. Email Templates

### Plain text email

```json
{
  "templateCode": "invoice_overdue",
  "channels": ["email"],
  "subject": "Invoice ${inv_invoice_number} is overdue",
  "body": "Dear ${recipientName},\n\nInvoice ${inv_invoice_number} for ${inv_invoice_amount} was due on ${inv_invoice_due_date} and has not been paid.\n\nPlease process the payment at your earliest convenience.\n\nRegards,\n${tenantName} Finance Team"
}
```

### HTML email

```json
{
  "templateCode": "order_confirmed",
  "channels": ["email"],
  "subject": "Order ${so_order_number} confirmed",
  "htmlBody": "<div style='font-family: sans-serif;'><h2>Order Confirmed</h2><p>Hi ${recipientName},</p><p>Your order <strong>${so_order_number}</strong> has been confirmed.</p><table style='border-collapse: collapse; width: 100%;'><tr style='background: #f5f5f5;'><td style='padding: 8px; border: 1px solid #ddd;'>Order Total</td><td style='padding: 8px; border: 1px solid #ddd;'>${so_order_total}</td></tr></table><p>Thank you for your business!</p></div>"
}
```

---

## 5. Webhook Configuration

### Step 1: Create a webhook subscription

**Via API:**

```bash
POST /api/webhooks
Content-Type: application/json
Authorization: Bearer <token>

{
  "name": "Order Events to ERP",
  "targetUrl": "https://erp.example.com/webhooks/auraboot",
  "eventType": "command:completed",
  "modelCode": "so_order",
  "secret": "your-webhook-secret-key",
  "enabled": true,
  "maxRetries": 5,
  "filterExpression": "#payload['commandCode'] == 'confirm_order'"
}
```

### Step 2: Verify webhook delivery

Each delivery sends an HTTP POST with:

**Headers:**

| Header | Description |
|--------|-------------|
| `Content-Type` | `application/json` |
| `X-AuraBoot-Event` | Event type (e.g., `command:completed`) |
| `X-AuraBoot-Signature` | HMAC-SHA256 signature of the body |
| `X-AuraBoot-Delivery-Id` | Unique delivery ID |
| `X-AuraBoot-Timestamp` | Unix timestamp |

**Payload:**

```json
{
  "eventId": "01HXYZ123",
  "eventType": "command:completed",
  "modelCode": "so_order",
  "recordId": "01HXYZ456",
  "occurredAt": "2026-04-11T10:30:00Z",
  "tenantId": 2,
  "payload": {
    "commandCode": "confirm_order",
    "operationType": "UPDATE",
    "data": {
      "so_order_number": "SO-202604-0001",
      "so_order_status": "confirmed",
      "so_order_total": 12500.00
    }
  }
}
```

### Step 3: Verify the signature

On your receiving server, verify the HMAC-SHA256 signature:

```python
import hmac
import hashlib

def verify_signature(payload_bytes, secret, received_signature):
    expected = hmac.new(
        secret.encode('utf-8'),
        payload_bytes,
        hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(f"sha256={expected}", received_signature)
```

### Retry policy

Failed deliveries (non-2xx response or timeout) are retried with exponential backoff:

| Attempt | Delay |
|---------|-------|
| 1st retry | 30 seconds |
| 2nd retry | 2 minutes |
| 3rd retry | 8 minutes |
| 4th retry | 32 minutes |
| 5th retry | ~2 hours |

After max retries, the subscription is **not** automatically disabled. Failed deliveries are logged in `ab_webhook_delivery_log`.

### Testing a webhook

```bash
POST /api/webhooks/{subscriptionId}/test
Authorization: Bearer <token>
```

This sends a test event to the configured URL with sample data.

---

## 6. Notification Triggers

### From Command execution

Every command completion publishes a `CommandCompletedEvent`. Configure a notification template matching the model and command:

```json
{
  "eventType": "command:completed",
  "modelCode": "pm_task",
  "commandCode": "assign_task",
  "channels": ["in_app", "email"]
}
```

### From Automation rules

Use the **Send Notification** action in automation rules:

```json
{
  "trigger": {
    "eventType": "command:completed",
    "modelCode": "crm_opportunity",
    "commandCode": "update_opportunity"
  },
  "condition": "#payload['crm_opp_amount'] > 100000",
  "actions": [
    {
      "type": "send_notification",
      "channels": ["in_app", "email"],
      "recipientType": "role",
      "recipientRole": "sales_manager",
      "subject": "High-value deal updated: ${recordTitle}",
      "body": "Deal ${crm_opp_name} updated. New amount: ${crm_opp_amount}"
    }
  ]
}
```

### From BPM workflow tasks

BPM events automatically generate notifications:

| BPM Event | Notification |
|-----------|-------------|
| Task assigned | In-app + email to assignee |
| Process approved | In-app to submitter |
| Process rejected | In-app + email to submitter |
| Process completed | In-app to all participants |

These are built-in and require no additional configuration.

---

## 7. User Notification Preferences

Users can control which notifications they receive per channel.

### Preference model

AuraBoot uses an **opt-out** model: all notifications are enabled by default. Users can selectively disable channels for specific notification categories.

### Accessing preferences

Users manage preferences from **Settings > Notifications** in the web UI.

| Setting | Options | Default |
|---------|---------|---------|
| In-app notifications | On / Off per category | On |
| Email notifications | On / Off per category | On |
| Digest mode | Immediate / Daily digest | Immediate |

### Digest mode

When digest mode is enabled, notifications within a 5-minute window are batched into a single message:

```
You have 3 new notifications:
  - Task "Fix login bug" assigned to you
  - Task "Update API docs" assigned to you
  - Task "Review PR #42" assigned to you
```

---

## 8. Complete Example: Task Assignment Notification

### Goal

When a task is assigned, send both an in-app notification and an email to the assignee.

### Step 1: Create the notification template

In your plugin's `notification-templates.json`:

```json
[
  {
    "templateCode": "pm_task_assigned",
    "eventType": "command:completed",
    "modelCode": "pm_task",
    "commandCode": "assign_task",
    "channels": ["in_app", "email"],
    "subject": "Task assigned: ${pm_task_title}",
    "body": "Hi ${recipientName},\n\n${actorName} assigned you the task \"${pm_task_title}\" in project ${pm_task_project_id_display}.\n\nDue: ${pm_task_due_date}\nPriority: ${pm_task_priority}\n\nView task: ${recordUrl}",
    "htmlBody": "<p>Hi ${recipientName},</p><p><strong>${actorName}</strong> assigned you the task <strong>${pm_task_title}</strong>.</p><ul><li>Project: ${pm_task_project_id_display}</li><li>Due: ${pm_task_due_date}</li><li>Priority: ${pm_task_priority}</li></ul>",
    "recipientType": "field_value",
    "recipientField": "pm_task_assignee_user_id",
    "excludeActor": true,
    "priority": "P1"
  }
]
```

### Step 2: Configure SMTP (if not already done)

Set environment variables:

```bash
export SPRING_MAIL_HOST=smtp.example.com
export SPRING_MAIL_PORT=587
export SPRING_MAIL_USERNAME=noreply@example.com
export SPRING_MAIL_PASSWORD=your-password
```

### Step 3: Test

1. Assign a task to another user
2. The assignee sees a notification badge on the bell icon
3. The assignee receives an email with the task details

### Step 4: Add a webhook for external integration

```bash
POST /api/webhooks
{
  "name": "Task assignments to Slack",
  "targetUrl": "https://hooks.slack.com/services/T00/B00/xxx",
  "eventType": "command:completed",
  "modelCode": "pm_task",
  "filterExpression": "#payload['commandCode'] == 'assign_task'",
  "enabled": true
}
```

---

## 9. Best Practices

### Avoiding notification spam

- **Use `excludeActor: true`** so users don't get notified about their own actions
- **Enable digest mode** for high-frequency events (e.g., bulk imports)
- **Use SpEL filters** on webhooks to limit which events trigger delivery
- **Set appropriate priority levels** -- P0 for urgent, P3 for informational

### Notification design

| Do | Don't |
|----|-------|
| Include the record name in the subject | Use generic subjects like "Update" |
| Provide a direct link to the record | Require the user to search for it |
| Keep the body concise (2-3 sentences) | Include the entire record data |
| Mention who performed the action | Send anonymous notifications |

### Webhook reliability

- Always verify the HMAC signature on the receiving end
- Return 2xx quickly; process the payload asynchronously
- Implement idempotency using `X-AuraBoot-Delivery-Id`
- Monitor the webhook delivery log for failures

---

## Troubleshooting

| Symptom | Cause | Solution |
|---------|-------|----------|
| No in-app notifications | SSE connection not established | Check browser console for SSE errors |
| Email not received | SMTP not configured | Verify `spring.mail.*` properties |
| Email in spam folder | Missing SPF/DKIM records | Configure DNS records for your sending domain |
| Webhook not triggered | Subscription disabled or filter mismatch | Check subscription status and `filterExpression` |
| Webhook delivery fails | Target URL unreachable | Check `ab_webhook_delivery_log` for error details |
| Duplicate notifications | Digest not enabled for high-frequency events | Enable digest mode or increase the aggregation window |
| `excludeActor` not working | Event missing actor context | Ensure the command publishes actor info in the event |

---

## Next Steps

- [Multi-Tenancy](multi-tenancy.md) -- notification isolation across tenants
- [CLI Reference](cli-reference.md) -- query webhook delivery logs
