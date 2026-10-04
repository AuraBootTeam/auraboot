import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '../designerI18n';
import type { SemanticMetaFailure } from './useMetaModels';

/** Safe business feedback shared by metric and dimension selectors. */
export function SemanticMetaFeedback({ error, onRetry }: {
  error: SemanticMetaFailure;
  onRetry: () => void;
}) {
  const { locale } = useI18n();
  return (
    <div role="alert" className="space-y-2">
      <p className="text-sm text-red-600">{resolveDesignerText(DESIGNER_I18N.semanticMeta[error.kind], locale)}</p>
      {error.kind === 'failed' && (
        <button type="button" onClick={onRetry}
          className="rounded px-2 py-1 text-sm text-blue-600 hover:bg-blue-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-blue-600">
          {resolveDesignerText(DESIGNER_I18N.semanticMeta.retry, locale)}
        </button>
      )}
    </div>
  );
}
