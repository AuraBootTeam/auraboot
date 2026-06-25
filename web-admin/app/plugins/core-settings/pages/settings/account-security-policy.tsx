import { useEffect, useState } from 'react';
import { LockKeyhole, RotateCcwKey, ShieldCheck, UserCog } from 'lucide-react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { workspacePageClassName } from '~/shared/layout/WorkspacePageLayout';

type PasswordPolicy = {
  minLength: number;
  maxLength: number;
  requireUppercase: boolean;
  requireLowercase: boolean;
  requireDigit: boolean;
  requireSpecial: boolean;
  historyCount: number;
  expiryDays: number;
  resetTokenExpiryMinutes: number;
};

type LockoutPolicy = {
  maxAttempts: number;
  durationMinutes: number;
};

type AccountSecurityPolicy = {
  mode: 'admin_managed' | 'self_service' | string;
  publicRegistrationEnabled: boolean;
  selfServicePasswordEnabled: boolean;
  adminManagedPasswordEnabled: boolean;
  mustChangePasswordAfterAdminReset: boolean;
  password: PasswordPolicy;
  lockout: LockoutPolicy;
  notes: string[];
};

type PolicyItem = {
  label: string;
  value: string;
  tone?: 'enabled' | 'disabled' | 'neutral';
};

export function meta() {
  return [
    { title: 'Account Security Policy' },
    { name: 'description', content: 'Read-only account and password policy summary' },
  ];
}

function boolText(value: boolean) {
  return value ? 'Enabled' : 'Disabled';
}

function boolTone(value: boolean): PolicyItem['tone'] {
  return value ? 'enabled' : 'disabled';
}

