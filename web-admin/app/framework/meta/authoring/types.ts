import type React from 'react';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';

export type AuthoringMode = 'select' | 'interact';
export type AuthoringNodeKind = 'page' | 'block' | 'field' | 'action';

export interface AuthoringSession {
  sessionPid: string;
  changeSetPid: string;
  pagePid: string;
  state: string;
  revision: number;
  riskLevel: string;
  route: string;
  publishPolicy: string;
  validationState: string;
  approvalState: string;
  publishState: string;
  manifestChecksum: string;
  snapshot: Record<string, unknown>;
  interactionContext: Record<string, unknown>;
  expiresAt: string;
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

export interface ContextualAuthoringSurfaceProps {
  schema: UnifiedSchema;
  recordPid?: string;
  children: React.ReactNode;
}
