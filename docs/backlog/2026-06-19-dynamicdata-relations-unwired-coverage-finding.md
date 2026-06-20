---
title: DynamicData relation/sub-table methods are unreachable (loadModelRelations is a stub)
created: 2026-06-19
type: backlog
status: shipped
area: meta/dynamic-data
related: docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md
---

# Finding: DynamicData relation CRUD happy paths are unreachable code

## What

While driving OSS backend coverage toward 0.80, the single biggest line-coverage gap in
`meta/service/impl` is `DynamicDataServiceImpl`'s relation/sub-table cluster — about **336
uncovered lines** across `getRelationData` (97), `saveWithRelations` (88), `createRelations` (32),
`removeRelations` (30), `enrichReferenceDisplayFields` (56), `resolveReferenceDisplayColumn` (22),
`findRelationByName` (11), `deleteExistingChildRecords` (16).

Those methods all resolve a relation through `model.getRelations()` /
`MetaModelService.getModelRelations(modelCode)`:

```java
// DynamicDataServiceImpl.findRelation / findRelationByName
if (model.getRelations() == null || model.getRelations().isEmpty()) {
    return null;            // -> caller throws "Relation not found" or returns early
}
```

`ModelDefinition.getRelations()` is populated by `MetaModelServiceImpl.loadModelRelations`, which
is a **TODO stub**:

```java
private List<RelationDefinition> loadModelRelations(Long modelId) {
    // TODO: 从数据库加载关联关系
    return Collections.emptyList();
}
```

So `getModelRelations` always returns an empty list, `findRelation` always returns `null`, and the
relation CRUD **happy paths can never execute** — only the "relation not found" / "model declares
no relations" error branches are reachable. (Confirmed by `RelationSyncServiceImplCoverageIT`, whose
`getInverseFields` returns empty for every real model.)

## Why it matters

1. **Coverage:** ~336 lines of `DynamicDataServiceImpl` are effectively dead until relations are
   loaded from the DB. They inflate the "missed lines" denominator but cannot be closed by tests.
   The honest options are (a) implement `loadModelRelations`, or (b) exclude the unreachable relation
   methods from the jacoco denominator with this finding as justification. Writing a relation harness
   would only ever exercise the "not found" branch.
2. **Product:** the many-to-many / sub-table (`saveWithRelations`, `createRelations`) and
   reference-display-enrichment features are wired in the service layer but inert, because the
   metadata side (`loadModelRelations`) was never implemented. Any caller relying on
   `getModelRelations` / bidirectional relations / joint sub-table save currently gets empty results
   or a "relation not found" error.

## Suggested next step (product, not a test)

Implement `loadModelRelations(modelId)` to materialize `RelationDefinition`s from the reference-field
metadata (refTarget: targetModel / targetField / relationType / joinTable), then the relation CRUD
paths become reachable and testable. Until then, treat the relation cluster as out of scope for the
coverage gate and prefer option (b) if it blocks the 0.80 target.
