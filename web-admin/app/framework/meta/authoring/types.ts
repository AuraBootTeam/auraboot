import type React from 'react';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';

export type AuthoringMode = 'select' | 'interact';
export type AuthoringNodeKind = 'page' | 'block' | 'field' | 'action';

export interface AuthoringOwnership {
  ownershipScope: 'TENANT';
  sourceOwnershipScope: 'PLATFORM' | 'APPLICATION' | 'TENANT';
  sourcePagePid: string;
  overridePid?: string | null;
  origin: 'DESIGN_STUDIO' | 'ENV_PROMOTION' | 'PRODUCTION_CONTEXTUAL_HOTFIX' | 'TENANT_OVERRIDE';
  tenantOverride: boolean;
  sourceMutable: boolean;
  restoreTarget: 'PLATFORM' | 'APPLICATION' | 'TENANT';
}

export interface AuthoringSession {
  sessionPid: string;
  changeSetPid: string;
  pagePid: string;
  ownership?: AuthoringOwnership;
  ownerUserId: number;
  changeSetStatus: string;
  workspaceMode: 'AUTHORING' | 'OBSERVER' | 'REVIEW';
  state: string;
  revision: number;
  riskLevel: string;
  route: string;
  publishPolicy: string;
  validationState: string;
  validation?: AuthoringValidationSummary | null;
  impactState: 'UNKNOWN' | 'KNOWN' | 'STALE' | 'FAILED';
  impact?: AuthoringImpactSummary | null;
  approvalState: string;
  publishState: string;
  manifestChecksum: string;
  snapshot: Record<string, unknown>;
  interactionContext: Record<string, unknown>;
  writerLease?: AuthoringWriterLease;
  expiresAt: string;
}

export interface AuthoringValidationIssue {
  code: string;
  severity: string;
  changeItemPid?: string | null;
  blockId?: string | null;
  propertyPath: string;
  messageKey: string;
}

export interface AuthoringValidationSummary {
  validationRunPid: string;
  revision: number;
  status: 'VALID' | 'INVALID';
  errorCount: number;
  issues: AuthoringValidationIssue[];
  validatedAt: string;
}

export interface AuthoringImpactDependency {
  resourceType: string;
  resourceCode: string;
  resourcePid: string;
  version: number;
  rowVersion: number;
}

export interface AuthoringImpactSummary {
  impactRunPid: string;
  revision: number;
  status: 'KNOWN' | 'FAILED';
  dependencyChecksum?: string | null;
  dependencies: AuthoringImpactDependency[];
  failureCode?: string | null;
  analyzedAt: string;
}

export type AuthoringWriterLeaseStatus =
  | 'OWNED'
  | 'HELD_BY_OTHER'
  | 'HELD_BY_OTHER_SESSION'
  | 'EXPIRED';

export interface AuthoringWriterLease {
  status: AuthoringWriterLeaseStatus;
  revision: number;
  leasedUntil: string;
}

export interface PropertyCapability {
  propertyPath: string;
  allowedOperations: string[];
  route: string;
  risk: string;
  effectTags: string[];
  reversibility: string;
  protectedSemantic: boolean;
  rolePreviewRequired: boolean;
}

export interface CapabilityManifest {
  blockType: string;
  pluginCode: string;
  pluginVersion: string;
  manifestVersion: string;
  checksum: string;
  properties: Record<string, PropertyCapability>;
}

export interface CapabilityRegistry {
  checksum: string;
  manifests: CapabilityManifest[];
}

export interface NewPageWorkspaceOption {
  value: string;
  label: string;
}

export interface NewPageWorkspaceOptions {
  models: NewPageWorkspaceOption[];
  parentMenus: NewPageWorkspaceOption[];
  permissions: NewPageWorkspaceOption[];
}

export interface CreateNewPageWorkspaceInput {
  pageKey: string;
  name: string;
  title: string;
  description?: string;
  kind: 'list' | 'form' | 'detail';
  modelCode: string;
  parentMenuCode: string;
  menuCode: string;
  menuName: string;
  menuPath: string;
  menuIcon?: string;
  permissionCode: string;
}

export interface AuthoringReviewWorkspace {
  session: AuthoringSession;
  capabilities: CapabilityRegistry;
}

export interface AuthoringRolePreviewTarget {
  rolePid: string;
  roleCode: string;
  roleName: string;
}

