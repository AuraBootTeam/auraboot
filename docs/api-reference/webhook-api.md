# Webhook API Reference

Webhooks allow external systems to receive real-time notifications when events occur in AuraBoot. When a record is created, updated, deleted, or transitions state, AuraBoot sends an HTTP POST to your configured endpoint.

## Webhook Management

All webhook management endpoints require the `sys.webhook.manage` permission.

### Create a Webhook

```
POST /api/webhooks
```

```bash
curl -s -X POST http://localhost:6443/api/webhooks \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Order Sync",
    "targetUrl": "https://example.com/webhooks/auraboot",
    "eventType": "record.created",
    "modelCode": "sc_showcase",
    "secret": "whsec_my_signing_secret",
    "filterExpression": "payload.sc_status == \"active\"",
    "maxRetries": 3,
    "timeoutMs": 10000,
    "enabled": true
  }'
```

**Request Fields:**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `name` | string | Yes | -- | Human-readable webhook name |
| `targetUrl` | string | Yes | -- | HTTPS endpoint to receive events |
| `eventType` | string | Yes | -- | Event type to subscribe to (see below) |
| `modelCode` | string | No | -- | Filter to a specific model (e.g., `sc_showcase`). Omit to receive events for all models |
| `secret` | string | No | -- | HMAC signing secret for payload verification |
| `filterExpression` | string | No | -- | Expression to filter which events trigger delivery |
| `headers` | string | No | -- | Custom HTTP headers (JSON string) |
| `maxRetries` | integer | No | `3` | Maximum retry attempts on failure |
| `timeoutMs` | integer | No | `10000` | HTTP request timeout in milliseconds |
| `enabled` | boolean | No | `true` | Whether the webhook is active |

### List Webhooks

```bash
# List all webhooks
curl -s http://localhost:6443/api/webhooks \
  -H "Authorization: Bearer <jwt>"

# Filter by event type
curl -s "http://localhost:6443/api/webhooks?eventType=record.created" \
  -H "Authorization: Bearer <jwt>"
```

### Get Webhook by PID

```bash
curl -s http://localhost:6443/api/webhooks/WH-01HXYZ \
  -H "Authorization: Bearer <jwt>"
```

### Update Webhook

```bash
curl -s -X PUT http://localhost:6443/api/webhooks/WH-01HXYZ \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Updated Order Sync",
    "targetUrl": "https://example.com/webhooks/v2",
    "eventType": "record.created",
    "maxRetries": 5
  }'
```

### Delete Webhook

```bash
curl -s -X DELETE http://localhost:6443/api/webhooks/WH-01HXYZ \
  -H "Authorization: Bearer <jwt>"
```

### Enable / Disable

```bash
# Enable
curl -s -X PUT http://localhost:6443/api/webhooks/WH-01HXYZ/enable \
  -H "Authorization: Bearer <jwt>"

# Disable
curl -s -X PUT http://localhost:6443/api/webhooks/WH-01HXYZ/disable \
  -H "Authorization: Bearer <jwt>"
```

---

## Event Types

| Event Type | Trigger |
|------------|---------|
| `record.created` | A new record is created in any (or the specified) model |
| `record.updated` | An existing record is updated |
| `record.deleted` | A record is deleted |
| `record.state_changed` | A record transitions state (via command state machine) |
| `command.executed` | A DSL command completes execution |
| `command.failed` | A DSL command fails during pipeline execution |
| `workflow.task_created` | A BPM human task is created |
| `workflow.task_completed` | A BPM human task is completed |
| `workflow.process_completed` | A BPM process instance completes |
| `plugin.imported` | A plugin is imported/updated |

You can use `*` as a wildcard to receive all events (not recommended for production).

---

## Payload Format

When an event fires, AuraBoot sends an HTTP POST to your `targetUrl`:

```http
POST /webhooks/auraboot HTTP/1.1
Host: example.com
Content-Type: application/json
X-AuraBoot-Event: record.created
X-AuraBoot-Signature: sha256=abc123...
X-AuraBoot-Delivery-Id: DEL-01HXYZ
X-AuraBoot-Timestamp: 1712841600
```

**Body:**

