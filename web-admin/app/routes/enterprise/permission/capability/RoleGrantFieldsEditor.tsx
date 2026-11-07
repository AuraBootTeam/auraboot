import { useI18n } from '~/contexts/I18nContext';
import type { AuthorizationDraft, AuthorizationPreview } from './authorizationDraftService';

export type GrantFieldChange = NonNullable<AuthorizationDraft['grantFieldChanges']>[number];
export type GrantFieldBinding = NonNullable<AuthorizationPreview['grantFieldCandidates']>[number];
const keyOf = (value: GrantFieldBinding | GrantFieldChange) =>
  value.bindingPid ? `binding:${value.bindingPid}` : `permission:${value.permissionCode}`;

interface Props {
  model: string;
  candidates: GrantFieldBinding[] | undefined;
  changes: GrantFieldChange[];
  disabled: boolean;
  loading: boolean;
  error: boolean;
  onRetry: () => void;
  onChange: (changes: GrantFieldChange[]) => void;
}

/** Each finite allowance stays attached to its complete grant, never to a model-wide union. */
export default function RoleGrantFieldsEditor({
  model,
  candidates,
  changes,
  disabled,
  loading,
  error,
  onRetry,
  onChange,
}: Props) {
  const { t } = useI18n();
  const bindings = (candidates ?? []).filter((binding) => binding.modelCode === model);
  return (
    <section
      data-testid="role-grant-fields"
      className="border-border rounded-control space-y-3 border p-3"
    >
      <h3 className="text-text-1 text-sm font-semibold">
        {t('roleGrantFields.title', undefined, 'Allowed fields per authorization')}
      </h3>
      <p className="text-text-2 text-xs leading-5">
        {t(
          'roleGrantFields.help',
          undefined,
          'Field lists apply to one authorization and its record scope. They do not grant business access. Hidden, read-only and shared restrictions still apply.',
        )}
      </p>
      {loading && (
        <p role="status" className="text-text-2 text-xs">
          {t('roleGrantFields.loading', undefined, 'Checking the current draft…')}
        </p>
      )}
      {error && (
        <p role="alert" className="text-text-2 text-xs">
          {t(
            'roleGrantFields.error',
            undefined,
            'Could not check field candidates. Your edits are preserved; field editing is disabled.',
          )}{' '}
          <button
            type="button"
            className="text-accent underline"
            onClick={onRetry}
            disabled={disabled}
          >
            {t('common.retry', undefined, 'Retry')}
          </button>
        </p>
      )}
      {!loading && !error && !bindings.length && (
        <p className="text-text-2 text-xs">
          {t(
            'roleGrantFields.empty',
            undefined,
            'No model-bound authorization is available for this object. Managed sources retain their original authoring rules.',
          )}
        </p>
      )}
      {bindings.map((binding, index) => {
        const key = keyOf(binding);
        const change = changes.find((item) => keyOf(item) === key);
        const writable =
          !disabled &&
          !loading &&
          !error &&
          binding.editable === true &&
          !!binding.metadataRevision;
        const action = binding.permissionCode?.split('.').at(-1) ?? '';
        const fields = binding.fields ?? [];
        const readFields = change?.readFields ?? binding.readFields ?? [];
        const writeFields = change?.writeFields ?? binding.writeFields ?? [];
        const label = (code: string) => {
          const field = fields.find((item) => item.code === code);
          return t(
            `model.${model}.${code}.label`,
            undefined,
            field?.label && field.label !== code
              ? field.label
              : t('roleFields.unnamedField', undefined, 'Field definition unavailable'),
          );
        };
        function update(read: string[], write: string[]) {
          onChange([
            ...changes.filter((item) => keyOf(item) !== key),
            {
              ...(binding.bindingPid
                ? { bindingPid: binding.bindingPid }
                : { permissionCode: binding.permissionCode }),
              expectedMetadataRevision:
                change?.expectedMetadataRevision ?? binding.metadataRevision,
              readFields: read,
              writeFields: write,
            },
          ]);
        }
        return (
          <div
            key={key}
            data-testid={`grant-fields-${binding.permissionCode}-${index}`}
            className="border-border space-y-2 border-t pt-3"
          >
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h4 className="text-text-1 text-sm font-medium">
                {t(`roleGrantFields.action.${action}`, undefined, 'Authorization')} · {index + 1}
                {!binding.bindingPid && (
                  <span className="text-text-2 ml-2 text-xs">
                    {t('roleGrantFields.pending', undefined, 'New in this draft')}
                  </span>
                )}
              </h4>
              {change && (
                <button
                  type="button"
                  disabled={disabled}
                  className="text-accent text-xs underline"
                  onClick={() => onChange(changes.filter((item) => keyOf(item) !== key))}
                >
                  {t('roleGrantFields.reset', undefined, 'Discard field edits')}
                </button>
              )}
            </div>
            {!binding.editable && (
              <p className="text-text-2 text-xs">
                {t(
                  'roleGrantFields.managed',
                  undefined,
                  'This authorization is managed by its source. It cannot be overwritten here.',
                )}
              </p>
            )}
            {binding.legacy && !change ? (
              <div className="text-text-2 flex flex-wrap items-center justify-between gap-2 text-xs">
                <p>
                  {t(
                    'roleGrantFields.legacy',
                    undefined,
                    'No explicit field list. Existing authorization rules are preserved.',
                  )}
                </p>
                <button
                  type="button"
                  disabled={!writable}
                  className="text-accent disabled:text-text-3 underline"
                  onClick={() =>
                    update(
                      action === 'read'
                        ? fields.flatMap((field) => (field.code ? [field.code] : []))
                        : [],
                      action !== 'read'
                        ? fields.flatMap((field) => (field.code ? [field.code] : []))
                        : [],
                    )
                  }
                >
                  {t('roleGrantFields.configure', undefined, 'Set a finite field list')}
                </button>
              </div>
            ) : (
              <>
                <p className="text-text-2 text-xs">
                  {t(
                    'roleGrantFields.finite',
                    undefined,
                    'Only selected fields are allowed. An empty list allows no fields; future fields are not included automatically. Reading and writing remain subject to the authorization’s action.',
                  )}
                </p>
                <div className="border-border rounded-control max-h-64 overflow-auto border">
                  <table className="w-full text-left text-xs">
                    <thead className="bg-subtle text-text-2 sticky top-0">
                      <tr>
                        <th className="p-2 font-medium">
                          {t('roleFields.field', undefined, 'Field')}
                        </th>
                        <th className="p-2 font-medium">
                          {t('roleGrantFields.read', undefined, 'May read')}
                        </th>
                        <th className="p-2 font-medium">
                          {t('roleGrantFields.write', undefined, 'May write')}
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {fields.map(
                        (field) =>
                          field.code && (
                            <tr key={field.code} className="border-border border-t">
                              <td className="p-2">{label(field.code!)}</td>
                              {(['read', 'write'] as const).map((mode) => {
                                const selected = mode === 'read' ? readFields : writeFields;
                                return (
                                  <td key={mode} className="p-2">
                                    <input
                                      type="checkbox"
                                      data-testid={`grant-field-${binding.permissionCode}-${index}-${mode}-${field.code}`}
                                      aria-label={`${label(field.code!)} · ${t(`roleGrantFields.${mode}`, undefined, mode === 'read' ? 'May read' : 'May write')}`}
                                      checked={selected.includes(field.code!)}
                                      disabled={
                                        !writable ||
                                        (mode === 'write' && action === 'read') ||
                                        (mode === 'read' && action !== 'read')
                                      }
                                      onChange={(event) => {
                                        const next = event.target.checked
                                          ? [...selected, field.code!]
                                          : selected.filter((code) => code !== field.code);
                                        update(
                                          mode === 'read' ? next : readFields,
                                          mode === 'write' ? next : writeFields,
                                        );
                                      }}
                                    />
                                  </td>
                                );
                              })}
                            </tr>
                          ),
                      )}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </div>
        );
      })}
    </section>
  );
}
