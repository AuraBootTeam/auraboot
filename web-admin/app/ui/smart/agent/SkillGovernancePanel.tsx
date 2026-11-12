import { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import MESSAGES from './SkillGovernancePanel.i18n.json';
import { getLocalizedText } from '~/utils/i18n';
import { get, post } from '~/shared/services/http-client';

type Version = {
  pid: string;
  version: string;
  operation: string;
  operationAt?: string;
  operationByDisplayName?: string;
  schemaSnapshot?: Record<string, unknown>;
};
export type TrialDefinition = {
  available?: boolean;
  definitionHash?: string;
  inputSchema?: unknown;
  executionMode?: string;
};
type Governance = {
  draftHash: string;
  managed: boolean;
  readOnly: boolean;
  legacyStatus: string;
  publication: { versionPid?: string; hash?: string; version?: string };
  validationErrors: string[];
  changes: Record<string, { published: unknown; draft: unknown }>;
  versions: Version[];
  trials: Version[];
  trialDefinition: TrialDefinition;
};
const BUTTON =
  'rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text hover:bg-hover disabled:opacity-50';

/** Publication decisions are explicit and compare-and-swap protected; editing never activates a draft. */
export function SkillGovernancePanel({
  pid,
  dirty,
  canPublish,
  revision,
  onManaged,
  onPublished,
}: {
  pid: string;
  dirty: boolean;
  canPublish: boolean;
  revision: number;
  onManaged: (managed: boolean) => void;
  onPublished: () => void;
}) {
  const { locale } = useI18n();
  const text = (key: keyof typeof MESSAGES, params: Record<string, unknown> = {}) => {
    let value = getLocalizedText(MESSAGES[key], locale);
    for (const [name, item] of Object.entries(params))
      value = value.split(`{${name}}`).join(String(item));
    return value;
  };
  const [view, setView] = useState<Governance>();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [nonce, setNonce] = useState(0);
  const [selected, setSelected] = useState<Version>();
  const [reviewed, setReviewed] = useState(false);
  const [notice, setNotice] = useState('');
  useEffect(() => {
    let active = true;
    setView(undefined);
    setSelected(undefined);
    setReviewed(false);
    setError('');
    void get<Governance>(`/api/agent/skill-definitions/${encodeURIComponent(pid)}/governance`)
      .then((response) => {
        if (!active) return;
        if (String(response.code) !== '0' || !response.data?.draftHash)
          throw new Error(response.message || text('governanceUnavailable'));
        setView(response.data);
        onManaged(response.data.managed);
      })
      .catch((cause) => {
        if (active) setError(cause instanceof Error ? cause.message : text('loadFailedRetry'));
      });
    return () => {
      active = false;
    };
  }, [pid, revision, nonce]);
  useEffect(() => {
    setReviewed(false);
    setSelected(undefined);
  }, [dirty]);
  const inspect = async (version: Version) => {
    setBusy(true);
    setError('');
    setReviewed(false);
    try {
      const response = await get<Version>(
        `/api/agent/skill-definitions/${encodeURIComponent(pid)}/versions/${encodeURIComponent(version.pid)}`,
      );
      if (String(response.code) !== '0' || !response.data) throw new Error(response.message);
      setSelected(response.data);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : text('versionReadFailed'));
    } finally {
      setBusy(false);
    }
  };
  const decide = async (version?: Version) => {
    if (!view || dirty || !reviewed || !canPublish) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const path = version ? `/versions/${encodeURIComponent(version.pid)}/activate` : '/publish';
      const response = await post<Governance>(
        `/api/agent/skill-definitions/${encodeURIComponent(pid)}${path}`,
        {
          expectedDraftHash: view.draftHash,
          expectedPublishedVersion: view.publication.versionPid ?? '',
          description: version
            ? `Restore version ${version.version}`
            : 'Publish reviewed definition',
        },
      );
      if (String(response.code) !== '0' || !response.data) throw new Error(response.message);
      setView(response.data);
      onManaged(response.data.managed);
      setReviewed(false);
      setSelected(undefined);
      onPublished();
      setNotice(text('activeVersionChangedUnpublishedEditsAreRetained'));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : text('decisionFailedReloadAndReview'));
    } finally {
      setBusy(false);
    }
  };
  const value = (field: string, item: unknown) =>
    ['execution_config', 'guardrails'].includes(field)
      ? text('configurationChangedPrivateValuesHidden')
      : item == null
        ? text('unset')
        : typeof item === 'string'
          ? item
          : JSON.stringify(item, null, 2);
  const blocked = busy || dirty || !canPublish || view?.readOnly || !view;
  return (
    <section
      className="rounded-card border-border bg-panel space-y-4 border p-5"
      data-testid="skill-governance"
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-base font-semibold">{text('versionsAndPublication')}</h2>
        <button
          type="button"
          className={BUTTON}
          disabled={busy}
          onClick={() => setNonce((n) => n + 1)}
        >
          {text('refreshGovernance')}
        </button>
      </div>
      {error && (
        <p role="alert" className="text-danger">
          {error}
        </p>
      )}
      {notice && <p role="status">{notice}</p>}
      {!view && !error && <p role="status">{text('checkingVersions')}</p>}
      {view && (
        <>
          <p className="text-text-2 text-sm">
            {view.managed
              ? text('activeVersionSavingEditsChangesTheDraft', {
                  value1: view.publication.version,
                })
              : text('legacyDefinitionFirstPublicationOptsIntoImmutable')}
          </p>
          <p className="text-text-2 text-sm">{text('publicationChecksTheEditorSCurrentTool')}</p>
          {dirty && <p role="status">{text('saveTheDraftThenRefreshAndReview')}</p>}
          {view.validationErrors.length > 0 && (
            <div role="alert">
              <p>{text('resolveBeforePublication')}</p>
              <ul className="list-disc pl-5">
                {view.validationErrors.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          )}
          <details>
            <summary className="cursor-pointer text-sm font-medium">
              {text('draftChanges', { value1: Object.keys(view.changes).length })}
            </summary>
            <div className="mt-3 max-h-96 space-y-3 overflow-y-auto pr-2">
              {Object.entries(view.changes).map(([field, change]) => (
                <div key={field} className="rounded-control border-border border p-3">
                  <h3 className="text-sm font-medium">
                    {text(('field_' + field) as keyof typeof MESSAGES)}
                  </h3>
                  <div className="mt-2 grid gap-3 md:grid-cols-2">
                    {(['published', 'draft'] as const).map((side) => (
                      <div key={side}>
                        <p className="text-text-2 text-xs">
                          {side === 'published' ? text('published') : text('draft')}
                        </p>
                        <pre className="text-xs break-words whitespace-pre-wrap">
                          {value(field, change[side])}
                        </pre>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </details>
          <h3 className="text-sm font-semibold">{text('publicationHistory')}</h3>
          {!view.versions.length ? (
            <p className="text-text-2 text-sm">{text('noPublishedVersionsYet')}</p>
          ) : (
            <ul className="space-y-2">
              {view.versions.map((version) => (
                <li
                  key={version.pid}
                  className="rounded-control border-border flex flex-wrap items-center justify-between gap-2 border p-3 text-sm"
                >
                  <span>
                    {text('version')} {version.version} ·{' '}
                    {version.operation === 'ROLLBACK' ? text('restored') : text('published')} ·{' '}
                    {version.operationByDisplayName ?? text('actorUnknown')} ·{' '}
                    {version.operationAt
                      ? new Date(version.operationAt).toLocaleString(locale)
                      : text('timeUnknown')}
                  </span>
                  <button
                    type="button"
                    className={BUTTON}
                    disabled={busy || dirty}
                    onClick={() => void inspect(version)}
                  >
                    {text('reviewThisVersion')}
                  </button>
                </li>
              ))}
            </ul>
          )}
          {selected && (
            <div
              className="rounded-control border-border space-y-2 border p-3"
              data-testid="skill-version-preview"
            >
              <h3 className="font-medium">
                {text('reviewRestorationOfVersion', { value1: selected.version })}
              </h3>
              <div className="max-h-72 space-y-2 overflow-y-auto pr-2">
                {Object.entries(selected.schemaSnapshot ?? {})
                  .sort(([left], [right]) => {
                    const order = [
                      'skill_name',
                      'skill_description',
                      'prompt_template',
                      'skill_tools',
                      'skill_input_schema',
                      'execution_mode',
                    ];
                    const rank = (field: string) =>
                      order.includes(field) ? order.indexOf(field) : 100;
                    return rank(left) - rank(right) || left.localeCompare(right);
                  })
                  .filter(
                    ([field, item]) =>
                      item != null && !['execution_config', 'guardrails'].includes(field),
                  )
                  .map(([field, item]) => (
                    <details key={field}>
                      <summary className="cursor-pointer text-sm">
                        {text(('field_' + field) as keyof typeof MESSAGES)}
                      </summary>
                      <pre className="text-xs break-words whitespace-pre-wrap">
                        {value(field, item)}
                      </pre>
                    </details>
                  ))}
              </div>
              <button
                type="button"
                className={BUTTON}
                onClick={() => {
                  setSelected(undefined);
                  setReviewed(false);
                }}
              >
                {text('returnToDraftPublication')}
              </button>
            </div>
          )}
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              checked={reviewed}
              disabled={!!blocked}
              onChange={(e) => setReviewed(e.target.checked)}
            />
            {selected
              ? text('iReviewedTheHistoricalDefinitionAndWill')
              : text('iReviewedTheDraftChangesAndValidation')}
          </label>
          <button
            type="button"
            className={BUTTON}
            disabled={!!blocked || !reviewed || (!selected && view.validationErrors.length > 0)}
            onClick={() => void decide(selected)}
          >
            {selected ? text('activateReviewedVersion') : text('publishReviewedDraft')}
          </button>
          <h3 className="text-sm font-semibold">{text('recentExecutionReceiptsUpTo')}</h3>
          {!view.trials.length ? (
            <p className="text-text-2 text-sm">{text('noRecordedExecutions')}</p>
          ) : (
            <ul className="space-y-2 text-sm">
              {view.trials.map((trial) => {
                const receipt = trial.schemaSnapshot ?? {};
                return (
                  <li key={trial.pid} className="rounded-control border-border border p-3">
                    <p>
                      {receipt.status === 'SUCCESS' ? text('succeeded') : text('incompleteFailed')}{' '}
                      ·{' '}
                      {receipt.versionLabel
                        ? text('version2', { value1: receipt.versionLabel })
                        : text('legacyDefinition')}{' '}
                      ·{' '}
                      {typeof receipt.durationMs === 'number'
                        ? `${receipt.durationMs} ms`
                        : text('durationUnknown')}
                    </p>
                    <details>
                      <summary className="cursor-pointer text-xs">
                        {text('executionIdentity')}
                      </summary>
                      <pre className="text-xs break-all whitespace-pre-wrap">
                        {JSON.stringify(receipt, null, 2)}
                      </pre>
                    </details>
                  </li>
                );
              })}
            </ul>
          )}
        </>
      )}
    </section>
  );
}
