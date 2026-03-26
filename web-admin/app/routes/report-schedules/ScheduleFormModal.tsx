import { useState } from 'react';
import { XMarkIcon, PlusIcon, TrashIcon } from '@heroicons/react/24/outline';
import { toast } from 'sonner';
import { fetchResult } from '~/services/http-client/HttpClient';

interface ScheduleFormModalProps {
  schedule: any | null;
  onClose: () => void;
  onSave: () => void;
  token: string | null;
}

const API_PATH = '/api/report-schedules';

const CRON_PRESETS = [
  { label: 'Daily at 8am', value: '0 0 8 * * *' },
  { label: 'Every Monday 8am', value: '0 0 8 * * MON' },
  { label: 'First of month 9am', value: '0 0 9 1 * *' },
  { label: 'Every 6 hours', value: '0 0 */6 * * *' },
  { label: 'Weekdays at 6pm', value: '0 0 18 * * MON-FRI' },
];

const FORMAT_OPTIONS = ['pdf', 'excel', 'html'];

/**
 * Modal form for creating/editing a report schedule.
 */
export default function ScheduleFormModal({
  schedule,
  onClose,
  onSave,
  token,
}: ScheduleFormModalProps) {
  const isEdit = !!schedule;

  const [name, setName] = useState(schedule?.name || '');
  const [reportId, setReportId] = useState(schedule?.reportId || '');
  const [scheduleCron, setScheduleCron] = useState(schedule?.scheduleCron || '0 0 8 * * MON');
  const [recipients, setRecipients] = useState<string[]>(schedule?.recipients || ['']);
  const [format, setFormat] = useState(schedule?.format || 'pdf');
  const [subjectTemplate, setSubjectTemplate] = useState(
    schedule?.subjectTemplate || 'Scheduled Report: ${reportName} - ${date}',
  );
  const [enabled, setEnabled] = useState(schedule?.enabled ?? true);
  const [saving, setSaving] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // Validate
    const validRecipients = recipients.filter((r) => r.trim().length > 0);
    if (validRecipients.length === 0) {
      toast.error('At least one recipient is required');
      return;
    }

    setSaving(true);
    try {
      const payload = {
        name,
        reportId,
        scheduleCron,
        recipients: validRecipients,
        format,
        subjectTemplate,
        enabled,
      };

      const url = isEdit ? `${API_PATH}/${schedule.id}` : API_PATH;
      const method = isEdit ? 'put' : 'post';

      const result = await fetchResult(url, {
        method: method as any,
        params: payload,
        token: token ?? undefined,
      });

      if (result.code === '0') {
        toast.success(isEdit ? 'Schedule updated' : 'Schedule created');
        onSave();
      } else {
        toast.error('Failed to save schedule');
      }
    } catch (err) {
      toast.error('Failed to save schedule');
    } finally {
      setSaving(false);
    }
  };

  const addRecipient = () => setRecipients([...recipients, '']);
  const removeRecipient = (index: number) =>
    setRecipients(recipients.filter((_, i) => i !== index));
  const updateRecipient = (index: number, value: string) => {
    const updated = [...recipients];
    updated[index] = value;
    setRecipients(updated);
  };

  return (
    <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
      <div className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-lg bg-white shadow-xl dark:bg-gray-800">
        {/* Header */}
        <div className="flex items-center justify-between border-b px-6 py-4 dark:border-gray-700">
          <h2 className="text-lg font-semibold text-gray-800 dark:text-gray-200">
            {isEdit ? 'Edit Schedule' : 'New Report Schedule'}
          </h2>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-700">
            <XMarkIcon className="h-5 w-5" />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="space-y-4 p-6">
          {/* Name */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              Schedule Name
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              className="w-full rounded-lg border px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder="e.g., Weekly Sales Report"
            />
          </div>

          {/* Report ID */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              Report ID
            </label>
            <input
              type="text"
              value={reportId}
              onChange={(e) => setReportId(e.target.value)}
              required
              className="w-full rounded-lg border px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder="Page schema ID or report key"
            />
          </div>

          {/* Schedule Cron */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              Schedule (Cron Expression)
            </label>
            <input
              type="text"
              value={scheduleCron}
              onChange={(e) => setScheduleCron(e.target.value)}
              required
              className="w-full rounded-lg border px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder="0 0 8 * * MON"
            />
            {/* Presets */}
            <div className="mt-2 flex flex-wrap gap-1">
              {CRON_PRESETS.map((preset) => (
                <button
                  key={preset.value}
                  type="button"
                  onClick={() => setScheduleCron(preset.value)}
                  className={`rounded border px-2 py-1 text-xs transition-colors ${
                    scheduleCron === preset.value
                      ? 'border-blue-300 bg-blue-50 text-blue-600'
                      : 'border-gray-200 bg-gray-50 text-gray-600 hover:bg-gray-100'
                  }`}
                >
                  {preset.label}
                </button>
              ))}
            </div>
          </div>

          {/* Recipients */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              Recipients
            </label>
            {recipients.map((email, i) => (
              <div key={i} className="mb-2 flex items-center gap-2">
                <input
                  type="email"
                  value={email}
                  onChange={(e) => updateRecipient(i, e.target.value)}
                  className="flex-1 rounded-lg border px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  placeholder="email@example.com"
                />
                {recipients.length > 1 && (
                  <button
                    type="button"
                    onClick={() => removeRecipient(i)}
                    className="rounded p-2 text-red-500 hover:bg-red-50"
                  >
                    <TrashIcon className="h-4 w-4" />
                  </button>
                )}
              </div>
            ))}
            <button
              type="button"
              onClick={addRecipient}
              className="flex items-center gap-1 text-sm text-blue-600 hover:text-blue-700"
            >
              <PlusIcon className="h-4 w-4" />
              Add recipient
            </button>
          </div>

          {/* Format */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              Output Format
            </label>
            <div className="flex gap-2">
              {FORMAT_OPTIONS.map((f) => (
                <button
                  key={f}
                  type="button"
                  onClick={() => setFormat(f)}
                  className={`rounded-lg border px-4 py-2 text-sm font-medium transition-colors ${
                    format === f
                      ? 'border-blue-600 bg-blue-600 text-white'
                      : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300'
                  }`}
                >
                  {f}
                </button>
              ))}
            </div>
          </div>

          {/* Subject Template */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              Email Subject Template
            </label>
            <input
              type="text"
              value={subjectTemplate}
              onChange={(e) => setSubjectTemplate(e.target.value)}
              className="w-full rounded-lg border px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder="Scheduled Report: ${reportName} - ${date}"
            />
            <p className="mt-1 text-xs text-gray-500">
              Available variables: {'${reportName}'}, {'${date}'}
            </p>
          </div>

          {/* Enabled toggle */}
          <div className="flex items-center gap-3">
            <label className="relative inline-flex cursor-pointer items-center">
              <input
                type="checkbox"
                checked={enabled}
                onChange={(e) => setEnabled(e.target.checked)}
                className="peer sr-only"
              />
              <div className="peer h-6 w-11 rounded-full bg-gray-200 peer-checked:bg-blue-600 peer-focus:ring-2 peer-focus:ring-blue-300 after:absolute after:top-[2px] after:left-[2px] after:h-5 after:w-5 after:rounded-full after:border after:border-gray-300 after:bg-white after:transition-all after:content-[''] peer-checked:after:translate-x-full peer-checked:after:border-white dark:border-gray-600 dark:bg-gray-600"></div>
            </label>
            <span className="text-sm text-gray-700 dark:text-gray-300">
              {enabled ? 'Schedule is active' : 'Schedule is disabled'}
            </span>
          </div>

          {/* Actions */}
          <div className="flex justify-end gap-3 border-t pt-4 dark:border-gray-700">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg bg-gray-100 px-4 py-2 text-sm text-gray-700 transition-colors hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={saving}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
            >
              {saving ? 'Saving...' : isEdit ? 'Update' : 'Create'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
