/**
 * useFormDraft — React glue over the pure {@link formDraftStore}.
 *
 * T10 (UX design system): wires debounced autosave of a DSL form's unsaved
 * values into a local draft, detects a restorable draft on (re)mount, and
 * exposes restore / discard / clear actions for the host renderer.
 *
 * SSR-safe: resolves `window.localStorage` lazily and tolerates its absence
 * (matches `CommandPalette.getRecentStorage`). The `storage` prop is injectable
 * so unit tests pass a fake.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  DEFAULT_DRAFT_MAX_AGE_MS,
  type FormDraft,
  clearDraft as clearDraftRaw,
  draftKey,
  loadDraft,
  saveDraft,
} from './formDraftStore';

/** Resolve `window.localStorage` only in the browser; `null` on the server. */
export function getDraftStorage(): Storage | null {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') {
    return null;
  }
  try {
    return window.localStorage;
  } catch {
    // Accessing localStorage can throw in sandboxed iframes / disabled cookies.
    return null;
  }
}

export interface UseFormDraftParams {
  /** Master switch — when false the hook is fully inert (no detect / save). */
  enabled: boolean;
  /** Model code identifying the form's model. */
  modelCode: string;
  /** Page key identifying the specific form page. */
  pageKey?: string | null;
  /** Edit-mode record id; omitted/empty for create forms. */
  recordPid?: string | null;
  /** Current live form values (autosaved, debounced). */
  values: Record<string, unknown>;
  /** Baseline values to diff against when deciding whether to offer restore. */
  initialValues?: Record<string, unknown>;
  /** Debounce window before a save (ms). Default 500. */
  debounceMs?: number;
  /** Draft TTL (ms). Default 24h. */
  maxAgeMs?: number;
  /** Injectable Storage for tests; defaults to `window.localStorage`. */
  storage?: Storage | null;
}

export interface UseFormDraftResult {
  /** A restorable draft (newer-than-TTL and differing from current), or null. */
  restorable: FormDraft | null;
  /** Apply the draft: returns its values and dismisses the prompt. */
  restore: () => Record<string, unknown> | null;
  /** Drop the draft from storage and dismiss the prompt. */
  discard: () => void;
  /** Remove the persisted draft (call on successful submit / explicit cancel). */
  clearDraft: () => void;
}

function stableStringify(value: Record<string, unknown>): string {
  // Order-insensitive comparison so key reordering doesn't look like a change.
  const sorted = Object.keys(value)
    .filter((k) => {
      const v = value[k];
      if (v === undefined || v === null || v === '') return false;
      if (Array.isArray(v) && v.length === 0) return false;
      return true;
    })
    .sort();
  return JSON.stringify(sorted.map((k) => [k, value[k]]));
}

export function useFormDraft(params: UseFormDraftParams): UseFormDraftResult {
  const {
    enabled,
    modelCode,
    pageKey,
    recordPid,
    values,
    initialValues,
    debounceMs = 500,
    maxAgeMs = DEFAULT_DRAFT_MAX_AGE_MS,
  } = params;

  const storage = params.storage !== undefined ? params.storage : getDraftStorage();
  const key = useMemo(() => draftKey(modelCode, pageKey, recordPid), [modelCode, pageKey, recordPid]);

  const [restorable, setRestorable] = useState<FormDraft | null>(null);

  // Latest values without retriggering the debounce effect's identity each render.
  const valuesRef = useRef(values);
  valuesRef.current = values;
  const storageRef = useRef(storage);
  storageRef.current = storage;
  const maxAgeRef = useRef(maxAgeMs);
  maxAgeRef.current = maxAgeMs;

  // --- Mount/identity-change detection of a restorable draft -------------------
  // Re-runs when the form identity (key) or enablement changes.
  const initialSignature = useMemo(() => stableStringify(initialValues ?? {}), [initialValues]);
  useEffect(() => {
    if (!enabled) {
      setRestorable(null);
      return;
    }
    const found = loadDraft(storageRef.current, key, {
      now: Date.now(),
      maxAgeMs: maxAgeRef.current,
    });
    if (!found) {
      setRestorable(null);
      return;
    }
    // Only offer to restore when the draft actually differs from the baseline.
    if (stableStringify(found.values) === initialSignature) {
      setRestorable(null);
      return;
    }
    setRestorable(found);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, key, initialSignature]);

  // --- Debounced autosave ------------------------------------------------------
  const valuesSignature = useMemo(() => stableStringify(values), [values]);
  useEffect(() => {
    if (!enabled) return;
    if (typeof window === 'undefined') return;
    const handle = window.setTimeout(() => {
      saveDraft(storageRef.current, key, valuesRef.current, Date.now());
    }, debounceMs);
    return () => window.clearTimeout(handle);
    // valuesSignature drives the debounce; valuesRef supplies fresh data at flush.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, key, debounceMs, valuesSignature]);

  const restore = useCallback((): Record<string, unknown> | null => {
    const draft = restorable;
    setRestorable(null);
    return draft ? draft.values : null;
  }, [restorable]);

  const discard = useCallback(() => {
    clearDraftRaw(storageRef.current, key);
    setRestorable(null);
  }, [key]);

  const clearDraft = useCallback(() => {
    clearDraftRaw(storageRef.current, key);
    setRestorable(null);
  }, [key]);

  return { restorable, restore, discard, clearDraft };
}
