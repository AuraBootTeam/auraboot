import React from 'react';
import { Link, useNavigate } from 'react-router';
import { cellRendererRegistry } from '~/framework/meta/runtime/renderers/CellRendererRegistry';
import { ManagedBadge } from '~/ui/common/ManagedBadge';
import { SourceTypeBadge } from '~/shared/components/SourceTypeBadge';
import { useModelListSchemaContext } from './ListSchemaContext';
import { useToastContext } from '~/contexts/ToastContext';
import { confirmDialog } from '~/utils/confirmDialog';
import { modelService } from '~/shared/services/modelService';

type HealthStatus = 'ok' | 'field_drift' | 'detection_failed' | 'unknown';

const HEALTH_STYLES: Record<HealthStatus, { label: string; className: string }> = {
  ok: { label: '正常', className: 'bg-green-50 text-green-700 ring-green-200' },
  field_drift: { label: '字段漂移', className: 'bg-amber-50 text-amber-700 ring-amber-200' },
  detection_failed: { label: '检测失败', className: 'bg-red-50 text-red-700 ring-red-200' },
  unknown: { label: '未检测', className: 'bg-gray-50 text-gray-600 ring-gray-200' },
};

function ModelCodeCell({ value, record }: { value: unknown; record: Record<string, any> }) {
  const { owners } = useModelListSchemaContext();
  const owner = owners[`MODEL:${record.code}`];

  return (
    <div className="flex flex-col gap-1">
      <Link
        to={`/meta/models/${record.pid}`}
        className="font-medium text-blue-600 hover:text-blue-700 hover:underline"
      >
        {String(value ?? '')}
      </Link>
      {owner?.managed && owner.pluginName ? (
        <ManagedBadge pluginName={owner.pluginName} userModified={owner.userModified} />
      ) : null}
    </div>
  );
}

function ModelHealthCell({ record }: { record: Record<string, any> }) {
  const rawStatus = (record.health?.status ?? 'unknown') as HealthStatus;
  const health = HEALTH_STYLES[rawStatus] ?? HEALTH_STYLES.unknown;
  const title = record.health?.lastDetectedAt
    ? `最近检测: ${new Date(record.health.lastDetectedAt).toLocaleString('zh-CN')}`
    : '从未检测';

  return (
    <span
      title={title}
      className={`inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ring-1 ring-inset ${health.className}`}
    >
      {health.label}
    </span>
  );
}

function ModelActionsCell({ record }: { record: Record<string, any> }) {
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { reloadEventName } = useModelListSchemaContext();

  const handleDelete = async () => {
    const confirmed = await confirmDialog({
      title: '删除模型',
      content: `确定删除模型 ${record.code} 吗？`,
      variant: 'danger',
    });
    if (!confirmed) {
      return;
    }

    try {
      await modelService.delete(record.pid);
      showSuccessToast('模型已删除');
      window.dispatchEvent(new Event(reloadEventName));
    } catch (error) {
      console.error('Failed to delete model:', error);
      showErrorToast(error instanceof Error ? error.message : '删除模型失败');
    }
  };

  return (
    <div className="flex items-center gap-2">
      <button
        type="button"
        onClick={(event) => {
          event.stopPropagation();
          navigate(`/meta/models/${record.pid}`);
        }}
        className="text-sm text-gray-600 hover:text-gray-900"
      >
        查看
      </button>
      <button
        type="button"
        onClick={(event) => {
          event.stopPropagation();
          navigate(`/meta/models/${record.pid}/edit`);
        }}
        className="text-sm text-blue-600 hover:text-blue-700"
      >
        编辑
      </button>
      <button
        type="button"
        onClick={(event) => {
          event.stopPropagation();
          void handleDelete();
        }}
        className="text-sm text-red-600 hover:text-red-700"
      >
        删除
      </button>
    </div>
  );
}

let registered = false;

export function ensureModelListRenderersRegistered() {
  if (registered) {
    return;
  }

  cellRendererRegistry.register('meta_model_code', ({ value, record }) => (
    <ModelCodeCell value={value} record={record} />
  ));
  cellRendererRegistry.register('meta_model_source_type', ({ record }) => (
    <SourceTypeBadge sourceType={record.sourceType} />
  ));
  cellRendererRegistry.register('meta_model_health', ({ record }) => (
    <ModelHealthCell record={record} />
  ));
  cellRendererRegistry.register('meta_model_actions', ({ record }) => (
    <ModelActionsCell record={record} />
  ));

  registered = true;
}
