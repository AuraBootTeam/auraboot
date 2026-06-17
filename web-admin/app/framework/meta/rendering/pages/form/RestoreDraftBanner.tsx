/**
 * RestoreDraftBanner — token-styled, i18n'd prompt offering to restore an
 * autosaved form draft (T10). Rendered by FormPageContent when {@link useFormDraft}
 * detects a non-expired draft that differs from the loaded/initial values.
 *
 * Visual language follows the UX design system (ux-design-system.md §1):
 * `bg-accent-weak` surface, `border-border`, `text-text-2` body, `bg-accent`
 * primary action — no hardcoded colors. Strings go through `t()` with English
 * fallbacks so the gate (G2) stays green.
 */
/** Matches the I18nContext `t` signature: (key, params?, fallback?) => string. */
type TranslateFn = (key: string, params?: Record<string, any>, fallback?: string) => string;

interface RestoreDraftBannerProps {
  /** Epoch millis the draft was saved at (for the relative/absolute time hint). */
  savedAt: number;
  /** Active locale, used to format the saved-at timestamp. */
  locale: string;
  /** Translate function (key, params, fallback). */
  t: TranslateFn;
  /** Apply the draft values. */
  onRestore: () => void;
  /** Drop the draft. */
  onDiscard: () => void;
}

function formatSavedAt(savedAt: number, locale: string): string {
  try {
    return new Date(savedAt).toLocaleString(locale || undefined);
  } catch {
    return new Date(savedAt).toLocaleString();
  }
}

export function RestoreDraftBanner({
  savedAt,
  locale,
  t,
  onRestore,
  onDiscard,
}: RestoreDraftBannerProps) {
  const savedAtLabel = formatSavedAt(savedAt, locale);
  const message = t(
    'form.draft.restorePrompt',
    { time: savedAtLabel },
    `You have unsaved changes from ${savedAtLabel}.`,
  );

  return (
    <div
      className="rounded-control bg-accent-weak border-border mx-6 mt-4 flex items-start justify-between gap-3 border p-4"
      data-testid="form-restore-draft-banner"
    >
      <div className="flex-1">
        <p className="text-text text-sm font-medium">
          {t('form.draft.title', undefined, 'Unsaved draft found')}
        </p>
        <p className="text-text-2 mt-1 text-sm">{message}</p>
      </div>
      <div className="flex flex-shrink-0 items-center gap-2">
        <button
          type="button"
          data-testid="form-restore-draft-restore"
          onClick={onRestore}
          className="rounded-control bg-accent hover:bg-accent-hover px-3 py-1.5 text-sm font-medium text-white"
        >
          {t('form.draft.restore', undefined, 'Restore')}
        </button>
        <button
          type="button"
          data-testid="form-restore-draft-discard"
          onClick={onDiscard}
          className="rounded-control border-border-strong text-text-2 hover:bg-hover border px-3 py-1.5 text-sm"
        >
          {t('form.draft.discard', undefined, 'Discard')}
        </button>
      </div>
    </div>
  );
}
