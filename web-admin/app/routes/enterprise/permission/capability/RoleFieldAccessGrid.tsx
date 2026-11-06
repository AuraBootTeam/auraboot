import { useEffect, useState, type ReactNode } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import {
  authorizationDraftService,
  type AuthorizationDraft,
  type AuthorizationSnapshot,
} from './authorizationDraftService';

export type FieldAccessChange = NonNullable<AuthorizationDraft['fieldChanges']>[number];
type Model = NonNullable<AuthorizationSnapshot['fieldAccess']>[number];
interface Props {
  rolePid: string;
  catalog: Model[] | undefined;
  changes: FieldAccessChange[];
  models: Set<string>;
  grantedCodes: Set<string>;
  disabled: boolean;
  onChange: (changes: FieldAccessChange[]) => void;
  renderRecordScope?: (model: string, label: string, permissions: string[]) => ReactNode;
}

/** Field controls restrict existing business access; they never grant a capability or CRUD action. */
export default function RoleFieldAccessGrid({
  rolePid,
  catalog,
  changes,
  models,
  grantedCodes,
  disabled,
  onChange,
  renderRecordScope,
}: Props) {
  const { t } = useI18n();
  const [modelCode, setModelCode] = useState(''),
    [query, setQuery] = useState(''),
    [roots, setRoots] = useState<Array<{ model: string; permissions: string[] }>>([]),
    [error, setError] = useState(false),
    [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (catalog === undefined) return;
    let active = true;
    setError(false);
    setRoots([]);
    authorizationDraftService
      .fieldPolicyRoots(rolePid)
      .then((result) => {
        if (active) setRoots(result);
      })
      .catch(() => {
        if (active) setError(true);
      });
    return () => {
      active = false;
    };
  }, [rolePid, catalog !== undefined, attempt]);
  const available = new Set(models);
  roots
    .filter(
      (root) => grantedCodes.has('*') || root.permissions.some((code) => grantedCodes.has(code)),
    )
    .forEach((root) => available.add(root.model));
  const options = [...(catalog ?? [])].sort(
    (a, b) =>
      Number(available.has(b.code)) - Number(available.has(a.code)) ||
      (a.label ?? '').localeCompare(b.label ?? ''),
  );
  const current = options.find((model) => model.code === modelCode) ?? options[0];
  function modelLabel(model: Model) {
    return t(
      `model.${model.code}._meta.label`,
      undefined,
      model.label && model.label !== model.code
        ? model.label
        : t('roleFields.unnamedModel', undefined, 'Business object definition unavailable'),
    );
  }
  function fieldLabel(field: Model['fields'][number]) {
    return t(
      `model.${current!.code}.${field.code}.label`,
      undefined,
      field.label && field.label !== field.code
        ? field.label
        : t('roleFields.unnamedField', undefined, 'Field definition unavailable'),
    );
  }
  const accessLabel = (value: string) =>
    t(
      `roleFields.${value}`,
      undefined,
      (
        { inherit: 'Use business access', read_only: 'Read only', hidden: 'Hidden' } as Record<
          string,
          string
        >
      )[value] ?? '',
    );
  return (
    <section
      data-testid="role-field-access-grid"
      className="border-border rounded-card space-y-4 border p-4"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-text-1 text-sm font-medium">
            {t('rbacExperience.objectAccess', undefined, 'Business object access')}
          </h3>
          <p className="text-text-2 mt-1 text-xs">
            {t(
              'roleFields.help',
              undefined,
              'Choose a business object and restrict its fields. Business access, record scopes and shared policies still apply.',
            )}
          </p>
        </div>
      </div>
      {catalog === undefined ? (
        <p role="alert" className="text-text-2 text-sm">
          {t(
            'roleFields.unavailable',
            undefined,
            'Field definitions are unavailable. Existing restrictions will be preserved.',
          )}
        </p>
      ) : !options.length ? (
        <p className="text-text-2 text-sm">
          {t('roleFields.empty', undefined, 'No field definitions are available for this role.')}
        </p>
      ) : (
        <>
          <div className="flex flex-wrap gap-3">
            <select
              data-testid="role-field-model"
              aria-label={t('roleFields.model', undefined, 'Business object')}
              value={current?.code ?? ''}
              onChange={(event) => {
                setModelCode(event.target.value);
                setQuery('');
              }}
              className="border-border bg-panel rounded-control min-w-48 border px-3 py-2 text-sm"
            >
              {options.map((model) => (
                <option key={model.code} value={model.code}>
                  {modelLabel(model)}
                </option>
              ))}
            </select>
            <input
              data-testid="role-field-search"
              aria-label={t('roleFields.search', undefined, 'Search fields')}
              placeholder={t('roleFields.search', undefined, 'Search fields')}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              className="border-border bg-panel rounded-control min-w-0 flex-1 border px-3 py-2 text-sm"
            />
          </div>
          {current &&
            renderRecordScope?.(
              current.code,
              modelLabel(current),
              roots
                .filter((root) => root.model === current.code)
                .flatMap((root) => root.permissions),
            )}
          <h3 className="text-text-1 text-sm font-semibold">
            {current && modelLabel(current)} · {t('roleFields.title', undefined, 'Field access')}
          </h3>
          {error && (
            <div role="alert" className="text-text-2 text-xs">
              {t(
                'roleFields.authorityError',
                undefined,
                'Could not verify this role’s business resources. Existing restrictions are preserved; changes are disabled.',
              )}{' '}
              <button
                type="button"
                onClick={() => setAttempt((value) => value + 1)}
                className="text-accent underline"
              >
                {t('common.retry', undefined, 'Retry')}
              </button>
            </div>
          )}
          {current && !available.has(current.code) && (
            <p className="bg-subtle text-text-2 rounded-control p-3 text-xs">
              {t(
                'roleFields.noBusiness',
                undefined,
                'This role has no business access to this object. Field settings cannot grant access; existing restrictions may be removed explicitly.',
              )}
            </p>
          )}
          {current && (
            <div className="border-border max-h-96 overflow-auto rounded-lg border">
              <table className="w-full text-left text-sm">
                <thead className="bg-subtle text-text-2 sticky top-0">
                  <tr>
                    <th className="p-3 font-medium">{t('roleFields.field', undefined, 'Field')}</th>
                    <th className="p-3 font-medium">
                      {t('roleFields.access', undefined, 'Access')}
                    </th>
                    <th className="p-3 font-medium">
                      {t('roleFields.other', undefined, 'Other restrictions')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {current.fields
                    .filter((field) =>
                      fieldLabel(field).toLowerCase().includes(query.trim().toLowerCase()),
                    )
                    .map((field) => {
                      const value =
                        changes.find(
                          (change) =>
                            change.modelCode === current.code && change.fieldCode === field.code,
                        )?.access ?? field.access;
                      return (
                        <tr key={field.code} className="border-border border-t">
                          <td className="p-3">{fieldLabel(field)}</td>
                          <td className="p-3">
                            <select
                              data-testid={`role-field-access-${field.code}`}
                              aria-label={fieldLabel(field)}
                              value={value}
                              disabled={
                                disabled ||
                                error ||
                                (!available.has(current.code) && field.access === 'inherit')
                              }
                              className="border-border bg-panel rounded-control border px-2 py-1.5 text-sm"
                              onChange={(event) => {
                                const next = changes.filter(
                                  (change) =>
                                    change.modelCode !== current.code ||
                                    change.fieldCode !== field.code,
                                );
                                if (event.target.value !== field.access)
                                  next.push({
                                    modelCode: current.code,
                                    fieldCode: field.code,
                                    access: event.target.value,
                                  });
                                onChange(next);
                              }}
                            >
                              {['inherit', 'read_only', 'hidden'].map((access) => (
                                <option
                                  key={access}
                                  value={access}
                                  disabled={access !== 'inherit' && !available.has(current.code)}
                                >
                                  {accessLabel(access)}
                                </option>
                              ))}
                            </select>
                          </td>
                          <td className="text-text-2 p-3 text-xs">
                            {field.additionalRestrictions?.length
                              ? field.additionalRestrictions.join('、')
                              : t(
                                  'roleFields.noAdditional',
                                  undefined,
                                  'No shared field restriction',
                                )}
                          </td>
                        </tr>
                      );
                    })}
                </tbody>
              </table>
            </div>
          )}
          <p className="text-text-2 text-xs">
            {t(
              'roleFields.inheritHelp',
              undefined,
              'Use business access removes only this role’s grid restriction. It does not grant write access or remove shared restrictions. Hidden fields are also read only.',
            )}
          </p>
        </>
      )}
    </section>
  );
}
