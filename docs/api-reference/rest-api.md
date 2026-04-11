# REST API Reference

AuraBoot exposes a RESTful API served by Spring Boot on port **6443** (default). All endpoints return a unified response envelope and require JWT authentication unless noted otherwise.

## Base URL

```
http://localhost:6443
```

Production deployments typically front this with a reverse proxy (nginx, Cloudflare) on port 443.

## Authentication

### Login

Obtain a JWT token by posting credentials to the login endpoint.

```bash
curl -s -X POST http://localhost:6443/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@example.com", "password": "ChangeMeOnFirstLogin!"}'
```

**Response:**

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "jwt": "eyJhbGciOi...",
    "userId": 1,
    "userPid": "01HXYZ...",
    "nickName": "Admin",
    "tenantId": 1,
    "role": "admin"
  }
}
```

Store the `jwt` value and pass it as a Bearer token on subsequent requests:

```bash
-H "Authorization: Bearer <jwt>"
```

### Token Lifetime

| Setting | Default | Env Variable |
|---------|---------|-------------|
| Expiration | 86400 s (24 h) | `JWT_EXPIRATION` |
| Algorithm | HS256 | -- |
| Key Rotation | Supported via `JWT_PREVIOUS_SECRET` | See [Configuration](../deployment/configuration.md) |

### Current User

```bash
curl -s http://localhost:6443/api/auth/me \
  -H "Authorization: Bearer <jwt>"
```

Returns the authenticated user's profile, roles, and permissions.

### Register

```bash
curl -s -X POST http://localhost:6443/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "SecureP@ss1",
    "nickName": "New User"
  }'
```

### Password Reset

```bash
# Request reset email
curl -s -X POST http://localhost:6443/api/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com"}'