export type AuthoringRoleStructureNodeType = 'MENU' | 'BLOCK' | 'FIELD' | 'ACTION';

export interface AuthoringRoleStructureDecision {
  nodeType: AuthoringRoleStructureNodeType;
  nodeId: string;
  label: string;
  permissionCode?: string | null;
  allowed: boolean;
  visible: boolean;
  writable: boolean;
  reason: 'UNRESTRICTED' | 'ALLOW' | 'TARGET_ROLE_DENY' | 'ACTOR_SCOPE_LIMIT';
}

export interface AuthoringRoleStructurePreview {
  mode: 'STRUCTURE';
  pagePid: string;
  targetRole: AuthoringRolePreviewTarget;
  actorIntersectionApplied: true;
  businessDataIncluded: false;
  exportAllowed: false;
  businessActionsAllowed: false;
  decisions: AuthoringRoleStructureDecision[];
}

export interface AuthoringSyntheticPreviewWidget {
  source: 'GENERATED_IN_MEMORY';
  value: string;
  series: Array<{ label: string; value: number }>;
}

export interface AuthoringSyntheticPreview {
  mode: 'SYNTHETIC';
  pagePid: string;
  source: 'GENERATED_IN_MEMORY';
  isolatedFromTenantData: true;
  persisted: false;
  exportAllowed: false;
  businessActionsAllowed: false;
  fixtureRevision: number;
  formValues: Record<string, unknown>;
  records: Array<Record<string, unknown>>;
  widgets: Record<string, AuthoringSyntheticPreviewWidget>;
}

export type AuthoringIdentitySimulationStatus = 'ACTIVE' | 'ENDED' | 'EXPIRED';

export interface AuthoringIdentitySimulation {
  simulationPid: string;
  mode: 'AUDITED_IDENTITY';
  sourceSessionPid: string;
  pagePid: string;
  targetRole: AuthoringRolePreviewTarget;
  actorIntersectionApplied: true;
  businessDataIncluded: false;
  readOnly: true;
  exportAllowed: false;
  businessActionsAllowed: false;
  status: AuthoringIdentitySimulationStatus;
  startedAt: string;
  expiresAt: string;
  endedAt?: string | null;
  decisions: AuthoringRoleStructureDecision[];
}

export interface AuthoringChangeItem {
  changeItemPid: string;
  sourceChangeItemPid?: string | null;
  blockId: string;
  propertyPath: string;
  operation: string;
  riskLevel: string;
  route: string;
  publishPolicy: string;
  reversibility: string;
  actorUserId: number;
  dependencySnapshot: string[];
  createdAt: string;
}

export interface AuthoringSplitResult {
  sourceSession: AuthoringSession;
  targetSession: AuthoringSession;
  sourceItems: AuthoringChangeItem[];
  targetItems: AuthoringChangeItem[];
  lineage: Array<{
    changeSetPid: string;
    revision: number;
    relation: 'SPLIT_FROM';
  }>;
}

export interface AuthoringRelease {
  releasePid: string;
  changeSetPid: string;
  changeSetRevision: number;
  previousReleasePid?: string | null;
  status: 'ACTIVE';
  manifestChecksum: string;
  channelVersion: number;
  activatedAt: string;
}

export interface AuthoringRollbackEligibility {
  eligible: boolean;
  reasonCode:
    | 'ELIGIBLE'
    | 'NO_ACTIVE_RELEASE'
    | 'NO_PREVIOUS_RELEASE'
    | 'PREVIOUS_RELEASE_UNAVAILABLE'
    | 'CONTAINS_COMPENSATABLE_CHANGES'
    | 'CONTAINS_FORWARD_ONLY_CHANGES';
  targetReleasePid?: string | null;
  reversibleItemCount: number;
  compensatableItemCount: number;
  forwardOnlyItemCount: number;
}

export interface AuthoringReleaseHistoryItem {
  releasePid: string;
  changeSetPid: string;
  changeSetRevision: number;
  previousReleasePid?: string | null;
  status: 'ACTIVE' | 'SUPERSEDED' | 'ROLLED_BACK' | 'FAILED' | 'PREPARING';
  reversibility: 'REVERSIBLE' | 'COMPENSATABLE' | 'FORWARD_ONLY';
  manifestChecksum: string;
  createdAt: string;
  activatedAt?: string | null;
}

