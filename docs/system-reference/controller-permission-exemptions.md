---
type: system-reference
status: active
---

# Controller Permission-Annotation Exemption Registry
Generated 2026-10-01 (batch4 E4). 232 `@RestController` classes; 132 carry `@RequirePermission`; the 100 below do not and rely on `SecurityConfig` (`anyRequest().authenticated()` / explicit `PermitAll`) plus service-layer scoping.
Policy: this registry is the year-reviewable record of WHY each controller has no `@RequirePermission`. New controllers default to annotated; removals from annotation need an entry here.

## agent (6)

Rationale: agent runtime/SSE streams — user-scoped by session identity; authorization enforced at service layer (A4 census: cross-tenant by design where applicable)
- `AgentDiscoveryController.java`
- `AgentSseController.java`
- `AiActionAuditController.java`
- `InterruptController.java`
- `UserSoulProfileController.java`
- `CrossTenantGrantController.java`

## agentchat (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `AgentEmployeeController.java`

## aisearch (2)

Rationale: global/tenant search over authorized models — scoping enforced by search query layer
- `AiSearchController.java`
- `GlobalSearchController.java`

## application (2)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `ApplicationIdentityController.java`
- `PlatformReleaseController.java`

## aurabot (4)

Rationale: in-app assistant conversation — bound to authenticated conversation sessions at service layer
- `AuraBotController.java`
- `AuraBotConversationController.java`
- `controller/AuraBotSkillController.java`
- `error/SkillExceptionHandler.java`

## auth (8)

Rationale: login/registration/session endpoints — inherently pre-auth (PermitAll in SecurityConfig)
- `AuthController.java`
- `DeactivationController.java`
- `DevVerifyCodeController.java`
- `LoginChannelController.java`
- `LoginChannelManageController.java`
- `TenantInvitationAcceptanceController.java`
- `VerifyCodeController.java`
- `WechatPcLoginController.java`

## automation (2)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `AutomationRunStreamController.java`
- `AutomationWebhookController.java`

## behavior (4)

Rationale: product-analytics ingest (public collector contract) + reads scoped by tenant at query layer
- `AnalyticsResultViewController.java`
- `BehaviorCollectController.java`
- `BehaviorQuarantineController.java`
- `KeyedCollectController.java`

## bi (2)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `DashboardDataController.java`
- `PivotQueryController.java`

## connector (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `AirflowWebhookController.java`

## conversation (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `ConversationTurnController.java`

## cswidget (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- ``

## currency (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `ExchangeRateController.java`

## dashboard (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `WorkbenchStatsController.java`

## datasync (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- ``

## dsl (3)

Rationale: read-only designer metadata endpoints — compile/introspection are tenant-scoped at service layer
- `controller/DslCompilerController.java`
- `ComponentSchemaController.java`
- `DslIntrospectionController.java`

## email (4)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `EmailAccountController.java`
- `EmailMessageController.java`
- `EmailSequenceController.java`
- `EmailTrackingController.java`

## engagement (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `UserEngagementController.java`

## entitlement (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `EntitlementController.java`

## environment (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `EnvironmentController.java`

## im (4)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `ImConversationController.java`
- `ImMessageController.java`
- `ImNotificationPreferenceController.java`
- `ImUnreadController.java`

## inbox (1)

Rationale: personal inbox — strictly user-scoped (user_id = principal), tenant filter irrelevant
- `InboxController.java`

## infrastructure (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `InfrastructureController.java`

## integration (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `ReliableIntegrationOperatorController.java`

## intent (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `IntentController.java`

## meta (21)

Rationale: meta-model CRUD — mutates go through CommandPipeline which enforces permission per command
- `AiFieldController.java`
- `AiModelSuggestionController.java`
- `NbaController.java`
- `AutoFillController.java`
- `BatchQueryController.java`
- `CascadeController.java`
- `ChartDataController.java`
- `CommandPipelineController.java`
- `DslRegistryController.java`
- `FormulaController.java`
- `McpAuditController.java`
- `QueryBuilderController.java`
- `RecordCapabilityController.java`
- `RecordCommentController.java`
- `ScheduleController.java`
- `UserProjectBindingController.java`
- `WatchController.java`
- `config/ActivityController.java`
- `config/AsyncTaskController.java`
- `config/RollUpController.java`
- `MetaApiExceptionHandler.java`

## mobile (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `MobileSearchController.java`

## notification (3)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `DeviceTokenController.java`
- `NotificationController.java`
- `NotificationSseController.java`

