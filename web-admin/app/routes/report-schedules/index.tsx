import { useState, useEffect, useCallback } from 'react';
import {
  PlusIcon,
  TrashIcon,
  PencilIcon,
  PaperAirplaneIcon,
  ClockIcon,
} from '@heroicons/react/24/outline';
import { toast } from 'sonner';
import { fetchResult } from '~/services/http-client/HttpClient';
import { useToken as useAuthToken } from '~/contexts/AuthContext';
import ScheduleFormModal from './ScheduleFormModal';

interface ReportSchedule {
  id: number;
  pid: string;
  name: string;
  reportId: string;
  scheduleCron: string;
  recipients: string[];
  format: string;
  subjectTemplate: string;
  enabled: boolean;
  lastRunAt: string | null;
  nextRunAt: string | null;
  lastRunStatus: string | null;
  lastRunError: string | null;
  createdAt: string;
}

const API_PATH = '/api/report-schedules';

/**
 * Report Schedules management page.
 * Lists all schedules with CRUD and test-send actions.
 */
export default function ReportSchedules() {
  const [schedules, setSchedules] = useState<ReportSchedule[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editingSchedule, setEditingSchedule] = useState<ReportSchedule | null>(null);
  const token = useAuthToken();

  const fetchSchedules = useCallback(async () => {
    try {
      const result = await fetchResult<ReportSchedule[]>(API_PATH, {
        method: 'get',
        token: token ?? undefined,
      });
      if (result.code === '0' && result.data) {
        setSchedules(result.data);
      }
    } catch (err) {
      console.error('Failed to fetch schedules:', err);
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    fetchSchedules();
  }, [fetchSchedules]);

  const handleDelete = async (id: number) => {
    if (!confirm('Are you sure you want to delete this schedule?')) return;
    try {
      const result = await fetchResult(`${API_PATH}/${id}`, {
        method: 'delete',
        token: token ?? undefined,
      });
      if (result.code === '0') {
        toast.success('Schedule deleted');
        fetchSchedules();
      } else {
        toast.error('Failed to delete schedule');
      }
    } catch (err) {
      toast.error('Failed to delete schedule');
    }
  };

  const handleTestSend = async (id: number) => {
    try {
      const result = await fetchResult(`${API_PATH}/${id}/test-send`, {
        method: 'post',
        token: token ?? undefined,
      });
      if (result.code === '0') {
        toast.success('Test email sent');
        fetchSchedules();
      } else {
        toast.error('Failed to send test email');
      }
    } catch (err) {
      toast.error('Failed to send test email');
    }
  };

  const handleSave = async () => {
    setShowModal(false);
    setEditingSchedule(null);
    fetchSchedules();
  };

  const handleEdit = (schedule: ReportSchedule) => {
    setEditingSchedule(schedule);
    setShowModal(true);
  };

  const formatDate = (date: string | null) => {
    if (!date) return '-';
    return new Date(date).toLocaleString();
  };

  return (
    <div className="mx-auto max-w-7xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-800 dark:text-gray-200">Report Schedules</h1>
          <p className="mt-1 text-sm text-gray-500">Manage automated report email delivery</p>
        </div>
        <button
          onClick={() => {
            setEditingSchedule(null);
            setShowModal(true);
          }}
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
        >
          <PlusIcon className="h-5 w-5" />
          New Schedule
        </button>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-lg bg-white shadow dark:bg-gray-800">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 dark:bg-gray-700">
              <tr>
                <th className="px-4 py-3 text-left font-medium text-gray-600 dark:text-gray-300">
                  Name
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-600 dark:text-gray-300">
                  Schedule
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-600 dark:text-gray-300">
                  Recipients
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-600 dark:text-gray-300">
                  Format
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-600 dark:text-gray-300">
                  Status
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-600 dark:text-gray-300">
                  Last Run
                </th>
                <th className="px-4 py-3 text-right font-medium text-gray-600 dark:text-gray-300">
                  Actions
                </th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={7} className="px-4 py-8 text-center text-gray-400">
                    Loading...
                  </td>
                </tr>
              ) : schedules.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-4 py-8 text-center text-gray-400">
                    No schedules configured. Click "New Schedule" to get started.
                  </td>
                </tr>
              ) : (
                schedules.map((schedule) => (
                  <tr
                    key={schedule.id}
                    className="dark:hover:bg-gray-750 border-t hover:bg-gray-50 dark:border-gray-700"
                  >
                    <td className="px-4 py-3">
                      <div className="font-medium text-gray-800 dark:text-gray-200">
                        {schedule.name}
                      </div>
                      <div className="text-xs text-gray-500">Report: {schedule.reportId}</div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1 text-gray-600 dark:text-gray-300">
                        <ClockIcon className="h-4 w-4" />
                        <code className="rounded bg-gray-100 px-1.5 py-0.5 text-xs dark:bg-gray-700">
                          {schedule.scheduleCron}
                        </code>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap gap-1">
                        {schedule.recipients?.slice(0, 2).map((email, i) => (
                          <span
                            key={i}
                            className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600 dark:bg-gray-700 dark:text-gray-300"
                          >
                            {email}
                          </span>
                        ))}
                        {(schedule.recipients?.length ?? 0) > 2 && (
                          <span className="text-xs text-gray-500">
                            +{schedule.recipients.length - 2} more
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className="rounded bg-blue-50 px-2 py-0.5 text-xs text-blue-600 dark:bg-blue-900 dark:text-blue-300">
                        {schedule.format}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={`rounded px-2 py-0.5 text-xs ${
                          schedule.enabled
                            ? 'bg-green-50 text-green-600 dark:bg-green-900 dark:text-green-300'
                            : 'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400'
                        }`}
                      >
                        {schedule.enabled ? 'Active' : 'Disabled'}
                      </span>
                      {schedule.lastRunStatus && (
                        <span
                          className={`ml-1 rounded px-2 py-0.5 text-xs ${
                            schedule.lastRunStatus === 'success'
                              ? 'bg-green-50 text-green-600'
                              : 'bg-red-50 text-red-600'
                          }`}
                        >
                          {schedule.lastRunStatus}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-500">
                      {formatDate(schedule.lastRunAt)}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          onClick={() => handleTestSend(schedule.id)}
                          className="rounded p-1.5 text-gray-500 transition-colors hover:bg-blue-50 hover:text-blue-600"
                          title="Send test email"
                        >
                          <PaperAirplaneIcon className="h-4 w-4" />
                        </button>
                        <button
                          onClick={() => handleEdit(schedule)}
                          className="rounded p-1.5 text-gray-500 transition-colors hover:bg-blue-50 hover:text-blue-600"
                          title="Edit"
                        >
                          <PencilIcon className="h-4 w-4" />
                        </button>
                        <button
                          onClick={() => handleDelete(schedule.id)}
                          className="rounded p-1.5 text-gray-500 transition-colors hover:bg-red-50 hover:text-red-600"
                          title="Delete"
                        >
                          <TrashIcon className="h-4 w-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Schedule Form Modal */}
      {showModal && (
        <ScheduleFormModal
          schedule={editingSchedule}
          onClose={() => {
            setShowModal(false);
            setEditingSchedule(null);
          }}
          onSave={handleSave}
          token={token}
        />
      )}
    </div>
  );
}
