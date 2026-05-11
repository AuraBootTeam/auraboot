# Command API Reference

The Command system is AuraBoot's unified data operation pipeline. Every create, update, delete, and state transition flows through a configurable 20+ stage pipeline. Commands are defined in DSL JSON and executed via the Command API.

## Execute a Command

```
POST /api/meta/commands/execute/{commandCode}
```

The `commandCode` uses the format `{namespace}:{action}` (e.g., `sc:create_showcase`, `sc:activate_showcase`).

**Permission:** `command.execute`

### Request Body

```json
{
  "payload": {
    "sc_name": "Test Showcase",
    "sc_code": "SC-001",
    "sc_quantity": 100
  },
  "clientRequestId": "unique-request-id-for-idempotency",
  "operationType": "CREATE",
  "targetRecordId": null,
  "expectedVersion": null
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `payload` | object | Yes | Key-value pairs of field data to pass to the command |
| `clientRequestId` | string | No | Idempotency key. If provided, duplicate requests within 24 hours return the original result instead of re-executing |
| `operationType` | string | No | Hint: `CREATE`, `UPDATE`, or `DELETE` |
| `targetRecordId` | string | No | Record ID for update/delete/state-transition commands |
| `expectedVersion` | integer | No | Optimistic locking: the expected row version. If the current version differs, the command is rejected |

### Response

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "success": true,
    "recordId": "01HXYZ...",
    "recordPid": "01HXYZ...",
    "data": {
      "id": 123,
      "pid": "01HXYZ...",
      "sc_name": "Test Showcase",
      "sc_code": "SC-001",
      "sc_status": "draft"
    },
    "message": null
  }
}
```

---

## Command Types

### Create

```bash
curl -s -X POST http://localhost:6443/api/meta/commands/execute/sc:create_showcase \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "payload": {
      "sc_name": "New Showcase",
      "sc_code": "SC-001",
      "sc_quantity": 100,
      "sc_category": "demo"
    }
  }'
```

### Update

```bash
curl -s -X POST http://localhost:6443/api/meta/commands/execute/sc:update_showcase \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "payload": {
      "sc_name": "Updated Name",
      "sc_quantity": 200
    },
    "targetRecordId": "01HXYZ..."
  }'
```

### Delete

```bash
curl -s -X POST http://localhost:6443/api/meta/commands/execute/sc:delete_showcase \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "targetRecordId": "01HXYZ..."
  }'
```

### State Transition

```bash
curl -s -X POST http://localhost:6443/api/meta/commands/execute/sc:activate_showcase \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "targetRecordId": "01HXYZ..."
  }'
```

State transitions are validated against the model's state machine definition. Invalid transitions return an error.

---

## Idempotency

When `clientRequestId` is provided, the command execution is idempotent within a 24-hour TTL window. The idempotency key is `{commandCode}:{clientRequestId}`.

```bash
curl -s -X POST http://localhost:6443/api/meta/commands/execute/sc:create_showcase \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "payload": {"sc_name": "Idempotent Create", "sc_code": "SC-099"},
    "clientRequestId": "req-2026-04-11-001"
  }'
```

Sending the same request again returns the original result without creating a duplicate record.

---

## The 20+ Stage Pipeline

Every command execution passes through these stages in order:

```
1. LOAD          - Load target record (for update/delete)
2. VALIDATE      - Schema validation (required fields, types, lengths)
3. PERMISSION    - RBAC permission check
4. STATE         - State machine validation (legal transitions)
5. LOCK          - Distributed lock acquisition
6. PRE_HANDLER   - Pre-execution hooks
7. FORMULA       - Formula field computation
8. FIELD_MAPPING - Field transformation and mapping
9. HANDLER       - Core business logic execution
10. POST_HANDLER - Post-execution hooks
11. EFFECT       - Side effects (update related records)
12. SIDE_EFFECT  - Additional side effects
13. NOTIFICATION - Send notifications
14. WEBHOOK      - Dispatch webhook events
15. AUDIT        - Write audit log
16. CACHE        - Cache invalidation
17. INDEX        - Search index update
18. SYNC         - Data sync (multi-instance)
19. CALLBACK     - Completion callbacks
20. COMPLETED    - Final stage marker
```

