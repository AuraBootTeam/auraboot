# Multi-Tenancy

Understand AuraBoot's row-level tenant isolation, provision new tenants, and manage cross-tenant administration.

## Goal

By the end of this guide you will be able to:

- Understand the multi-tenant data isolation architecture
- Provision a new tenant via the bootstrap API
- Switch between tenants as an admin user
- Configure tenant-specific customizations
- Manage cross-tenant administration as a super admin

## Prerequisites

- AuraBoot instance running
- Admin or platform admin access
- Understanding of [Core Concepts](../core-concepts/) (models, fields, permissions)

---

## 1. Architecture Overview

AuraBoot uses **row-level isolation** for multi-tenancy. Every tenant's data lives in the same database, separated by a `tenant_id` column that is automatically enforced at the framework level.

### Data domain model (G0-G4)

All tables in AuraBoot are classified into five data domains:

| Domain | Scope | `tenant_id` | Examples |
|--------|-------|-------------|---------|
| **G0** Global Dictionary | Shared static data | None | Administrative divisions, query operators |
| **G1** Identity / Control Plane | System-level config | None | `ab_user`, `ab_tenant`, `ab_platform_account` |
| **G2** Platform Runtime | Platform operations (system tenant) | `1` | Marketplace plugins, plans, features |
| **G3** Tenant Configuration | Per-tenant config | Per tenant | Roles, permissions, menus, DSL metadata |
| **G4** Business Data | Per-tenant business data | Per tenant | Dynamic tables (`mt_*`) |

### How isolation works

```
SQL query: SELECT * FROM mt_crm_lead WHERE status = 'new'

TenantLineInterceptor automatically rewrites to:
SELECT * FROM mt_crm_lead WHERE status = 'new' AND tenant_id = 2

No manual WHERE clause needed. No data leaks possible.
```

The `TenantLineInterceptor` (a MyBatis-Plus plugin) automatically appends `AND tenant_id = ?` to every query on tenant-scoped tables. G0 and G1 tables are in the ignore list and are not filtered.

---

## 2. System Modes

AuraBoot supports three system modes, set once at bootstrap time:

| Mode | Registration | Login | Tenant Switching | Use Case |
|------|-------------|-------|-----------------|----------|
| **SINGLE** | Auto-join default tenant | No selection needed | Not supported | Simple deployments, single-org |
| **MULTI** | Create new tenant or join existing | Select tenant if multiple | Supported | SaaS platforms, multi-org |
| **HYBRID** | Same as MULTI + optional default tenant | Same as MULTI | Supported | Mixed deployments |

The system mode is **immutable** after bootstrap. Changing it requires a full database reset.

---

## 3. Tenant Provisioning

### Via Bootstrap API (first tenant)

When AuraBoot starts for the first time, use the bootstrap API to create the initial admin and default tenant:

```bash
POST /api/bootstrap/setup
Content-Type: application/json

{
  "adminEmail": "admin@example.com",
  "adminPassword": "SecurePassword123",
  "companyName": "Acme Corporation",
  "systemMode": "MULTI"
}
```

This executes a 15-step pipeline:

1. Write system configuration (mode, platform name)
2. Create system tenant (id=1)
3. Create platform account
4. Create admin user
5. Create default business tenant
6. Add admin to both tenants
7. Bootstrap default tenant (roles, permissions, menus)
8. Import built-in plugins
9. Initialize marketplace (optional)
10. Finalize: mark `system.initialized=true`

### Check bootstrap status

```bash
GET /api/bootstrap/status
```

Response (initialized):

```json
{
  "code": "0",
  "data": {
    "initialized": true,
    "inProgress": false,
    "mode": "single",
    "missingParts": [],
    "reason": null
  }
}
```

Response (not initialized):

```json
{
  "code": "0",
  "data": {
    "initialized": false,
    "inProgress": false,
    "mode": null,
    "missingParts": ["system_config"],
    "reason": "Bootstrap not completed"
  }
}
```

When `initialized=false`, the web UI renders a top banner linking to `/setup`
instead of silently redirecting. The authoritative signal is `system_config.system.initialized`,
written by `BootstrapEngineService` after all bootstrap steps complete.

