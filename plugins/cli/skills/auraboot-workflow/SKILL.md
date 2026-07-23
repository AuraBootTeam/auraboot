---
name: auraboot-workflow
description: Use when adding behavior to an AuraBoot model — commands, binding rules, and command execution. Guides you through defining a command, wiring binding rules correctly, and executing/dry-running it via the aura CLI + MCP tools.
---

# AuraBoot Workflow (commands & rules)

Add executable behavior to a model. All writes go through the command pipeline; **never hand-roll HTTP writes** — define a command and run it.

## Before you start

```bash
aura status && aura doctor
aura exec --help
```

## Author a command

- Create a command definition with the `create_command` MCP tool (`dryRun:true` first), or scaffold locally with `aura dsl scaffold`.
- `commands.json` `inputFields` is a **list of field codes** (`List<String>`); rich input schemas go in `inputSchema`, not `inputFields`.
- **Binding rules must be a separate `bindingRules.json`** registered in `resourceDirs`. Inline `bindingRules` inside `commands.json` are silently ignored on import.

## Run / dry-run

```bash
aura exec <plugin>:<command_code> --set field=value --set qty:int=3 --dry-run
aura exec <plugin>:<command_code> --from payload.json
```

- The payload lives under `{ payload: { ...fields }, operationType }` — not `{ data }`.
- The public JSON record-id field is `targetRecordPid` (not `recordId` / `targetRecordId`).
- `--dry-run` previews the request body without executing. Drop it to execute for real.

## Validate + verify

```bash
aura plugin validate . --agent-mode      # fix all errors, then import
```

A validator pass proves the command is well-formed, not that the button wired to it does anything. After import, execute the command (or drive the page action) and confirm the record actually changed — check via `aura query <model>` or the detail page.
