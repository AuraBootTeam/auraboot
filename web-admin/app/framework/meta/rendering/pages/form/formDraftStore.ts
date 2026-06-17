/**
 * formDraftStore — pure, storage-agnostic local draft persistence for DSL forms.
 *
 * T10 (UX design system): autosave the form's unsaved values as a local DRAFT so
 * an accidental reload / navigation does not lose in-progress work, with
 * restore-on-reopen and clear-on-submit.
 *
 * This module is intentionally free of React and `window` — the `Storage` is
 * injected so unit tests use a fake and the host (SSR) can pass `null` when no
 * `localStorage` is available. All operations swallow Storage exceptions
 * (quota / private mode / disabled cookies) and degrade to a no-op; a missing,
 * expired or corrupt draft always resolves to `null` (with proactive cleanup).
 */

/** Namespace prefix for every draft key written to Storage. */
export const DRAFT_KEY_PREFIX = 'aura_form_draft';

/** Default time-to-live for a draft (24h). Older drafts are ignored + cleared. */
export const DEFAULT_DRAFT_MAX_AGE_MS = 24 * 60 * 60 * 1000;

/** Persisted draft envelope. */
export interface FormDraft {
  /** Epoch millis the draft was saved at. */
  savedAt: number;
  /** The form values captured at `savedAt`. */
  values: Record<string, unknown>;
}

export interface LoadDraftOptions {
  /** Current time (epoch millis). Injectable for deterministic tests. */
  now: number;
  /** Max draft age before it is treated as expired. Defaults to 24h. */
  maxAgeMs?: number;
}

/**
 * Compose a stable, namespaced Storage key for a form draft.
 *
 * Create forms (no `recordId`) share a single `:new` slot per model+page so a
 * reload mid-create restores. Edit forms are scoped per `recordId` so editing
 * two records does not cross-contaminate.
 */
export function draftKey(
  modelCode: string,
  pageKey?: string | null,
  recordId?: string | null,
): string {
  const safeModel = modelCode || '_';
  const safePage = pageKey || '_';
  const safeRecord = recordId || 'new';
  return `${DRAFT_KEY_PREFIX}:${safeModel}:${safePage}:${safeRecord}`;
}

/**
 * True when at least one field carries a value worth persisting. Guards against
 * saving an all-empty form (which would otherwise pop a useless restore prompt).
 * `0` and `false` count as meaningful; `''`, `null`, `undefined` and `[]` do not.
 */
export function hasMeaningfulValues(values: Record<string, unknown> | null | undefined): boolean {
  if (!values || typeof values !== 'object') return false;
  for (const value of Object.values(values)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value) && value.length === 0) continue;
    return true;
  }
  return false;
}

/**
 * Persist `values` as a draft under `key`. No-op (returns `false`) when the
 * Storage is absent, the values are not meaningful, or Storage throws (quota).
 * Returns `true` only when a draft was actually written.
 */
export function saveDraft(
  storage: Storage | null | undefined,
  key: string,
  values: Record<string, unknown>,
  savedAt: number,
): boolean {
  if (!storage) return false;
  if (!hasMeaningfulValues(values)) return false;
  const draft: FormDraft = { savedAt, values };
  try {
    storage.setItem(key, JSON.stringify(draft));
    return true;
  } catch {
    // Storage quota exceeded / private mode / disabled — degrade silently.
    return false;
  }
}

/**
 * Load a non-expired, well-formed draft. Returns `null` for a missing, expired
 * or corrupt draft and proactively removes the offending key so the form does
 * not keep prompting. Never throws.
 */
export function loadDraft(
  storage: Storage | null | undefined,
  key: string,
  options: LoadDraftOptions,
): FormDraft | null {
  if (!storage) return null;
  const maxAgeMs = options.maxAgeMs ?? DEFAULT_DRAFT_MAX_AGE_MS;

  let raw: string | null;
  try {
    raw = storage.getItem(key);
  } catch {
    return null;
  }
  if (raw == null) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    clearDraft(storage, key);
    return null;
  }

  if (
    !parsed ||
    typeof parsed !== 'object' ||
    Array.isArray(parsed) ||
    typeof (parsed as FormDraft).savedAt !== 'number' ||
    typeof (parsed as FormDraft).values !== 'object' ||
    (parsed as FormDraft).values === null ||
    Array.isArray((parsed as FormDraft).values)
  ) {
    clearDraft(storage, key);
    return null;
  }

  const draft = parsed as FormDraft;
  if (options.now - draft.savedAt > maxAgeMs) {
    clearDraft(storage, key);
    return null;
  }

  return draft;
}

/** Remove a draft. Never throws (Storage may be absent or locked). */
export function clearDraft(storage: Storage | null | undefined, key: string): void {
  if (!storage) return;
  try {
    storage.removeItem(key);
  } catch {
    // ignore
  }
}
