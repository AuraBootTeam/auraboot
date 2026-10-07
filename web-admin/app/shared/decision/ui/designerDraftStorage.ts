/**
 * Scoped sessionStorage persistence for the DecisionOps designers' UNSAVED
 * in-memory drafts (EP-14/EP-23 and the studio mapping rows).
 *
 * Both editors keep their unsaved draft models (rule names, condition ASTs,
 * action drafts, decision-binding mapping rows) in React state only. Switching
 * locale reloads the page (I18nContext.handleSetLocale calls
 * window.location.reload()), which remounts the editor and wipes that state.
 * This module gives each editor a session archive: the editor writes its draft
 * snapshot on every change, restores it on remount, and clears it once the
 * draft is successfully saved to the backend.
 *
 * Keys are scoped per editor context so drafts never cross-contaminate:
 * - event-policy designer: `decisionops.designer.draft.<policyCode>`
 * - strategy studio workbench: `decisionops.studio.draft.<scope>` (the archive
 *   holds one entry per scenario binding key, e.g. `SLA:wd_manager_approve_sla`)
 *
 * Session storage is deliberate: unsaved drafts should survive a reload within
 * the tab's working session but must not leak across browser sessions like a
 * real autosave.
 */

export const DESIGNER_DRAFT_KEY_PREFIX = 'decisionops.designer.draft.';

/**
 * JSON-safe snapshot of the designer draft (the `draftJson` shape emitted by
 * EventPolicyDesignerWorkflow). Fields are `unknown` on purpose: validation
 * happens on read so a stale or hand-mangled entry degrades to "no archive"
 * instead of corrupting the editor.
 */
export interface DesignerDraftSnapshot {
  phase?: unknown;
  matchMode?: unknown;
  executionMode?: unknown;
  failureStrategy?: unknown;
  conflictStrategy?: unknown;
  dedupStrategy?: unknown;
  rules?: unknown;
}

export function designerDraftKey(policyCode: string): string {
  return `${DESIGNER_DRAFT_KEY_PREFIX}${policyCode}`;
}

function isDesignerDraftSnapshot(value: unknown): value is DesignerDraftSnapshot {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  return Array.isArray((value as Record<string, unknown>).rules);
}

export function loadDesignerDraft(policyCode: string): DesignerDraftSnapshot | null {
  if (!policyCode || typeof window === 'undefined') return null;
  try {
    const raw = window.sessionStorage.getItem(designerDraftKey(policyCode));
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    return isDesignerDraftSnapshot(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export function saveDesignerDraft(policyCode: string, draft: DesignerDraftSnapshot): void {
  if (!policyCode || typeof window === 'undefined') return;
  try {
    window.sessionStorage.setItem(designerDraftKey(policyCode), JSON.stringify(draft));
  } catch {
    // Quota or serialization failures must never break editing.
  }
}

export function clearDesignerDraft(policyCode: string): void {
  if (!policyCode || typeof window === 'undefined') return;
  try {
    window.sessionStorage.removeItem(designerDraftKey(policyCode));
  } catch {
    // ignore
  }
}

export const STUDIO_DRAFT_KEY_PREFIX = 'decisionops.studio.draft.';

/**
 * The Strategy Studio workbench hosts every scenario orchestrator at once, so
 * its unsaved mapping-row drafts (`ruleBindingDrafts`, keyed by
 * `<scenarioKey>:<fragmentCode|ruleCode>`) share one workbench-scoped archive
 * instead of a per-policy key.
 */
export const STUDIO_WORKBENCH_DRAFT_SCOPE = 'workbench';

export function studioDraftKey(scope: string): string {
  return `${STUDIO_DRAFT_KEY_PREFIX}${scope}`;
}

/**
 * JSON-safe snapshot of the studio workbench draft: a record of unsaved
 * scenario binding drafts. Fields are `unknown` on purpose: validation happens
 * on read so a stale or hand-mangled entry degrades to "no archive" instead of
 * corrupting the orchestrator.
 */
export interface StudioDraftSnapshot {
  bindings?: unknown;
}

function isStudioDraftSnapshot(value: unknown): value is StudioDraftSnapshot {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const bindings = (value as Record<string, unknown>).bindings;
  return typeof bindings === 'object' && bindings !== null && !Array.isArray(bindings);
}

export function loadStudioDraft(scope: string): StudioDraftSnapshot | null {
  if (!scope || typeof window === 'undefined') return null;
  try {
    const raw = window.sessionStorage.getItem(studioDraftKey(scope));
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    return isStudioDraftSnapshot(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export function saveStudioDraft(scope: string, draft: StudioDraftSnapshot): void {
  if (!scope || typeof window === 'undefined') return;
  try {
    window.sessionStorage.setItem(studioDraftKey(scope), JSON.stringify(draft));
  } catch {
    // Quota or serialization failures must never break editing.
  }
}

export function clearStudioDraft(scope: string): void {
  if (!scope || typeof window === 'undefined') return;
  try {
    window.sessionStorage.removeItem(studioDraftKey(scope));
  } catch {
    // ignore
  }
}
