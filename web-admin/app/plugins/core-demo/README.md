# core-demo

Hello-world plugin proving the kernel ↔ plugin contract end-to-end.

When `pluginLoader.install(...)` + `enable(...)` + `activateAll()` runs,
this plugin contributes one navigation resource at `/_demo`.

Used as:
- Smoke test for the framework runtime
- Canonical example for future `core-*` plugin migrations

Remove once `core-system` / `core-iam` / etc. all follow the same shape.
