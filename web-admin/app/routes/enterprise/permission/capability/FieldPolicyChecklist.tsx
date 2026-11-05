import { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { authorizationDraftService, type AuthorizationSnapshot } from './authorizationDraftService';

type BoundPolicy = NonNullable<AuthorizationSnapshot['fieldPolicies']>[number];
interface PolicyOption {
  pid: string;
  name: string;
  model: string;
  kind: 'column' | 'field_write';
  enabled: boolean;
}
interface Props {
  rolePid: string;
  bound: BoundPolicy[] | undefined;
  selected: string[];
  models: Set<string>;
  grantedCodes: Set<string>;
  disabled: boolean;
  readOnly?: boolean;
  onChange: (selected: string[]) => void;
}

/** Existing policy definitions are restrictions, never grants of business or model access. */
export default function FieldPolicyChecklist({
  rolePid,
  bound,
  selected,
  models,
  grantedCodes,
  disabled,
  readOnly = false,
  onChange,
}: Props) {
  const { t } = useI18n();
  const [catalog, setCatalog] = useState<PolicyOption[]>([]);
  const [roots, setRoots] = useState<Array<{ model: string; permissions: string[] }>>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const known =
    bound !== undefined &&
    bound.every(
      (p) =>
        typeof p.policyPid === 'string' &&
        p.policyPid.trim() &&
        typeof p.name === 'string' &&
        p.name.trim() &&
        typeof p.modelCode === 'string' &&
        p.modelCode.trim() &&
        typeof p.fieldCode === 'string' &&
        p.fieldCode.trim() &&
        typeof p.enabled === 'boolean' &&
        ['column', 'field_write'].includes(p.policyType?.toLowerCase() ?? ''),
    );
  useEffect(() => {
    let active = true;
    if (readOnly || !known) {
      setLoading(false);
      setError(false);
      setCatalog([]);
      setRoots([]);
      return;
    }
    setLoading(true);
    setError(false);
    setCatalog([]);
    setRoots([]);
    Promise.all([
      authorizationDraftService.fieldPolicies(),
      authorizationDraftService.fieldPolicyRoots(rolePid),
    ])
      .then(([policies, businessRoots]) => {
        if (
          !businessRoots.every(
            (root) =>
              typeof root.model === 'string' &&
              root.model.trim() &&
              Array.isArray(root.permissions) &&
              root.permissions.every((code) => typeof code === 'string' && code.trim()),
          )
        )
          throw new Error('Incomplete business field roots');
        const options: PolicyOption[] = [];
        for (const policy of policies) {
          const kind = policy.policyType?.toLowerCase();
          if (kind !== 'column' && kind !== 'field_write') continue;
          if (
            !policy.pid?.trim() ||
            !policy.name?.trim() ||
            !policy.modelCode?.trim() ||
            !policy.fieldCode?.trim() ||
            typeof policy.enabled !== 'boolean'
          )
            throw new Error('Incomplete field policy catalog');
          options.push({
            pid: policy.pid,
            name: policy.name,
            model: policy.modelCode,
            kind,
            enabled: policy.enabled,
          });
        }
        if (new Set(options.map((p) => p.pid)).size !== options.length)
          throw new Error('Duplicate field policies');
        if (active) {
          setCatalog(options);
          setRoots(businessRoots);
        }
      })
      .catch(() => {
        if (active) setError(true);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [rolePid, attempt, readOnly, known]);
  const options = new Map(catalog.map((p) => [p.pid, p]));
  for (const policy of bound ?? []) {
    if (policy.policyPid && policy.name && policy.modelCode && !options.has(policy.policyPid)) {
      options.set(policy.policyPid, {
        pid: policy.policyPid,
        name: policy.name,
        model: policy.modelCode,
        kind: policy.policyType?.toLowerCase() === 'field_write' ? 'field_write' : 'column',
        enabled: policy.enabled === true,
      });
    }
  }
  // Client hints expose only the owner's root, never the internal dependency reach.
  // Preview/save revalidate against the current server catalog and complete role grants.
  const applicableModels = new Set(models);
  roots
    .filter(
      (root) => grantedCodes.has('*') || root.permissions.some((code) => grantedCodes.has(code)),
    )
    .forEach((root) => applicableModels.add(root.model));
  const visible = [...options.values()]
    .filter((p) => selected.includes(p.pid) || applicableModels.has(p.model))
    .sort((a, b) => a.name.localeCompare(b.name));
  return (
    <section
      data-testid="role-field-policies"
      className="rounded-card border-border space-y-3 border p-4"
    >
      <h3 className="text-text-1 text-sm font-medium">
        {t('admin.permission.fields.title', undefined, 'Fields and sensitive information')}
      </h3>
      <p className="text-text-2 text-xs">
        {t(
          'admin.permission.fields.help',
          undefined,
          'Select existing field restrictions for this role. They do not grant business access and are saved with duties and record scopes.',
        )}
      </p>
      {(bound?.length ?? 0) > 0 && (
        <ul data-testid="bound-field-policy-summary" className="text-text-2 text-xs">
          {bound
            ?.filter((policy) => typeof policy.name === 'string' && policy.name.trim())
            .map((policy) => (
              <li key={policy.policyPid}>{policy.name}</li>
            ))}
        </ul>
      )}
      {!known ? (
        <p role="alert" className="text-status-red text-sm">
          {t(
            'admin.permission.fields.unknown',
            undefined,
            'Current field restrictions are unavailable. Existing bindings will be preserved.',
          )}
        </p>
      ) : loading ? (
        <p role="status" className="text-text-2 text-sm">
          {t('admin.permission.fields.loading', undefined, 'Loading field restrictions…')}
        </p>
      ) : error ? (
        <div role="alert" className="text-status-red space-y-2 text-sm">
          <p>
            {t(
              'admin.permission.fields.loadError',
              undefined,
              'Field restrictions could not be loaded. Check policy-management access or try again. Existing bindings will be preserved.',
            )}
          </p>
          <button
            type="button"
            data-testid="field-policies-retry"
            onClick={() => setAttempt((a) => a + 1)}
            className="text-brand underline"
          >
            {t('common.retry', undefined, 'Retry')}
          </button>
        </div>
      ) : visible.length === 0 ? (
        <p className="text-text-2 text-sm">
          {t(
            'admin.permission.fields.empty',
            undefined,
            'No field restrictions are available for this role’s authorized resources.',
          )}
        </p>
      ) : (
        <ul className="space-y-2">
          {visible.map((policy) => {
            const checked = selected.includes(policy.pid);
            const applicable = applicableModels.has(policy.model);
            return (
              <li key={policy.pid}>
                <label className="text-text-1 flex items-start gap-2 text-sm">
                  <input
                    type="checkbox"
                    data-testid={`field-policy-${policy.pid}`}
                    checked={checked}
                    disabled={disabled || (!checked && (!applicable || !policy.enabled))}
                    onChange={() =>
                      onChange(
                        checked
                          ? selected.filter((pid) => pid !== policy.pid)
                          : [...selected, policy.pid],
                      )
                    }
                    className="mt-1"
                  />
                  <span>
                    {policy.name}
                    <span className="text-text-2 ml-2 text-xs">
                      {t(
                        policy.kind === 'field_write'
                          ? 'admin.permission.fields.readOnly'
                          : 'admin.permission.fields.masked',
                        undefined,
                        policy.kind === 'field_write'
                          ? 'Read-only restriction'
                          : 'Visibility or masking restriction',
                      )}
                    </span>
                    {!policy.enabled && (
                      <span className="text-text-2 ml-2 text-xs">
                        {t('admin.permission.fields.inactive', undefined, 'Inactive')}
                      </span>
                    )}
                    {!applicable && (
                      <span className="text-text-2 block text-xs">
                        {t(
                          'admin.permission.fields.noResource',
                          undefined,
                          'This role no longer has access to the associated resource. Review this binding before saving field changes.',
                        )}
                      </span>
                    )}
                  </span>
                </label>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