## openplatform (5)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `ExternalEventIngressController.java`
- `OpenPlatformFacadeController.java`
- `OpenPlatformManagementController.java`
- `OpenPlatformPublicController.java`
- `OpenPlatformTokenController.java`

## p1demo (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- ``

## permission (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `RecordShareController.java`

## plugin (4)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `controller/MarketplaceBrowseController.java`
- `controller/MarketplaceStripeWebhookController.java`
- `controller/SolutionBrowseController.java`
- `PluginRestDispatcher.java`

`PluginRestDispatcher.dispatch` explicitly delegates the annotation-level decision through
`@AuthenticatedAccess` to the registered route policy. This is not a route permission waiver:
authenticated routes still require caller context and their exact `RestRoute.permissionCode`
before entering the transaction/audit pipeline. Declared public routes retain the rate limit,
public tenant context, and cleanup contract. The default-deny policy for other handlers is unchanged.
The dispatcher tests exercise the real permission interceptor before dispatch, including permitted,
denied, missing-caller, and public paths.

## promotion (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `controller/ResourceReferenceController.java`

## review (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `ReviewController.java`

## saas (1)

Rationale: bootstrap/platform-admin surfaces — guarded by explicit role checks in service layer (AdminRoleChecker family)
- `controller/BootstrapController.java`

## tenant (1)

Rationale: tenant selection/creation before tenant context is fully bound
- `TenantSelectionController.java`

## timezone (1)

Rationale: per-tenant preference read — explicit tenantId parameter, scoped by parameter
- `TenantTimezoneController.java`

## user (3)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `SessionController.java`
- `UserPreferenceController.java`
- `UserProfileController.java`

## workbench (1)

Rationale: domain-specific scoping enforced at service layer; A4 census classification pending (TBD)
- `UserNoteController.java`

## Plugin REST dispatch authorization

`PluginRestDispatcher.dispatch` explicitly declares `@AuthenticatedAccess` as an authorization delegation boundary. The default-deny controller interceptor admits this method so that the registered route contract can decide access; it does not grant a permission or alter the global unannotated policy. For authenticated routes the dispatcher requires a current user and the exact registered `permissionCode` before invoking the governed pipeline. Explicit PUBLIC routes retain their per-IP rate limit, default-tenant public context and audit pipeline. Unmatched routes remain absent; unregistered handlers remain denied. Upstream authentication still protects non-public paths.

The combined MVC/interceptor regressions in `PluginRestDispatcherTest` verify permitted-route execution, rejection without the route permission, and public-route rate limiting with the shipped default-deny interceptor. Direct dispatcher tests alone do not cover this boundary.


## Domain application boundaries (2026-11-08)

Published operation permissions may declare `extension.domainModels`, a finite map from model code to native actions (`read`, `create`, `update`, `delete`). A domain level is an actual permission, not a grant expansion into model CRUD. Its action is `read`, `write`, or `manage`; higher levels imply lower levels within the same resource/domain. Only explicitly declared models change behavior. Other products retain the existing path.

`DomainCommandAuthorization` admits one command function decision and retains its role-local binding provenance for the synchronous invocation. `BusinessCommandRecordAuthorization` continues to evaluate record scope, conditions, and fields for the actual record. `dataPolicyReferences` retains existing operation-bound constraints; generated model bindings are also consulted as constraints, never positive function fallbacks. `dataControlledReads` keeps sensitive model reading on its existing data-control grants. Scope rows and field/masking policies are not rewritten.

For the explicitly listed native CRUD methods in `PermissionInterceptor`, HTTP authentication remains mandatory and the domain operation check executes in `DynamicDataServiceImpl`, so direct non-HTTP calls use the same boundary. Metadata endpoints, the list/query namespace admission, other controllers, and other products retain their existing interceptor checks. Native reads with empty results still require application authorization. Export downloads re-run `DynamicDataService.exportData` and its current data/field policy before releasing bytes.

Asynchronous delivery re-establishes the actor and uses the same command/domain declaration with current authorization. Transaction-local internal resource handles are not serialized across the queue. Domain refusal cannot fall back to record-sharing as a separate function grant; sharing remains part of record access once domain eligibility is satisfied. Internal handlers do not require separately granted child model-operation codes. Tenant, relationship, field, writer, version, and transaction constraints remain enforced.

The permission mapper uses its generated entity result map for catalog, active definitions, and ID/code lookups consumed by these boundaries. JSONB extension values must hydrate as structured objects, not strings. Real PostgreSQL regression coverage is in `BusinessCommandGrantPostgresIT`.
