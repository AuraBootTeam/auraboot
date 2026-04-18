/**
 * TaskDetailDrawer - Right-side drawer for task details
 *
 * Integrates ApprovalTimeline, AttachmentPanel, SlaMonitorPanel and renders
 * the bound DSL form (when present) via the platform `useDslForm` /
 * `DslFormRenderer` pipeline — the same path BpmTaskDrawer uses for the
 * approval drawer. The previous bespoke TaskFormRenderer was deleted along
 * with the now-corrected TaskFormData shape (see bpmFormService.ts header).
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '~/ui/ui/button';
import { X, ExternalLink, FileText, Clock, Paperclip, Shield, ClipboardList } from 'lucide-react';
import type { TaskInstance } from '../services/bpmWorkbenchService';
import type { DialogState } from '../hooks/useTaskCenter';
import { ApprovalTimeline } from './ApprovalTimeline';
import { AttachmentPanel } from './AttachmentPanel';
import type { SlaRecord } from '../services/slaService';
import type { TaskFormData } from '../services/bpmFormService';
import * as slaService from '../services/slaService';
import * as bpmFormService from '../services/bpmFormService';
import { useDslForm } from '~/framework/meta/hooks/useDslForm';
import { DslFormRenderer } from '~/framework/meta/rendering/DslFormRenderer';

// ==================== Types ====================

interface TaskDetailDrawerProps {
  task: TaskInstance | null;
  onClose: () => void;
  onOpenDialog: (type: DialogState['type'], task: TaskInstance) => void;
  onClaim: (task: TaskInstance) => void;
}

type TabId = 'info' | 'form' | 'timeline' | 'attachments' | 'sla';

// ==================== Helper ====================

function formatDate(dateString?: string): string {
  if (!dateString) return '-';
  try {
    return new Date(dateString).toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return dateString;
  }
}

function priorityLabel(p?: number): { text: string; className: string } {
  if (p === undefined || p === null) return { text: '-', className: '' };
  if (p >= 80) return { text: '高', className: 'text-red-600' };
  if (p >= 50) return { text: '中', className: 'text-yellow-600' };
  return { text: '低', className: 'text-green-600' };
}

// ==================== Component ====================

export function TaskDetailDrawer({ task, onClose, onOpenDialog, onClaim }: TaskDetailDrawerProps) {
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState<TabId>('info');
  const [slaRecords, setSlaRecords] = useState<SlaRecord[]>([]);
  const [formData, setFormData] = useState<TaskFormData | null>(null);
  const [formLoading, setFormLoading] = useState(false);

  // Load SLA data when tab switches
  useEffect(() => {
    if (activeTab === 'sla' && task?.processInstanceId) {
      slaService
        .getSlaByInstance(task.processInstanceId)
        .then(setSlaRecords)
        .catch(() => setSlaRecords([]));
    }
  }, [activeTab, task?.processInstanceId]);

  // Load form data when form tab is active
  useEffect(() => {
    if (activeTab === 'form' && task?.taskId) {
      setFormLoading(true);
      bpmFormService
        .getTaskForm(task.taskId)
        .then(setFormData)
        .catch(() => setFormData(null))
        .finally(() => setFormLoading(false));
    }
  }, [activeTab, task?.taskId]);

  // Reset tab and form data when task changes
  useEffect(() => {
    setActiveTab('info');
    setFormData(null);
  }, [task?.taskId]);

  const handleViewFlowDiagram = useCallback(() => {
    if (task?.processInstanceId) {
      navigate(
        `/bpm/process-status?processInstanceId=${encodeURIComponent(task.processInstanceId)}`,
      );
    }
  }, [navigate, task?.processInstanceId]);

  if (!task) return null;

  const priority = priorityLabel(task.priority);

  const tabs: { id: TabId; label: string; icon: React.ReactNode }[] = [
    { id: 'info', label: '基本信息', icon: <FileText className="h-3.5 w-3.5" /> },
    { id: 'form', label: '表单', icon: <ClipboardList className="h-3.5 w-3.5" /> },
    { id: 'timeline', label: '审批记录', icon: <Clock className="h-3.5 w-3.5" /> },
    { id: 'attachments', label: '附件', icon: <Paperclip className="h-3.5 w-3.5" /> },
    { id: 'sla', label: 'sla', icon: <Shield className="h-3.5 w-3.5" /> },
  ];

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-40 bg-black/20" onClick={onClose} />

      {/* Drawer */}
      <div className="animate-in slide-in-from-right fixed top-0 right-0 bottom-0 z-50 flex w-[520px] flex-col bg-white shadow-xl duration-200">
        {/* Header */}
        <div className="flex items-center justify-between border-b px-6 py-4">
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-lg font-semibold">
              {task.taskName || task.title || '任务详情'}
            </h2>
            {task.businessKey && (
              <p className="truncate text-sm text-gray-500">{task.businessKey}</p>
            )}
          </div>
          <Button variant="ghost" size="sm" onClick={onClose} className="ml-2 flex-shrink-0">
            <X className="h-4 w-4" />
          </Button>
        </div>

        {/* Tabs */}
        <div className="border-b px-6">
          <div className="flex space-x-1">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`flex items-center gap-1.5 border-b-2 px-3 py-2.5 text-sm font-medium transition-colors ${
                  activeTab === tab.id
                    ? 'border-blue-600 text-blue-600'
                    : 'border-transparent text-gray-500 hover:text-gray-700'
                }`}
              >
                {tab.icon}
                {tab.label}
              </button>
            ))}
          </div>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-6">
          {activeTab === 'info' && <InfoTab task={task} priority={priority} />}
          {activeTab === 'form' && (
            <FormTab
              formData={formData}
              loading={formLoading}
              taskId={task.taskId}
              onSubmitSuccess={onClose}
            />
          )}
          {activeTab === 'timeline' && task.processInstanceId && (
            <ApprovalTimeline processInstanceId={task.processInstanceId} />
          )}
          {activeTab === 'attachments' && (
            <AttachmentPanel processInstanceId={task.processInstanceId} taskId={task.taskId} />
          )}
          {activeTab === 'sla' && <SlaTab records={slaRecords} />}
        </div>

        {/* Footer Actions */}
        <div className="flex items-center justify-between gap-2 border-t px-6 py-3">
          <Button variant="outline" size="sm" onClick={handleViewFlowDiagram} className="gap-1">
            <ExternalLink className="h-3.5 w-3.5" />
            查看流程图
          </Button>
          <div className="flex items-center gap-2">
            {!task.claimUserId && (
              <Button variant="outline" size="sm" onClick={() => onClaim(task)}>
                认领
              </Button>
            )}
            <Button
              size="sm"
              className="bg-green-600 hover:bg-green-700"
              onClick={() => onOpenDialog('approve', task)}
            >
              通过
            </Button>
            <Button variant="destructive" size="sm" onClick={() => onOpenDialog('reject', task)}>
              驳回
            </Button>
            <Button variant="outline" size="sm" onClick={() => onOpenDialog('complete', task)}>
              完成
            </Button>
          </div>
        </div>
      </div>
    </>
  );
}