# Reset with token (from email link)
curl -s -X POST http://localhost:6443/api/auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{"token": "reset-token-from-email", "newPassword": "NewSecureP@ss1"}'
```

---

## Response Envelope

Every API response follows this structure:

```json
{
  "code": "0",
  "message": "success",
  "data": { ... }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `code` | string | `"0"` for success, error code otherwise |
| `message` | string | Human-readable message |
| `data` | object/array/null | Response payload |

### Error Codes

| Code | Meaning |
|------|---------|
| `0` | Success |
| `400` | Bad request / validation error |
| `401` | Unauthorized (missing or invalid JWT) |
| `403` | Forbidden (insufficient permissions) |
| `404` | Resource not found |
| `409` | Conflict (duplicate key, state violation) |
| `429` | Rate limited |
| `500` | Internal server error |

---

## Dynamic CRUD API

The Dynamic CRUD API provides unified data operations for all DSL-defined models. Endpoints use a `pageKey` path variable that maps to a model code.

### List Records

```
GET /api/dynamic/{pageKey}/list
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `pageNum` | integer | `1` | Page number (1-indexed) |
| `pageSize` | integer | `10` | Records per page (max 500) |
| `keyword` | string | -- | Full-text search keyword |
| `filters` | string (JSON) | -- | Filter conditions array |
| `sortField` | string | -- | Single sort field name |
| `sortOrder` | string | -- | `ASC` or `DESC` |
| `sortFields` | string | -- | Multi-sort: `field1:desc,field2:asc` (max 5 fields) |
| `queryCode` | string | -- | Named query code (overrides model table query) |
| `cursor` | long | -- | Keyset pagination cursor (overrides `pageNum`) |

**Example:**

```bash
curl -s "http://localhost:6443/api/dynamic/sc_showcase_list/list?pageNum=1&pageSize=20&keyword=test" \
  -H "Authorization: Bearer <jwt>"
```

**Response:**

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "records": [
      {
        "id": 1,
        "pid": "01HXYZ...",
        "sc_name": "Test Showcase",
        "sc_code": "SC-001",
        "sc_status": "active",
        "created_at": "2026-01-15T10:30:00Z"
      }
    ],
    "total": 42,
    "pageNum": 1,
    "pageSize": 20,
    "totalPages": 3,
    "nextCursor": 1234
  }
}
```

### Filter Syntax

Filters are passed as a JSON array in the `filters` query parameter:

```
filters=[{"fieldName":"status","operator":"EQ","value":"active"}]
```

URL-encoded:

```bash
curl -s "http://localhost:6443/api/dynamic/sc_showcase_list/list?filters=%5B%7B%22fieldName%22%3A%22sc_status%22%2C%22operator%22%3A%22EQ%22%2C%22value%22%3A%22active%22%7D%5D" \
  -H "Authorization: Bearer <jwt>"
```

**Filter Operators:**

| Operator | Description | Value Type | Example |
|----------|-------------|------------|---------|
| `EQ` | Equals | single | `{"fieldName":"status","operator":"EQ","value":"active"}` |
| `NE` (or `NEQ`) | Not equals | single | `{"fieldName":"status","operator":"NE","value":"draft"}` |
| `GT` | Greater than | single | `{"fieldName":"amount","operator":"GT","value":1000}` |
| `GE` (or `GTE`) | Greater than or equal | single | `{"fieldName":"amount","operator":"GE","value":1000}` |
| `LT` | Less than | single | `{"fieldName":"amount","operator":"LT","value":5000}` |
| `LE` (or `LTE`) | Less than or equal | single | `{"fieldName":"amount","operator":"LE","value":5000}` |
| `LIKE` | Contains (fuzzy match) | single | `{"fieldName":"name","operator":"LIKE","value":"test"}` |
| `NOT_LIKE` | Does not contain | single | `{"fieldName":"name","operator":"NOT_LIKE","value":"draft"}` |
| `IN` | In list | array (`values`) | `{"fieldName":"status","operator":"IN","values":["active","pending"]}` |
| `NOT_IN` | Not in list | array (`values`) | `{"fieldName":"status","operator":"NOT_IN","values":["archived"]}` |
| `IS_NULL` | Is null | none | `{"fieldName":"deleted_at","operator":"IS_NULL"}` |
| `IS_NOT_NULL` | Is not null | none | `{"fieldName":"assigned_to","operator":"IS_NOT_NULL"}` |
| `BETWEEN` | Between range | array (`values`) | `{"fieldName":"amount","operator":"BETWEEN","values":[100,500]}` |
| `NOT_BETWEEN` | Not between range | array (`values`) | `{"fieldName":"amount","operator":"NOT_BETWEEN","values":[100,500]}` |

**Compound Filters:**

```json
[
  {"fieldName": "status", "operator": "EQ", "value": "active"},
  {"fieldName": "amount", "operator": "GT", "value": 1000},
  {"fieldName": "category", "operator": "IN", "values": ["sales", "marketing"]}
]
```

Multiple filters are combined with `AND` by default. Use `logicalOperator` for `OR`:

```json
[
  {
    "logicalOperator": "OR",
    "subConditions": [
      {"fieldName": "status", "operator": "EQ", "value": "active"},
      {"fieldName": "status", "operator": "EQ", "value": "pending"}
    ]
  }
]
```

### Get Single Record

```
GET /api/dynamic/{pageKey}/{recordId}
```

```bash
curl -s http://localhost:6443/api/dynamic/sc_showcase/01HXYZ \
  -H "Authorization: Bearer <jwt>"
```

### Create Record

```
POST /api/dynamic/{pageKey}/create
```

```bash
curl -s -X POST http://localhost:6443/api/dynamic/sc_showcase/create \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "sc_name": "New Showcase",
    "sc_code": "SC-002",
    "sc_quantity": 100
  }'
```

### Update Record

```
PUT /api/dynamic/{pageKey}/{recordId}
```

```bash
curl -s -X PUT http://localhost:6443/api/dynamic/sc_showcase/01HXYZ \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "sc_name": "Updated Showcase",
    "sc_quantity": 200
  }'
```

### Delete Record

```
DELETE /api/dynamic/{pageKey}/{recordId}
```

```bash
curl -s -X DELETE http://localhost:6443/api/dynamic/sc_showcase/01HXYZ \
  -H "Authorization: Bearer <jwt>"
```

### Batch Operations

**Batch Create** (max 500 records):

```bash
curl -s -X POST http://localhost:6443/api/dynamic/sc_showcase/batch \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '[
    {"sc_name": "Item 1", "sc_code": "SC-003"},
    {"sc_name": "Item 2", "sc_code": "SC-004"}
  ]'
```

**Batch Update** (max 500 records):

```bash
curl -s -X PUT http://localhost:6443/api/dynamic/sc_showcase/batch \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '[
    {"id": 1, "sc_name": "Updated 1"},
    {"id": 2, "sc_name": "Updated 2"}
  ]'
```

**Batch Delete** (max 500 records):

```bash
curl -s -X DELETE http://localhost:6443/api/dynamic/sc_showcase/batch \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '["01HXYZ1", "01HXYZ2", "01HXYZ3"]'
```

### Validate Data

```bash
curl -s -X POST http://localhost:6443/api/dynamic/sc_showcase/validate \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{"sc_name": "", "sc_code": "SC-005"}'
```

**Response:**

```json
{
  "code": "0",
  "data": {
    "valid": false,
    "errors": [
      {"field": "sc_name", "message": "sc_name is required"}
    ]
  }
}
```

### Get Field Options

```bash
curl -s "http://localhost:6443/api/dynamic/sc_showcase/field-options/sc_category?keyword=sales" \
  -H "Authorization: Bearer <jwt>"
```

### Export Data

```bash
curl -s -X POST http://localhost:6443/api/dynamic/sc_showcase/export \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{"format": "excel", "conditions": [{"field":"status","operator":"EQ","value":"active"}]}'
```

Returns a `downloadUrl` to fetch the generated file.

### Get Page Metadata

Rich metadata endpoint for mobile apps and external integrations:

```bash
curl -s http://localhost:6443/api/dynamic/sc_showcase_list/meta \
  -H "Authorization: Bearer <jwt>"
```

Returns model fields (with types and enum options), page schema, user permissions, and available view types.

### Joint Save (Master + Sub-Tables)

Save a parent record and related child records in a single transaction:

```bash
curl -s -X POST http://localhost:6443/api/dynamic/order/joint-save \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "masterData": {"customer_name": "Acme Corp", "total_amount": 1000},
    "tables": {
      "items": [
        {"product_id": "P1", "quantity": 2, "unit_price": 100},
        {"product_id": "P2", "quantity": 3, "unit_price": 200}
      ]
    },
    "replaceExisting": true
  }'
```

### Record Capabilities

Get available actions for a specific record, filtered by permissions and state:

```bash
curl -s "http://localhost:6443/api/dynamic/sc_showcase/01HXYZ/capabilities?platform=web&context=detail" \
  -H "Authorization: Bearer <jwt>"
```

---

## Permissions

API endpoints enforce role-based access control. Dynamic CRUD endpoints use permission codes in the pattern:

```
model.{pageKey}.read
model.{pageKey}.create
model.{pageKey}.update
model.{pageKey}.delete
model.{pageKey}.export
model.{pageKey}.import
```

A `403 Forbidden` response is returned when the authenticated user lacks the required permission.

---

## Pagination

All list endpoints use offset-based pagination by default:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `pageNum` | 1 | Current page (1-indexed) |
| `pageSize` | 10 | Records per page (max 500) |

The response includes:

```json
{
  "records": [...],
  "total": 142,
  "pageNum": 1,
  "pageSize": 20,
  "totalPages": 8,
  "nextCursor": 1234
}
```

For high-volume tables, use **keyset pagination** by passing the `cursor` parameter. When `cursor` is provided, `pageNum` is ignored and the query uses `WHERE id > cursor` for efficient scanning.

---

## Rate Limiting

| Endpoint | Limit |
|----------|-------|
| `POST /api/auth/login` | Per-IP + per-email throttle |
| `POST /api/auth/forgot-password` | 3 requests per IP per minute |
| `POST /api/auth/reset-password` | 5 requests per IP per minute |

Exceeding limits returns a `429 Too Many Requests`-style error (code `400` with rate limit message).

---

## OpenAPI / Swagger

AuraBoot ships with Swagger UI for interactive API exploration:

```
http://localhost:6443/swagger-ui.html
```

OpenAPI spec (JSON):

```
http://localhost:6443/v3/api-docs
```

API groups: `meta`, `dynamic`, `plugins`, `auth`, `bpm`, `connectors`, `all`.
