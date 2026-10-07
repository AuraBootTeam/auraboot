/**
 * Scoped sessionStorage persistence for the DecisionOps event-policy designer's
 * UNSAVED in-memory draft (EP-14/EP-23).
 *
 * The designer keeps the whole draft model (rule names, condition AST, action
 * drafts, decision-binding mapping rows) in React state only. Switching locale
 * reloads the page (I18nContext.handleSetLocale calls window.location.reload()),
 * which remounts the workflow and wipes that state. This module gives the
 * workflow a per-policy session archive: the workflow writes its draft snapshot
 * on every change, restores it on remount, and clears it once the draft is
 * successfully saved to the backend.
 *
 * Keys are scoped per policy code (`decisionops.designer.draft.<policyCode>`),
 * so drafts for different policies never cross-contaminate. Session storage is
 * deliberate: unsaved drafts should survive a reload within the tab's working
 * session but must not leak across browser sessions like a real autosave.
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