// ==================== InfoTab ====================

function InfoTab({
  task,
  priority,
}: {
  task: TaskInstance;
  priority: { text: string; className: string };
}) {
  const fields = [
    { label: '任务名称', value: task.taskName || task.title || '-' },
    { label: '流程定义', value: task.processDefinitionKey || '-' },
    { label: '流程实例', value: task.processInstanceId || '-' },
    { label: '任务节点', value: task.taskDefinitionKey || '-' },
    { label: '处理人', value: task.claimUserId || task.assignee || '-' },
    { label: '创建时间', value: formatDate(task.createTime) },
    { label: '截止日期', value: formatDate(task.dueDate) },
    {
      label: '优先级',
      value: <span className={`font-medium ${priority.className}`}>{priority.text}</span>,
    },
  ];

  return (
    <div className="space-y-4">
      <dl className="grid grid-cols-2 gap-x-4 gap-y-3">
        {fields.map((f) => (
          <div key={f.label}>
            <dt className="mb-0.5 text-xs text-gray-500">{f.label}</dt>
            <dd className="text-sm text-gray-900">{f.value}</dd>
          </div>
        ))}
      </dl>
      {task.description && (
        <div className="border-t pt-3">
          <h4 className="mb-1 text-xs text-gray-500">描述</h4>
          <p className="text-sm whitespace-pre-wrap text-gray-700">{task.description}</p>
        </div>
      )}
    </div>
  );
}

// ==================== FormTab ====================

/**
 * FormTab — renders the DSL form bound to the current task via formBinding.
 *
 * Backend contract (TaskFormResponse): `formBinding` is a single object with
 * `formRef` (target page key) or `null` when no form is bound. We delegate
 * actual rendering to the same `useDslForm` + `DslFormRenderer` path used by
 * BpmTaskDrawer so field permission, variable mapping, validation, and
 * widget resolution all behave identically across the two drawers.
 *
 * Submit flow: DslFormRenderer calls `form.submit()` → onSubmit callback →
 * POST /api/bpm/forms/task/{taskId}/submit with the saveStrategy declared on
 * the binding. Success closes the parent drawer.
 */