function PolicySection({
  title,
  description,
  icon: Icon,
  items,
}: {
  title: string;
  description: string;
  icon: typeof ShieldCheck;
  items: PolicyItem[];
}) {
  return (
    <section className="rounded-md border border-slate-200 bg-white" data-testid={`policy-section-${title.toLowerCase().replaceAll(' ', '-')}`}>
      <div className="flex items-start gap-3 border-b border-slate-100 px-5 py-4">
        <div className="flex h-9 w-9 items-center justify-center rounded-md bg-slate-100 text-slate-700">
          <Icon className="h-5 w-5" aria-hidden="true" />
        </div>
        <div>
          <h2 className="text-base font-semibold text-slate-950">{title}</h2>
          <p className="mt-1 text-sm text-slate-500">{description}</p>
        </div>
      </div>
      <dl className="grid grid-cols-1 divide-y divide-slate-100 md:grid-cols-2 md:divide-x md:divide-y-0">
        {items.map((item) => (
          <div key={item.label} className="px-5 py-4">
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">{item.label}</dt>
            <dd
              className={
                item.tone === 'enabled'
                  ? 'mt-1 text-sm font-semibold text-emerald-700'
                  : item.tone === 'disabled'
                    ? 'mt-1 text-sm font-semibold text-slate-500'
                    : 'mt-1 text-sm font-semibold text-slate-950'
              }
            >
              {item.value}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export default function AccountSecurityPolicyPage() {
  const [policy, setPolicy] = useState<AccountSecurityPolicy | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchResult<AccountSecurityPolicy>('/api/admin/account-security-policy')
      .then((result) => {
        if (cancelled) return;
        if (ResultHelper.isSuccess(result) && result.data) {
          setPolicy(result.data);
          return;
        }
        setError(result.desc || result.message || 'Failed to load account security policy');
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load account security policy');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) {
    return (
      <div className="min-h-screen bg-slate-50">
        <div className={workspacePageClassName('contentRelaxed')}>
          <div className="text-sm text-slate-500" data-testid="account-security-policy-loading">
            Loading account security policy...
          </div>
        </div>
      </div>
    );
  }

  if (error || !policy) {
    return (
      <div className="min-h-screen bg-slate-50">
        <div className={workspacePageClassName('contentRelaxed')}>
          <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700" data-testid="account-security-policy-error">
            {error || 'Account security policy unavailable'}
          </div>
        </div>
      </div>
    );
  }

  const modeText = policy.mode === 'self_service' ? 'Self-service enabled' : 'Administrator managed';

  return (
    <div className="min-h-screen bg-slate-50" data-testid="account-security-policy-page">
      <div className="border-b border-slate-200 bg-white">
        <div className={workspacePageClassName('header')}>
          <div className="flex items-center justify-between gap-4">
            <div>
              <h1 className="text-2xl font-bold text-slate-950">Account Security Policy</h1>
              <p className="mt-1 text-sm text-slate-500">
                Read-only view of the current deployment password rules and tenant account behavior.
              </p>
            </div>
            <span className="rounded-md border border-slate-200 bg-slate-50 px-3 py-1 text-sm font-medium text-slate-700" data-testid="account-security-policy-mode">
              {modeText}
            </span>
          </div>
        </div>
      </div>

      <div className={workspacePageClassName('contentRelaxed')}>
        <div className="space-y-4">
          <PolicySection
            title="Account Behavior"
            description="Tenant-visible account behavior. Public registration stays closed by default."
            icon={UserCog}
            items={[
              { label: 'Public registration', value: boolText(policy.publicRegistrationEnabled), tone: boolTone(policy.publicRegistrationEnabled) },
              { label: 'Self-service password', value: boolText(policy.selfServicePasswordEnabled), tone: boolTone(policy.selfServicePasswordEnabled) },
              { label: 'Admin managed reset', value: boolText(policy.adminManagedPasswordEnabled), tone: boolTone(policy.adminManagedPasswordEnabled) },
              { label: 'Force change after reset', value: boolText(policy.mustChangePasswordAfterAdminReset), tone: boolTone(policy.mustChangePasswordAfterAdminReset) },
            ]}
          />

          <PolicySection
            title="Password Complexity"
            description="Deployment-level password rules shared by all tenants."
            icon={LockKeyhole}
            items={[
              { label: 'Length', value: `${policy.password.minLength}-${policy.password.maxLength} characters` },
              { label: 'Lowercase letter', value: boolText(policy.password.requireLowercase), tone: boolTone(policy.password.requireLowercase) },
              { label: 'Digit', value: boolText(policy.password.requireDigit), tone: boolTone(policy.password.requireDigit) },
              { label: 'Uppercase / special', value: `${boolText(policy.password.requireUppercase)} / ${boolText(policy.password.requireSpecial)}` },
            ]}
          />

          <PolicySection
            title="Reset And History"
            description="Password reuse, expiry, and token lifetime controls."
            icon={RotateCcwKey}
            items={[
              { label: 'History reuse block', value: `${policy.password.historyCount} recent passwords` },
              { label: 'Password expiry', value: `${policy.password.expiryDays} days` },
              { label: 'Reset token lifetime', value: `${policy.password.resetTokenExpiryMinutes} minutes` },
              { label: 'Recovery mode', value: policy.selfServicePasswordEnabled ? 'Token reset available' : 'Contact administrator' },
            ]}
          />

          <PolicySection
            title="Login Lockout"
            description="Protection against repeated password guessing."
            icon={ShieldCheck}
            items={[
              { label: 'Failed attempts', value: `${policy.lockout.maxAttempts} attempts` },
              { label: 'Lock duration', value: `${policy.lockout.durationMinutes} minutes` },
            ]}
          />

          <section className="rounded-md border border-slate-200 bg-white px-5 py-4">
            <h2 className="text-base font-semibold text-slate-950">Delivery Notes</h2>
            <ul className="mt-3 space-y-2 text-sm text-slate-600">
              {policy.notes.map((note) => (
                <li key={note} className="flex gap-2">
                  <span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-slate-400" />
                  <span>{note}</span>
                </li>
              ))}
            </ul>
          </section>
        </div>
      </div>
    </div>
  );
}