### Creating additional tenants (MULTI mode)

In MULTI mode, new tenants can be created through:

1. **Self-registration** -- if `allow_self_registration` is enabled, new users who register are guided to create a new tenant or join an existing one
2. **Admin provisioning** -- a platform admin creates tenants through the admin UI

When a new tenant is created, the `TenantBootstrapService` automatically:

- Creates default roles (Admin, User)
- Assigns base permissions
- Creates the default menu structure
- Installs tenant-level plugin configurations

---

## 4. Tenant Switching

### Login flow with tenant selection

In MULTI/HYBRID mode, the login process includes tenant selection:

```
1. POST /api/auth/login
   -> Returns initial JWT (no tenant context)

2. GET /api/tenant-selection/my-spaces
   -> Returns list of tenants the user belongs to

3. POST /api/tenant-selection/process
   Body: { "action": "select", "tenantId": 2 }
   -> Returns JWT with tenant context (tenantId embedded in claims)
```

### Via CLI

```bash
# Interactive login (shows tenant selection menu)
aura login

# Login to a specific tenant
aura login --tenant "Acme Corporation"

# Switch to the system/platform admin space
aura login --tenant System
```

### In the web UI

1. Click your avatar in the top-right corner
2. Select **Switch Space**
3. Choose the target tenant from the list
4. The page reloads with the new tenant context

---

## 5. Data Isolation Details

### Automatic tenant filtering

The `TenantLineInterceptor` handles all standard CRUD operations:

| Operation | Behavior |
|-----------|----------|
| SELECT | Appends `AND tenant_id = ?` |
| INSERT | Sets `tenant_id` on the new record |
| UPDATE | Appends `AND tenant_id = ?` |
| DELETE | Appends `AND tenant_id = ?` |

### Tables excluded from tenant filtering

G0 and G1 tables do not have `tenant_id` and are excluded from the interceptor:

- `ab_user` -- users can belong to multiple tenants
- `ab_tenant` -- tenant definitions themselves
- `ab_platform_account` -- platform-level accounts
- `ab_platform_license` -- licenses
- `ab_system_config` -- system configuration
- Global dictionary tables

### Accessing platform data

When code needs to query G2 tables (marketplace, plugins), it must temporarily switch to the system tenant context:

```java
// Correct: use SystemTenantContextExecutor
List<Plugin> plugins = SystemTenantContextExecutor.executeAsSystem(() ->
    pluginMapper.selectList(null)
);

// Wrong: querying G2 table in a customer tenant context returns empty results
```

### Dynamic tables

Business data tables follow the naming convention `mt_{model_code}`. Each has a `tenant_id` column:

```sql
CREATE TABLE mt_crm_lead (
    id BIGSERIAL PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL,
    tenant_id BIGINT NOT NULL,
    -- business fields...
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_mt_crm_lead_tenant ON mt_crm_lead(tenant_id);
```

---

## 6. Cross-Tenant Administration

### Platform admin capabilities

Users with the platform admin role can:

| Capability | Description |
|------------|-------------|
| View all tenants | List and inspect tenant metadata |
| Create tenants | Provision new tenants |
| Manage tenant members | Add/remove users from tenants |
| View platform metrics | Cross-tenant usage statistics |
| Manage marketplace | Publish/unpublish plugins |
| License management | Assign and revoke licenses |

### Accessing the platform admin space

```bash
# Via CLI
aura login --tenant System

# Via web UI
Switch to the "System" space from the tenant selector
```

The system tenant (id=1) is the namespace for platform administration, not for business data.

---

## 7. Tenant-Specific Customization

### Branding and settings

Each tenant can customize:

| Setting | Scope | Configuration |
|---------|-------|---------------|
| Company name | Per tenant | Tenant settings |
| Logo | Per tenant | Upload in settings |
| Theme colors | Per tenant | Theme configuration |
| Default language | Per tenant | System config |
| Date/time format | Per tenant | Locale settings |

### Roles and permissions

Each tenant has its own role and permission hierarchy:

```
Tenant: Acme Corporation
  Roles:
    - Tenant Admin (all permissions)
    - Sales Manager (CRM + reports)
    - Sales Rep (CRM read/write, no delete)
    - Viewer (read-only)
```

