---
type: system-reference
status: active
created: 2026-10-27
---

# Application-module command metadata lifecycle

Module-owned command handlers stay in their child Spring contexts. Runtime dispatch and import-time semantic validation must see the same live handlers; indexing a module bean a second time as a PF4J handler is not a registration workaround.

After all child contexts for a plugin have been registered in the live module map, the host publishes ApplicationModulesChangedEvent outside the module registry monitor. PluginExtensionRegistryBridge synchronously reconciles import metadata from the current ExtensionRegistry. This also covers modules loaded after ApplicationReadyEvent and does not depend on ordering among ready listeners. Existing child ApplicationModuleReadyEvent delivery remains unchanged.

Module unload removes the child contexts before notifying the host. The bridge removes only metadata it previously installed and still owns; built-in metadata and a later replacement owner remain intact. Re-registering a live module is idempotent, and unloading/reloading recreates metadata from live beans. The notification occurs outside the module monitor to avoid holding it while a listener queries the live registry.

Metadata registration permits semantic validation to resolve a handler code. It does not authorize a user, grant a command or record scope, bypass model/field writers, or prove that a product command has executed successfully. Native product qualification must still parse/import actual configuration after cold startup and exercise the authorized command path. A successful module startup log alone is insufficient.
