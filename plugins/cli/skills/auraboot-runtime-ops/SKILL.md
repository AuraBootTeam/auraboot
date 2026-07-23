---
name: auraboot-runtime-ops
description: Use when operating a live AuraBoot instance — querying data, running commands, analyzing results, and handling human-in-the-loop approvals. Guides you through the aura CLI runtime commands for day-to-day operations.
---

# AuraBoot Runtime Ops

Operate a live instance: read data, run commands, review agent runs and approvals. **Reads are free; writes go through commands and may require approval.**

## Before you start

```bash
aura status && aura doctor
```

## Query and analyze

```bash
aura query <entity> -f status=OPEN -f amount>1000 -n 50      # JSON, pipeable
aura query <entity> --nq <named_query_code>                  # aggregations / dashboards
aura query <entity> | aura analyze "summarize by owner"      # stdin -> AI -> stdout
```

Domain shortcuts exist too: `aura crm leads`, `aura finance invoices`, `aura inventory low-stock`, etc. Use `--help` on any of them.

## Run commands

```bash
aura exec <plugin>:<command_code> --set field=value --dry-run   # preview
aura exec <plugin>:<command_code> --set field=value            # execute
```

## Agents, runs, and approvals (human-in-the-loop)

```bash
aura ops agents list
aura ops runs list
aura ops approvals list                    # pending agent actions
aura ops approvals approve <pid>
aura ops approvals reject <pid> -r "reason"
aura ops audit list                        # governance / compliance trace
```

High-risk agent actions pause for approval — approve or reject them explicitly rather than widening permissions to bypass the gate.