Roles are defined in the tenant's `default-bootstrap.json` template and can be customized after creation.

### Menu customization

Each tenant can configure its own menu structure. The default menu is created from the bootstrap template, but admins can:

- Hide/show menu items
- Reorder menu items
- Create custom menu groups

### Plugin configuration

Plugins are installed per-tenant. Each tenant can have different plugins enabled:

```
Tenant A: CRM + Project Management + HR
Tenant B: CRM + Inventory + Finance
Tenant C: CRM only
```

---

## 8. Complete Example: Set Up a New Tenant with Custom Roles

### Step 1: Create the tenant

As a platform admin:

```bash
# Login as platform admin
aura login --tenant System

# The tenant creation is typically done via the web UI admin panel
```

### Step 2: Bootstrap with custom roles

When a new tenant is provisioned, the bootstrap process creates default roles. After creation, customize via the admin UI:

1. Navigate to **Settings > Roles**
2. Create custom roles:

| Role | Permissions |
|------|------------|
| Sales Manager | `DYNAMIC.crm_lead.*`, `DYNAMIC.crm_opportunity.*`, `DYNAMIC.crm_account.*` |
| Sales Rep | `DYNAMIC.crm_lead.read`, `DYNAMIC.crm_lead.create`, `DYNAMIC.crm_opportunity.read` |
| Finance Viewer | `DYNAMIC.fin_invoice.read`, `DYNAMIC.fin_payment.read` |

### Step 3: Add users to the tenant

1. Navigate to **Settings > Members**
2. Invite users by email
3. Assign roles to each user

### Step 4: Install plugins

```bash
# Switch to the new tenant
aura login --tenant "New Corp"

# Import plugins
aura plugin publish plugins/crm --yes
aura plugin publish plugins/project-management --yes
```

### Step 5: Verify isolation

```bash
# Query leads in the new tenant -- should be empty (new tenant)
aura query crm_lead -n 5

# Switch back to original tenant
aura login --tenant "Acme Corporation"

# Query leads -- shows Acme's data, not New Corp's
aura query crm_lead -n 5
```

---

## 9. Security Considerations

### Tenant isolation guarantees

| Layer | Mechanism | Bypass Risk |
|-------|-----------|-------------|
| Database queries | `TenantLineInterceptor` auto-appends `tenant_id` | None (framework-level) |
| API requests | JWT contains `tenantId` claim | Token theft (mitigated by HTTPS + expiry) |
| File storage | Tenant-prefixed paths | None (path includes tenant ID) |
| Cache (Redis) | Tenant-prefixed keys | None (key includes tenant ID) |
| Background jobs | Tenant context propagated | Must use `TenantContextPropagator` |

### Common security rules

- **Never** manually add `tenant_id` to WHERE clauses -- the interceptor handles it
- **Never** trust client-supplied `tenant_id` -- always use the JWT-extracted value
- **Never** query G3/G4 tables without tenant context -- results will be wrong
- **Always** use `SystemTenantContextExecutor` when accessing G2 (platform) tables
- **Always** validate tenant membership before granting access

### Audit trail

All data changes are tracked per-tenant in the activity timeline, including:

- Who performed the action
- When it happened
- What changed (field-level diff)
- Which tenant context was active

---

## Troubleshooting

| Symptom | Cause | Solution |
|---------|-------|----------|
| Empty data after login | Wrong tenant selected | Check JWT claims; switch to correct tenant |
| "Access forbidden" on API calls | User not a member of the current tenant | Add user to the tenant |
| Cross-tenant data visible | Custom SQL bypassing interceptor | Use MyBatis Mapper, not raw JDBC |
| Bootstrap fails | System already initialized | Check `/api/bootstrap/status`; reset DB if needed |
| Tenant switching not available | System mode is SINGLE | Requires MULTI or HYBRID mode (set at bootstrap) |
| New tenant has no menus | Bootstrap template missing | Check `default-bootstrap.json` template |

---

## Next Steps

- [Notifications](notifications.md) -- tenant-isolated notification delivery
- [CLI Reference](cli-reference.md) -- manage tenants and data from the command line