export interface AuthoringReleaseHistory {
  resourcePid: string;
  activeReleasePid?: string | null;
  previousReleasePid?: string | null;
  channelVersion: number;
  rollbackEligibility: AuthoringRollbackEligibility;
  items: AuthoringReleaseHistoryItem[];
  page: number;
  size: number;
  total: number;
}

export interface AuthoringNode {
  id: string;
  sourceId: string;
  kind: AuthoringNodeKind;
  blockType: string;
  label: string;
  parentId: string | null;
  depth: number;
  source: Record<string, unknown>;
  children: AuthoringNode[];
}

export interface HandoffCreated {
  contextId: string;
  targetRoute: string;
  expiresAt: string;
}

export interface HandoffContext {
  pagePid: string;
  changeSetPid: string;
  sessionPid: string;
  revision: number;
  intent: string;
  targetRoute: string;
  returnTo: string;
  blockId?: string | null;
  propertyPath?: string | null;
  interactionContext: Record<string, unknown>;
  expiresAt: string;
}

export type PatchOperation = 'ADD' | 'REPLACE' | 'REMOVE';

export interface BoundaryDecision {
  route: string;
  risk: string;
  publishPolicy: string;
  reason: string;
  manifestChecksum: string;
  rolePreviewRequired: boolean;
}

export interface AuthoringAiPatchProposalItemRequest {
  blockId: string;
  propertyPath: string;
  operation: PatchOperation;
  value?: unknown;
  manifestChecksum: string;
}

export interface AuthoringAiPatchProposalItem extends AuthoringAiPatchProposalItemRequest {
  ordinal: number;
  previousValue: unknown;
  decision: BoundaryDecision;
}

export type AuthoringAiPatchProposalStatus = 'PROPOSED' | 'APPLIED' | 'REJECTED';

export interface AuthoringAiPatchProposal {
  proposalPid: string;
  sourceSessionPid: string;
  changeSetPid: string;
  pagePid: string;
  baseRevision: number;
  registryChecksum: string;
  proposalHash: string;
  status: AuthoringAiPatchProposalStatus;
  aggregateRisk: string;
  aggregateRoute: string;
  publishPolicy: string;
  typedPatchOnly: true;
  requiresHumanApproval: true;
  items: AuthoringAiPatchProposalItem[];
  resultRevision?: number | null;
  createdAt: string;
  appliedAt?: string | null;
  rejectedAt?: string | null;
}

export interface ApplyAuthoringAiPatchProposalResult {
  proposal: AuthoringAiPatchProposal;
  session: AuthoringSession;
}

export interface PatchResult {
  session: AuthoringSession;
  changeItemPid: string;
  decision: BoundaryDecision;
  previousValue: unknown;
  savedValue: unknown;
}

export interface AuthoringStudioBatchPlan {
  creates: Array<{
    blockId: string;
    blockType: string;
    parentBlockId: string | null;
    beforeBlockId: string | null;
    manifestChecksum: string;
  }>;
  relocations: Array<{
    blockId: string;
    targetParentBlockId: string;
    beforeBlockId: string | null;
    manifestChecksum: string;
  }>;
  removes: Array<{ blockId: string; manifestChecksum: string }>;
  moves: Array<{
    blockId: string;
    beforeBlockId: string | null;
    manifestChecksum: string;
  }>;
  patches: Array<{
    blockId: string;
    propertyPath: string;
    operation: PatchOperation;
    value: unknown;
    manifestChecksum: string;
  }>;
}

export interface AuthoringStudioBatchResult {
  session: AuthoringSession;
  changeItemPids: string[];
}

export interface PendingAuthoringEdit {
  key: string;
  baseRevision: number;
  blockId: string;
  blockLabel: string;
  manifestChecksum: string;
  property: PropertyCapability;
  operation: PatchOperation;
  previousValue: unknown;
  value: unknown;
}

export type AuthoringGovernanceAction = 'withdraw' | 'reopen' | 'approve' | 'reject' | 'publish';

export interface ContextualAuthoringSurfaceProps {
  schema: UnifiedSchema;
  recordPid?: string;
  children: React.ReactNode;
  renderRuntime?: (schema: UnifiedSchema) => React.ReactNode;
}
