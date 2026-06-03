import { useEffect, useState } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import { tenantPreferenceService } from '~/shared/services/tenantPreferenceService';
import TimezoneSelect from '~/ui/TimezoneSelect';
import { workspacePageClassName } from '~/shared/layout/WorkspacePageLayout';

type MetaArgs = Record<string, unknown>;

export function meta({}: MetaArgs) {
  return [
    { title: 'System Preferences' },
    { name: 'description', content: 'Configure tenant-level system preferences' },
  ];
}

export default function SystemPreferencesPage() {
  const { showErrorToast, showSuccessToast } = useToastContext();
  const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const [datetimeFormat, setDatetimeFormat] = useState('YYYY-MM-DD HH:mm:ss');
  const [timezone, setTimezone] = useState(() => browserTimezone);
  // Whether a tenant-level timezone is actually persisted. When false the select
  // shows the browser timezone only as a starting point — it is NOT the active
  // tenant policy yet (users fall back to their own browser timezone until saved).
  const [timezoneConfigured, setTimezoneConfigured] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [timezoneSaving, setTimezoneSaving] = useState(false);

  useEffect(() => {
    Promise.all([
      tenantPreferenceService.get<string>('ui.datetime.format').then((value) => {
        if (value && value.trim()) setDatetimeFormat(value);
      }),
      tenantPreferenceService.get<string>('ui.timezone').then((value) => {
        if (value && value.trim()) {
          setTimezone(value);
          setTimezoneConfigured(true);
        }
      }),
    ])
      .catch((err) => {
        showErrorToast(`Failed to load system preferences: ${err?.message || 'Unknown error'}`);
      })
      .finally(() => setLoading(false));
  }, [showErrorToast]);

  const handleSave = async () => {
    try {
      setSaving(true);
      await tenantPreferenceService.set('ui.datetime.format', datetimeFormat.trim());
      showSuccessToast('System datetime format saved');
    } catch (err: any) {
      showErrorToast(`Failed to save system preference: ${err?.message || 'Unknown error'}`);
    } finally {
      setSaving(false);
    }
  };

  const handleSaveTimezone = async () => {
    try {
      setTimezoneSaving(true);
      await tenantPreferenceService.set('ui.timezone', timezone);
      setTimezoneConfigured(true);
      showSuccessToast('System timezone saved');
    } catch (err: any) {
      showErrorToast(`Failed to save system timezone: ${err?.message || 'Unknown error'}`);
    } finally {
      setTimezoneSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-50">
        <div className="text-gray-500">Loading...</div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="border-b bg-white">
        <div className={workspacePageClassName('header')}>
          <h1 className="text-2xl font-bold text-gray-900">System Preferences</h1>
          <p className="mt-1 text-sm text-gray-500">
            Tenant-level display policy, lower priority than user preferences
          </p>
        </div>
      </div>

      <div className={workspacePageClassName('contentRelaxed')}>
        <div className="rounded-lg bg-white p-6 shadow-sm">
          <h2 className="mb-2 text-lg font-semibold text-gray-900">Datetime Display Format</h2>
          <p className="mb-4 text-sm text-gray-500">
            This format is used for datetime fields such as `created_at`. Recommended: YYYY-MM-DD
            HH:mm:ss
          </p>

          <div className="flex flex-col gap-3 md:flex-row">
            <input
              type="text"
              value={datetimeFormat}
              onChange={(e) => setDatetimeFormat(e.target.value)}
              data-testid="system-datetime-format-input"
              className="flex-1 rounded-lg border border-gray-300 px-3 py-2 focus:border-transparent focus:ring-2 focus:ring-blue-500"
            />
            <button
              type="button"
              onClick={handleSave}
              disabled={saving || !datetimeFormat.trim()}
              data-testid="system-datetime-format-save"
              className="rounded-lg bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {saving ? 'Saving...' : 'Save'}
            </button>
          </div>
        </div>

        <div className="mt-6 rounded-lg bg-white p-6 shadow-sm">
          <h2 className="mb-2 text-lg font-semibold text-gray-900">Timezone</h2>
          <p className="mb-4 text-sm text-gray-500">
            Default timezone for all users in this tenant. Users can override this in their personal
            preferences.
          </p>

          <div className="flex flex-col items-start gap-3 md:flex-row">
            <div className="min-w-0 flex-1">
              <TimezoneSelect
                value={timezone}
                onChange={setTimezone}
                data-testid="system-timezone-select"
              />
              <p className="mt-2 text-xs text-gray-500" data-testid="system-timezone-status">
                {timezoneConfigured
                  ? `Tenant timezone is set to ${timezone}.`
                  : `Not configured yet — users currently fall back to their own browser timezone (yours is ${browserTimezone}). Click Save to apply this as the tenant default.`}
              </p>
            </div>
            <button
              type="button"
              onClick={handleSaveTimezone}
              disabled={timezoneSaving}
              data-testid="system-timezone-save"
              className="rounded-lg bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {timezoneSaving ? 'Saving...' : 'Save'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