```json
{
  "event": "record.created",
  "timestamp": "2026-04-11T12:00:00Z",
  "deliveryId": "DEL-01HXYZ",
  "tenantId": 1,
  "modelCode": "sc_showcase",
  "data": {
    "recordId": "01HXYZ...",
    "recordPid": "01HXYZ...",
    "fields": {
      "sc_name": "New Showcase",
      "sc_code": "SC-001",
      "sc_status": "draft"
    },
    "changedFields": ["sc_name", "sc_code", "sc_status"],
    "previousState": null,
    "currentState": "draft",
    "triggeredBy": {
      "userId": 1,
      "userPid": "USR-01HXYZ"
    }
  }
}
```

For `record.updated` events, the payload includes both `previousValues` and `currentValues` for each changed field.

---

## HMAC Signature Verification

When a `secret` is configured, AuraBoot signs the payload using HMAC-SHA256. The signature is sent in the `X-AuraBoot-Signature` header.

### Verification Steps

1. Read the raw request body as a byte array (do not parse JSON first)
2. Compute HMAC-SHA256 of the body using your webhook secret
3. Compare with the value after `sha256=` in the `X-AuraBoot-Signature` header

### Example (Node.js)

```javascript
const crypto = require('crypto');

function verifyWebhook(body, signature, secret) {
  const expected = 'sha256=' + crypto
    .createHmac('sha256', secret)
    .update(body, 'utf8')
    .digest('hex');
  return crypto.timingSafeEqual(
    Buffer.from(signature),
    Buffer.from(expected)
  );
}
```

### Example (Python)

```python
import hmac
import hashlib

def verify_webhook(body: bytes, signature: str, secret: str) -> bool:
    expected = 'sha256=' + hmac.new(
        secret.encode(), body, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(signature, expected)
```

### Example (Java)

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;

public boolean verifyWebhook(byte[] body, String signature, String secret) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
    String expected = "sha256=" + bytesToHex(mac.doFinal(body));
    return MessageDigest.isEqual(signature.getBytes(), expected.getBytes());
}
```

---

## Retry Policy

When a webhook delivery fails (non-2xx response or timeout), AuraBoot retries with exponential backoff:

| Attempt | Delay |
|---------|-------|
| 1st retry | ~1 minute |
| 2nd retry | ~5 minutes |
| 3rd retry | ~30 minutes |

After `maxRetries` failures, the delivery is marked as failed and recorded in the delivery log. No further retries are attempted.

Your endpoint should return a `2xx` status code to acknowledge successful receipt. Any other status code triggers a retry.

---

## Delivery Logs

View the delivery history for a webhook:

```bash
curl -s "http://localhost:6443/api/webhooks/WH-01HXYZ/deliveries?limit=50" \
  -H "Authorization: Bearer <jwt>"
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `limit` | `50` | Number of delivery logs to return (max 200) |

**Response:**

```json
{
  "code": "0",
  "data": [
    {
      "id": 1,
      "subscriptionPid": "WH-01HXYZ",
      "eventType": "record.created",
      "httpStatus": 200,
      "responseBody": "{\"ok\":true}",
      "deliveryTimeMs": 145,
      "attempt": 1,
      "success": true,
      "createdAt": "2026-04-11T12:00:01Z"
    }
  ]
}
```

---

## Testing Webhooks

Send a test payload to verify your endpoint is reachable:

```bash
curl -s -X POST http://localhost:6443/api/webhooks/WH-01HXYZ/test \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "event": "test",
    "message": "This is a test delivery"
  }'
```

The test payload is sent to the webhook's `targetUrl` with the same headers and signature as a real delivery.

---

## Best Practices

1. **Always verify signatures** in production to prevent spoofed requests
2. **Respond quickly** (under 5 seconds). If processing takes longer, acknowledge with `200` and process asynchronously
3. **Handle duplicates** -- network retries can result in the same event being delivered twice. Use `deliveryId` for deduplication
4. **Use `modelCode` filters** to reduce noise. Subscribe to specific models instead of receiving all events
5. **Monitor delivery logs** to catch endpoint failures early
6. **Use HTTPS** for your target URL in production
