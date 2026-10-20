# Customer delivery extension

This config plugin demonstrates a governed customer extension over the standard project application. It adds a follow-up action and workspace link to the declared `delivery-follow-up` slot, and owns the `/p/c/cde_delivery_workspace` composite DSL page. It does not fork the project React pages or create a second project model.

Install the current `project-management` plugin first, including its declared page slot. Validate and publish this plugin with the product CLI against your local development URL:

```sh
aura plugin validate plugins/customer-delivery-extension
aura plugin publish plugins/customer-delivery-extension --target http://localhost:8080 --yes
```

Use an existing authenticated CLI session or `AURA_TOKEN`; never put a raw token into screenshots or source control. The Enterprise customer-delivery initializer installs dependencies in order on a fresh local setup. Updating a retained runtime preserves its business data; explicitly publish changed plugins rather than resetting the database.

After publishing to an existing runtime, open **System management → Plugins → Installed** and explicitly enable **Customer delivery extension**. A fresh Enterprise scenario initializer enables this declared scenario dependency; an update preserves an administrator’s existing enable/disable choice.

Open **Project delivery → Projects**, view a project, and use **Record delivery follow-up**. The dialog collects the next action and executes the existing `tpm:update_project` command for the current project. Reopen the project to verify the saved value. The dedicated workspace shows the existing tenant-scoped `tpm_delivery_brief` query, lets you select one project, and writes through the same command. Only `tpm.project.manage` can write; readers can inspect projects. Selection, UI disablement and visibility do not replace server authorization or optimistic concurrency checks.

Disabling the plugin must remove its runtime contribution; the base project page remains intact. Missing slots, duplicate contribution IDs and block conflicts must be rejected during import/composition. Re-enabling restores the contribution and its independently owned page. No dynamic script execution or undeclared hook lifecycle is promised by this plugin.
