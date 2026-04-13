import React, { useCallback } from 'react';
import type { ConditionGroup, ConditionRow as ConditionRowType, FieldOption } from './types';
import { ConditionRow } from './ConditionRow';

interface ConditionBuilderProps {
  group: ConditionGroup;
  fields: FieldOption[];
  onChange: (group: ConditionGroup) => void;
}

let idCounter = Date.now();
function nextId(): string {
  return `cond_${idCounter++}`;
}

export function ConditionBuilder({ group, fields, onChange }: ConditionBuilderProps) {
  const handleRowChange = useCallback(
    (index: number, updated: ConditionRowType) => {
      const newConditions = [...group.conditions];
      newConditions[index] = updated;
      onChange({ ...group, conditions: newConditions });
    },
    [group, onChange],
  );

  const handleRowDelete = useCallback(
    (index: number) => {
      const newConditions = group.conditions.filter((_, i) => i !== index);
      onChange({ ...group, conditions: newConditions });
    },
    [group, onChange],
  );

  const handleAdd = useCallback(() => {
    const newRow: ConditionRowType = {
      id: nextId(),
      field: '',
      operator: '===',
      value: '',
    };
    onChange({ ...group, conditions: [...group.conditions, newRow] });
  }, [group, onChange]);

  const handleToggleOperator = useCallback(() => {
    onChange({ ...group, operator: group.operator === 'and' ? 'or' : 'and' });
  }, [group, onChange]);

  return (
    <div data-testid="condition-builder">
      {group.conditions.map((row, index) => (
        <React.Fragment key={row.id}>
          <ConditionRow
            row={row}
            fields={fields}
            onChange={(updated) => handleRowChange(index, updated)}
            onDelete={() => handleRowDelete(index)}
          />
          {index < group.conditions.length - 1 && (
            <div className="flex items-center justify-end mb-1.5 pr-9">
              <button
                className="rounded bg-gray-100 px-2 py-0.5 text-[10px] font-medium text-gray-500 hover:bg-gray-200"
                onClick={handleToggleOperator}
                title="Click to toggle AND/OR"
                data-testid="condition-logic-toggle"
              >
                {group.operator === 'and' ? 'AND' : 'OR'}
              </button>
            </div>
          )}
        </React.Fragment>
      ))}

      <button
        className="mt-1 flex items-center gap-1 rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
        onClick={handleAdd}
        data-testid="condition-add"
      >
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <line x1="12" y1="5" x2="12" y2="19" />
          <line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        Add condition
      </button>
    </div>
  );
}
