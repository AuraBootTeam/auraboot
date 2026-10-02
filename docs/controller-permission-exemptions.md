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
