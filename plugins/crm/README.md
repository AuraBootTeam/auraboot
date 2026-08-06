# AuraBoot CRM

`com.auraboot.crm` is the official CRM package shipped with AuraBoot and the
single product-grade CRM implementation in the public repository. The legacy
`crm-quick-start` learning template has been removed to keep one canonical CRM.

## Included

- accounts, contacts, leads, opportunities, activities, campaigns, and complaints;
- lead scoring and assignment rules;
- opportunity pipeline Kanban, sales forecast, and manager workbenches;
- consent, subscriptions, segmentation, attribution, journeys, SLA, and analytics;
- a thin PF4J backend for business logic that cannot be expressed safely as DSL.

The core has no business-plugin dependency. Product selection, sales-order creation,
finance rate-source metadata, and industry packages are downstream extensions. In
particular, `crm:win_opportunity` only closes the opportunity in the core package;
installing the Sales package adds atomic, idempotent draft-order creation through a
secondary command handler.

## Build and validate

```bash
cd plugins/crm/backend
gradle test jar

cd ../../..
node --test plugins/crm/tests/*.test.mjs
node scripts/check-dsl-actions.mjs plugins/crm
```

The generated JAR is written to
`plugins/crm/backend/build/libs/crm-plugin-1.0.0.jar`, matching `plugin.json`.

## Licensing

This package is distributed under the repository's AuraBoot License. The repository
is public and source-available; consult the root `LICENSE` for permitted use and
redistribution terms.
