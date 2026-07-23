---
name: auraboot-permissions
description: Use when defining or reviewing AuraBoot access control — roles, permissions, RBAC. Guides you to author permission codes and role grants in plugin config, validate completeness, and verify as a non-admin role (not just admin).
---

# AuraBoot Permissions (RBAC)

Author roles and permissions as plugin config; the platform is the sole authority on access — **a Skill hint never substitutes for a server-side permission check.**

## Before you start

```bash
aura status && aura doctor
aura plugin --help
```

## Author

- Permission codes follow `module.resource.action` (e.g. `crm.lead.read`). Keep them consistent — the platform gate is case-sensitive.
- Declare permissions in `permissions.json`, grant them to roles in `roles.json`, and reference them from `menus.json` / pages / commands.
- Every menu/page/command that references a permission must have that permission declared, or it fails governance validation.

## Validate

```bash
aura plugin validate . --agent-mode
```

Governance layer flags undeclared permissions referenced by menus, missing grants, and circular dependencies. Fix all before import.

## Verify as a real role — not as admin

**"Admin can use it" ≠ "the system works."** Admin holds every atomic code and never hits a wall, so it masks gaps. After import, exercise each capability **as an ordinary role** (a non-admin test user):

- anonymous → expect 401
- authenticated but ungranted → expect 403
- granted → expect success
- cross-tenant → expect isolation

When a permission is denied unexpectedly, read the platform permission audit log for the real `permissionCode` rather than guessing at scope/owner.
