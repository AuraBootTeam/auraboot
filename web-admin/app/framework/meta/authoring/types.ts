import type React from 'react';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';

export type AuthoringMode = 'select' | 'interact';
export type AuthoringNodeKind = 'page' | 'block' | 'field' | 'action';

export interface AuthoringSession {
  sessionPid: string;
  changeSetPid: string;
  pagePid: string;
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

export interface AuthoringReviewWorkspace {
  session: AuthoringSession;
  capabilities: CapabilityRegistry;
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

export interface PatchResult {
  session: AuthoringSession;
  changeItemPid: string;
  decision: BoundaryDecision;
  previousValue: unknown;
  savedValue: unknown;
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
