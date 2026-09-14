import type { FormFillField, FormFillResult } from '../../formFill';

export function FormFillReceipt({
  receipt,
  fields,
  t,
  onUndo,
}: {
  receipt: FormFillResult;
  fields: FormFillField[];
  onUndo: () => void;
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;
}) {
  const labels = new Map(fields.map((field) => [field.code, field.label]));
  const count = Object.keys(receipt.applied).length;
  const reviews = Object.entries(receipt.reviews ?? {});
  const hasAmbiguity = reviews.some(([, review]) => review.status === 'ambiguous');
  return (
    <div
      role="status"
      data-testid="form-ai-fill-receipt"
      className="rounded-control border-border bg-accent-weak mb-4 space-y-2 border p-4 text-sm"
    >
      <p className="text-text font-medium">
        {count === 0 && hasAmbiguity
          ? t('ai.fill.review_pending')
          : t('ai.fill.review').replace('{count}', String(count))}
      </p>
      {count > 0 && (
        <p className="text-text-2">
          {Object.keys(receipt.applied)
            .map((code) => labels.get(code))
            .join('、')}
        </p>
      )}
      {receipt.conflicts.length > 0 && (
        <p className="text-text-2" data-testid="form-ai-fill-conflicts">
          {t('ai.fill.conflicts')}:
          {receipt.conflicts.map((code) => (
            <span key={code} className="block break-words">
              {labels.get(code)}：{String(receipt.suggestions[code])}
            </span>
          ))}
        </p>
      )}
      {reviews.length > 0 && (
        <details
          className="border-border rounded-control bg-surface border p-3"
          open={hasAmbiguity}
        >
          <summary className="text-text cursor-pointer font-medium">
            {t('ai.fill.evidence_details')}
          </summary>
          <ul className="mt-3 space-y-3" data-testid="form-ai-fill-evidence">
            {reviews.map(([code, review]) => (
              <li
                key={code}
                data-testid={`form-ai-review-${code}`}
                className="space-y-1 break-words"
              >
                <p className="text-text font-medium">
                  {labels.get(code)} ·{' '}
                  {t(review.status === 'ambiguous' ? 'ai.fill.ambiguous' : 'ai.fill.supported')}
                </p>
                {review.status === 'supported' && (
                  <p className="text-text-2">
                    {t('ai.fill.candidate')}：
                    {String(
                      receipt.suggestions[code] ??
                        receipt.applied[code]?.after ??
                        receipt.values[code],
                    )}
                  </p>
                )}
                {review.status === 'ambiguous' && review.reason && (
                  <p className="text-text-2">{t(`ai.fill.reason.${review.reason}`)}</p>
                )}
                <blockquote className="border-border-strong text-text-2 border-l-2 pl-3 whitespace-pre-wrap">
                  {review.quote}
                </blockquote>
              </li>
            ))}
          </ul>
          <p className="text-text-2 mt-3 text-xs">{t('ai.fill.evidence_hint')}</p>
        </details>
      )}
      {receipt.rejected.length > 0 && (
        <p className="text-text-2">
          {t('ai.fill.rejected').replace('{count}', String(receipt.rejected.length))}
        </p>
      )}
      {count > 0 && (
        <button
          type="button"
          data-testid="form-ai-fill-undo"
          onClick={onUndo}
          className="rounded-control border-border-strong hover:bg-hover border px-3 py-1.5"
        >
          {t('ai.fill.undo')}
        </button>
      )}
    </div>
  );
}
