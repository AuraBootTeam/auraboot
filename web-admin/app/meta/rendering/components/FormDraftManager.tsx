/**
 * FormDraftManager — auto-save form drafts to localStorage
 *
 * Saves form data every 30 seconds (configurable).
 * Shows recovery prompt when a draft exists.
 * Clears draft on successful submit.
 */

import React, { useEffect, useRef, useCallback, useState } from 'react';

interface FormDraftManagerProps {
  /** Unique key for this form (e.g., "create:pe-order" or "edit:pe-order:123") */
  draftKey: string;
  /** Current form data */
  formData: Record<string, any>;
  /** Called when user chooses to restore a draft */
  onRestore: (data: Record<string, any>) => void;
  /** Auto-save interval in ms (default: 30000) */
  interval?: number;
  /** Whether the form has been submitted (clears draft) */
  submitted?: boolean;
  children?: React.ReactNode;
}

const DRAFT_PREFIX = 'aura_form_draft_';

function getDraftKey(key: string): string {
  return `${DRAFT_PREFIX}${key}`;
}

export const FormDraftManager: React.FC<FormDraftManagerProps> = ({
  draftKey,
  formData,
  onRestore,
  interval = 30000,
  submitted = false,
  children,
}) => {
  const [showRecovery, setShowRecovery] = useState(false);
  const [savedDraft, setSavedDraft] = useState<Record<string, any> | null>(null);
  const formDataRef = useRef(formData);
  formDataRef.current = formData;

  // Check for existing draft on mount
  useEffect(() => {
    try {
      const stored = localStorage.getItem(getDraftKey(draftKey));
      if (stored) {
        const parsed = JSON.parse(stored);
        if (parsed && typeof parsed === 'object' && parsed._timestamp) {
          const age = Date.now() - parsed._timestamp;
          // Only show recovery if draft is less than 24 hours old
          if (age < 24 * 60 * 60 * 1000) {
            setSavedDraft(parsed);
            setShowRecovery(true);
          } else {
            localStorage.removeItem(getDraftKey(draftKey));
          }
        }
      }
    } catch {
      // Ignore parse errors
    }
  }, [draftKey]);

  // Auto-save interval
  useEffect(() => {
    const timer = setInterval(() => {
      const data = formDataRef.current;
      if (data && Object.keys(data).length > 0) {
        try {
          localStorage.setItem(
            getDraftKey(draftKey),
            JSON.stringify({ ...data, _timestamp: Date.now() }),
          );
        } catch {
          // Storage quota exceeded or other error
        }
      }
    }, interval);

    return () => clearInterval(timer);
  }, [draftKey, interval]);

  // Clear draft on submit
  useEffect(() => {
    if (submitted) {
      localStorage.removeItem(getDraftKey(draftKey));
    }
  }, [submitted, draftKey]);

  const handleRestore = useCallback(() => {
    if (savedDraft) {
      const { _timestamp, ...data } = savedDraft;
      onRestore(data);
    }
    setShowRecovery(false);
  }, [savedDraft, onRestore]);

  const handleDiscard = useCallback(() => {
    localStorage.removeItem(getDraftKey(draftKey));
    setShowRecovery(false);
    setSavedDraft(null);
  }, [draftKey]);

  return (
    <>
      {showRecovery && savedDraft && (
        <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 p-4">
          <div className="flex items-start gap-3">
            <div className="mt-0.5 flex-shrink-0 text-amber-500">
              <svg className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                <path
                  fillRule="evenodd"
                  d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 5a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 5zm0 9a1 1 0 100-2 1 1 0 000 2z"
                  clipRule="evenodd"
                />
              </svg>
            </div>
            <div className="flex-1">
              <p className="text-sm font-medium text-amber-800">Unsaved draft found</p>
              <p className="mt-1 text-sm text-amber-700">
                Last saved: {new Date(savedDraft._timestamp).toLocaleString()}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={handleRestore}
                className="rounded-md bg-amber-100 px-3 py-1.5 text-sm font-medium text-amber-800 transition-colors hover:bg-amber-200"
              >
                Restore
              </button>
              <button
                onClick={handleDiscard}
                className="px-3 py-1.5 text-sm text-amber-600 transition-colors hover:text-amber-800"
              >
                Discard
              </button>
            </div>
          </div>
        </div>
      )}
      {children}
    </>
  );
};

/** Utility to clear a specific draft */
export function clearFormDraft(draftKey: string): void {
  localStorage.removeItem(`${DRAFT_PREFIX}${draftKey}`);
}

/** Utility to clear all form drafts */
export function clearAllFormDrafts(): void {
  const keys = Object.keys(localStorage);
  for (const key of keys) {
    if (key.startsWith(DRAFT_PREFIX)) {
      localStorage.removeItem(key);
    }
  }
}
