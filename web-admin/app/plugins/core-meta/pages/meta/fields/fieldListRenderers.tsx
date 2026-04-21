import React from 'react';
import { Link, useNavigate } from 'react-router';
import { cellRendererRegistry } from '~/framework/meta/runtime/renderers/CellRendererRegistry';
import { ManagedBadge } from '~/ui/common/ManagedBadge';
import { useFieldListSchemaContext } from './FieldListSchemaContext';

function FieldCodeCell({ value, record }: { value: unknown; record: Record<string, any> }) {
  const { owners } = useFieldListSchemaContext();
  const owner = owners[`field:${record.code}`];

  return (
    <div className="flex flex-col gap-1">
      <Link
        to={`/meta/fields/${record.pid}`}
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

function FieldActionsCell({ record }: { record: Record<string, any> }) {
  const navigate = useNavigate();

  return (
    <div className="flex items-center gap-2">
      <button
        type="button"
        onClick={(event) => {
          event.stopPropagation();
          navigate(`/meta/fields/${record.pid}`);
        }}
        className="text-sm text-blue-600 hover:text-blue-700"
      >
        查看
      </button>
      <button
        type="button"
        onClick={(event) => {
          event.stopPropagation();
          navigate(`/meta/fields/${record.pid}/usage`);
        }}
        className="text-sm text-indigo-600 hover:text-indigo-700"
      >
        使用情况
      </button>
      <button
        type="button"
        onClick={(event) => {
          event.stopPropagation();
          navigate(`/meta/fields/${record.pid}/impact`);
        }}
        className="text-sm text-amber-600 hover:text-amber-700"
      >
        影响分析
      </button>
    </div>
  );
}

let registered = false;

export function ensureFieldListRenderersRegistered() {
  if (registered) {
    return;
  }

  cellRendererRegistry.register('meta_field_code', ({ value, record }) => (
    <FieldCodeCell value={value} record={record} />
  ));
  cellRendererRegistry.register('meta_field_actions', ({ record }) => (
    <FieldActionsCell record={record} />
  ));

  registered = true;
}