function FormTab({
  formData,
  loading,
  taskId,
  onSubmitSuccess,
}: {
  formData: TaskFormData | null;
  loading: boolean;
  taskId: string;
  onSubmitSuccess: () => void;
}) {
  const [submitting, setSubmitting] = useState(false);
  const formBinding = formData?.formBinding ?? null;
  const hasForm = !!formBinding?.formRef;

  const handleFormSubmit = useCallback(
    async (payload: { values: Record<string, unknown> }) => {
      setSubmitting(true);
      try {
        const variableBindings = formBinding?.variableBindings ?? {};
        const mappedVars: Record<string, unknown> = {};
        for (const [varName, fieldCode] of Object.entries(variableBindings)) {
          if (fieldCode in payload.values) {
            mappedVars[varName] = payload.values[fieldCode];
          }
        }
        await bpmFormService.submitTaskForm(taskId, {
          saveStrategy: formBinding?.saveStrategy || 'business_only',
          businessData: payload.values,
          variables: mappedVars,
        });
        onSubmitSuccess();
      } finally {
        setSubmitting(false);
      }
    },
    [taskId, formBinding, onSubmitSuccess],
  );

  const form = useDslForm({
    pageKey: formBinding?.formRef || '',
    enabled: hasForm,
    recordId: formData?.businessKey || undefined,
    initialValues: (formData?.processVariables as Record<string, unknown>) || undefined,
    fieldPermissions: formBinding?.fieldPermissions || undefined,
    permissionMode: (formBinding?.permissionMode as 'merge' | 'override') || 'merge',
    onSubmit: hasForm ? handleFormSubmit : undefined,
  });

  if (loading) {
    return (
      <div
        className="flex items-center justify-center py-12 text-gray-400"
        data-testid="form-tab-loading"
      >
        <div className="mr-2 h-5 w-5 animate-spin rounded-full border-2 border-gray-300 border-t-blue-600" />
        <span className="text-sm">加载表单...</span>
      </div>
    );
  }

  if (!formData || !hasForm) {
    return (
      <div
        className="flex flex-col items-center justify-center py-12 text-gray-400"
        data-testid="form-tab-empty"
      >
        <ClipboardList className="mb-3 h-10 w-10 opacity-30" />
        <p className="text-sm">该任务未绑定表单</p>
      </div>
    );
  }

  return (
    <div
      className={submitting ? 'pointer-events-none opacity-60' : ''}
      data-testid="form-tab-content"
    >
      <DslFormRenderer form={form} compact className="bpm-detail-task-form" />
    </div>
  );
}

// ==================== SlaTab ====================

function SlaTab({ records }: { records: SlaRecord[] }) {
  if (records.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-gray-400">
        <Shield className="mb-3 h-10 w-10 opacity-30" />
        <p className="text-sm">暂无 SLA 记录</p>
      </div>
    );
  }

  const statusConfig: Record<string, { label: string; className: string }> = {
    running: { label: '运行中', className: 'bg-blue-100 text-blue-700' },
    WARNING: { label: '预警', className: 'bg-yellow-100 text-yellow-700' },
    OVERDUE: { label: '逾期', className: 'bg-red-100 text-red-700' },
    paused: { label: '已暂停', className: 'bg-gray-100 text-gray-700' },
    completed: { label: '已完成', className: 'bg-green-100 text-green-700' },
  };

  return (
    <div className="space-y-3">
      {records.map((r) => {
        const cfg = statusConfig[r.status] || {
          label: r.status,
          className: 'bg-gray-100 text-gray-700',
        };
        return (
          <div key={r.pid} className="space-y-2 rounded-lg border p-4">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium">SLA #{r.slaConfigId.slice(0, 8)}</span>
              <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${cfg.className}`}>
                {cfg.label}
              </span>
            </div>
            <div className="grid grid-cols-2 gap-2 text-xs text-gray-500">
              <div>
                <span className="font-medium">开始: </span>
                {formatDate(r.startTime)}
              </div>
              <div>
                <span className="font-medium">截止: </span>
                {formatDate(r.deadlineTime)}
              </div>
              {r.currentWarningLevel !== undefined && r.currentWarningLevel > 0 && (
                <div className="col-span-2 text-orange-600">预警等级: {r.currentWarningLevel}</div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
