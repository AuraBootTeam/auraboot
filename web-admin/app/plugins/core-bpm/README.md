# core-bpm

> BPM (Business Process Management) core plugin.

Contributes:
- `/bpm/task-center` — task management and approval inbox (unified entry point as of 2026-04-17)
- `/bpm/process-status` — read-only BPMN viewer (deep link)
- `/bpm/sla-monitor` — SLA dashboard

This plugin is the **M3 pilot migration** — first conversion of a
hardcoded route group to a `definePlugin`-driven module. The component
implementations still live in `~/bpm/components/` (shared with the
designer); only the route entries and registration moved here.

Future iterations will pull `~/bpm/components/` into this plugin's
`./components/` subdir as well.