Each stage can be configured per command through DSL. Stages can be skipped, have custom handlers injected, or define conditional execution rules.

---

## Command Management API

These endpoints manage command definitions (admin/developer use).

### List Commands by Model

```bash
curl -s "http://localhost:6443/api/meta/commands?modelCode=sc_showcase" \
  -H "Authorization: Bearer <jwt>"
```

### Get Command by Code

```bash
curl -s http://localhost:6443/api/meta/commands/by-code/sc:create_showcase \
  -H "Authorization: Bearer <jwt>"
```

### Get Command by PID

```bash
curl -s http://localhost:6443/api/meta/commands/CMD-01HXYZ \
  -H "Authorization: Bearer <jwt>"
```

### Create Command Definition

```bash
curl -s -X POST http://localhost:6443/api/meta/commands \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "sc:custom_action",
    "modelCode": "sc_showcase",
    "commandType": "custom",
    "displayName": "Custom Action",
    "description": "A custom command"
  }'
```

### Update Command Definition

```bash
curl -s -X PUT http://localhost:6443/api/meta/commands/CMD-01HXYZ \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "displayName": "Updated Custom Action"
  }'
```

### Delete Command Definition

```bash
curl -s -X DELETE http://localhost:6443/api/meta/commands/CMD-01HXYZ \
  -H "Authorization: Bearer <jwt>"
```

### Publish Command

Publishing activates a command definition, making it available for execution:

```bash
curl -s -X POST http://localhost:6443/api/meta/commands/CMD-01HXYZ/publish \
  -H "Authorization: Bearer <jwt>"
```

---

## Binding Rules

Binding rules define conditions that must be met before a command can execute (e.g., field-level validation, cross-record checks).

### Add Binding Rule

```bash
curl -s -X POST http://localhost:6443/api/meta/commands/CMD-01HXYZ/binding-rules \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "ruleType": "validation",
    "expression": "payload.sc_quantity > 0",
    "errorMessage": "Quantity must be positive"
  }'
```

### List Binding Rules

```bash
curl -s http://localhost:6443/api/meta/commands/CMD-01HXYZ/binding-rules \
  -H "Authorization: Bearer <jwt>"
```

### Remove Binding Rule

```bash
curl -s -X DELETE http://localhost:6443/api/meta/commands/binding-rules/RULE-01HXYZ \
  -H "Authorization: Bearer <jwt>"
```

### Reorder Binding Rules

```bash
curl -s -X POST http://localhost:6443/api/meta/commands/CMD-01HXYZ/binding-rules/reorder \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '["RULE-01", "RULE-03", "RULE-02"]'
```

---

## Audit Logs

Command executions are logged for audit purposes.

### Query Audit Logs

```bash
curl -s "http://localhost:6443/api/meta/commands/audit-logs?commandCode=sc:create_showcase&success=true&pageNum=1&pageSize=20" \
  -H "Authorization: Bearer <jwt>"
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `commandCode` | string | Filter by command code |
| `success` | boolean | Filter by execution result |
| `startDate` | string | Start date filter (ISO 8601) |
| `endDate` | string | End date filter (ISO 8601) |
| `pageNum` | integer | Page number (default 1) |
| `pageSize` | integer | Page size (default 20) |

### Get Single Audit Log

```bash
curl -s http://localhost:6443/api/meta/commands/audit-logs/12345 \
  -H "Authorization: Bearer <jwt>"
```

---

## Error Handling

Command execution errors include context about which pipeline stage failed:

```json
{
  "code": "400",
  "message": "State transition not allowed: cannot transition from 'archived' to 'active'",
  "data": null
}
```

Common error scenarios:

| Scenario | Error |
|----------|-------|
| Missing required field | Validation error at VALIDATE stage |
| Insufficient permissions | 403 at PERMISSION stage |
| Invalid state transition | 400 at STATE stage |
| Optimistic lock conflict | 409 when `expectedVersion` mismatches |
| Duplicate `clientRequestId` | Returns original result (not an error) |
