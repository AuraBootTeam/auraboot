import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import TEXT from './InstalledTab.i18n.json';

/** Business summaries remain visible; original diagnostics are retained on demand. */
export default function ImportWarnings({ warnings }: { warnings: string[] }) {
  const { locale } = useI18n();
  const text = (key: keyof typeof TEXT) => getLocalizedText(TEXT[key], locale);
  const kinds = warnings.map(warning => /^Preserved (AUTOMATION|DECISION|CONDITION|EVENT_POLICY):/.exec(warning)?.[1] || (/^The following \d+ resource\(s\) have customer changes/.test(warning) ? 'changedResources' : undefined));
  return <div className="mt-3 space-y-2 text-sm text-amber-900">
    {warnings.map((warning, index) => <p key={index} className="rounded-lg border border-amber-200 bg-white px-3 py-2">
      {kinds[index] ? `${text(kinds[index] as keyof typeof TEXT)}：${text('preserveReason')}` : warning}
    </p>)}
    {kinds.some(Boolean) && <details className="pt-1">
      <summary className="cursor-pointer text-xs text-gray-600">{text('diagnostics')}</summary>
      <ul className="mt-2 space-y-2 break-words text-xs text-gray-600">{warnings.filter((_, index) => kinds[index]).map((warning, index) => <li key={index}>{warning}</li>)}</ul>
    </details>}
  </div>;
}
