import React, { useState, useCallback } from 'react';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

interface ButtonConfig {
  code: string;
  action?: string;
  icon?: string;
  danger?: boolean;
  primary?: boolean;
  visibleWhen?: string;
  navigateTo?: string;
  confirmMessageKey?: string;
  apiAction?: {
    endpoint: string;
    method?: string;
    successMessage?: string | { 'en-US'?: string; 'zh-CN'?: string };
  };
  [key: string]: unknown;
}

export interface ActionButtonEditorProps {
  buttons: ButtonConfig[];
  onChange: (buttons: ButtonConfig[]) => void;
  readonly?: boolean;
}

type ActionType = 'navigate' | 'api' | 'custom';

function getActionType(btn: ButtonConfig): ActionType {
  if (btn.apiAction) return 'api';
  if (btn.navigateTo) return 'navigate';
  return 'custom';
}

function getActionBadge(type: ActionType, danger?: boolean) {
  if (danger) return { label: 'danger', className: 'bg-red-100 text-red-600' };
  if (type === 'navigate') return { label: 'navigate', className: 'bg-blue-100 text-blue-600' };
  if (type === 'api') return { label: 'API call', className: 'bg-green-100 text-green-600' };
  return { label: 'custom', className: 'bg-gray-100 text-gray-600' };
}

interface SortableButtonCardProps {
  btn: ButtonConfig;
  index: number;
  isExpanded: boolean;
  readonly?: boolean;
  onToggle: () => void;
  onRemove: () => void;
  onUpdate: (updates: Partial<ButtonConfig>) => void;
  onChangeActionType: (type: ActionType) => void;
}

function SortableButtonCard({
  btn,
  index,
  isExpanded,
  readonly,
  onToggle,
  onRemove,
  onUpdate,
  onChangeActionType,
}: SortableButtonCardProps) {
  const sortableId = `${btn.code}-${index}`;
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: sortableId,
    disabled: readonly,
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
    zIndex: isDragging ? 1 : undefined,
  };

  const actionType = getActionType(btn);
  const badge = getActionBadge(actionType, btn.danger);

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`overflow-hidden rounded-md border ${
        isExpanded ? 'border-blue-500' : 'border-gray-200'
      } ${isDragging ? 'shadow-md' : ''}`}
      data-testid={`action-btn-${btn.code}`}
    >
      {/* Header */}
      <div
        className={`flex cursor-pointer items-center justify-between px-2 py-1.5 ${
          isExpanded ? 'bg-blue-50' : 'bg-white hover:bg-gray-50'
        }`}
        onClick={onToggle}
      >
        <div className="flex items-center gap-1.5">
          {/* Drag handle */}
          <span
            {...attributes}
            {...listeners}
            className={`cursor-grab text-[10px] text-gray-300 hover:text-gray-500 active:cursor-grabbing ${
              readonly ? 'cursor-not-allowed opacity-30' : ''
            }`}
            title="Drag to reorder"
            onClick={(e) => e.stopPropagation()}
          >
            ⠿
          </span>
          <span className="text-[10px] text-gray-400">{isExpanded ? '▼' : '▶'}</span>
          <span className="text-xs font-medium">{btn.code}</span>
          <span className={`rounded px-1 py-0.5 text-[9px] ${badge.className}`}>
            {badge.label}
          </span>
        </div>
        <button
          onClick={(e) => {
            e.stopPropagation();
            onRemove();
          }}
          className="text-xs text-red-400 hover:text-red-600"
          disabled={readonly}
        >
          ×
        </button>
      </div>

      {/* Expanded content */}
      {isExpanded && (
        <div className="space-y-2 border-t px-2 py-2 text-xs">
          {/* Code */}
          <div>
            <label className="text-[10px] font-semibold text-gray-500">Code</label>
            <input
              className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
              value={btn.code}
              onChange={(e) => onUpdate({ code: e.target.value })}
              disabled={readonly}
            />
          </div>

          {/* Action Type */}
          <div>
            <label className="text-[10px] font-semibold text-gray-500">Action Type</label>
            <div className="mt-0.5 flex gap-0.5">
              {(['navigate', 'api', 'custom'] as ActionType[]).map((t) => (
                <button
                  key={t}
                  className={`rounded px-2 py-0.5 text-[10px] ${
                    actionType === t ? 'bg-blue-500 text-white' : 'bg-gray-100 text-gray-600'
                  }`}
                  onClick={() => onChangeActionType(t)}
                  disabled={readonly}
                >
                  {t === 'api' ? 'API Call' : t.charAt(0).toUpperCase() + t.slice(1)}
                </button>
              ))}
            </div>
          </div>

          {/* Icon */}
          <div>
            <label className="text-[10px] font-semibold text-gray-500">Icon</label>
            <input
              className="mt-0.5 w-full rounded border px-1.5 py-1 text-xs"
              value={btn.icon || ''}
              onChange={(e) => onUpdate({ icon: e.target.value || undefined })}
              placeholder="e.g. Edit, Plus, Trash"
              disabled={readonly}
            />
          </div>

          {/* Navigate fields */}
          {actionType === 'navigate' && (
            <div>
              <label className="text-[10px] font-semibold text-gray-500">Navigate To</label>
              <input
                className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
                value={btn.navigateTo || ''}
                onChange={(e) => onUpdate({ navigateTo: e.target.value })}
                placeholder="/path/{pid}"
                disabled={readonly}
              />
            </div>
          )}

          {/* API Call fields */}
          {actionType === 'api' && (
            <div className="space-y-1.5 rounded border border-green-200 bg-green-50 p-1.5">
              <div className="grid grid-cols-2 gap-1">
                <div>
                  <label className="text-[10px] font-semibold text-gray-500">Method</label>
                  <select
                    className="mt-0.5 w-full rounded border bg-white px-1 py-1 text-xs"
                    value={btn.apiAction?.method || 'post'}
                    onChange={(e) =>
                      onUpdate({
                        apiAction: { ...btn.apiAction!, method: e.target.value },
                      })
                    }
                    disabled={readonly}
                  >
                    <option>POST</option>
                    <option>PUT</option>
                    <option>DELETE</option>
                  </select>
                </div>
                <div>
                  <label className="text-[10px] font-semibold text-gray-500">Success Msg</label>
                  <input
                    className="mt-0.5 w-full rounded border px-1 py-1 text-xs"
                    value={
                      typeof btn.apiAction?.successMessage === 'string'
                        ? btn.apiAction.successMessage
                        : btn.apiAction?.successMessage?.['zh-CN'] || ''
                    }
                    onChange={(e) =>
                      onUpdate({
                        apiAction: {
                          ...btn.apiAction!,
                          successMessage: e.target.value,
                        },
                      })
                    }
                    disabled={readonly}
                  />
                </div>
              </div>
              <div>
                <label className="text-[10px] font-semibold text-gray-500">Endpoint</label>
                <input
                  className="mt-0.5 w-full rounded border px-1 py-1 font-mono text-xs"
                  value={btn.apiAction?.endpoint || ''}
                  onChange={(e) =>
                    onUpdate({
                      apiAction: { ...btn.apiAction!, endpoint: e.target.value },
                    })
                  }
                  placeholder="/api/.../{pid}/action"
                  disabled={readonly}
                />
              </div>
            </div>
          )}

          {/* Common fields */}
          <div>
            <label className="text-[10px] font-semibold text-gray-500">Visible When</label>
            <input
              className="mt-0.5 w-full rounded border px-1.5 py-1 font-mono text-xs"
              value={btn.visibleWhen || ''}
              onChange={(e) => onUpdate({ visibleWhen: e.target.value || undefined })}
              placeholder="row.status === 'draft'"
              disabled={readonly}
            />
          </div>

          <div className="flex gap-3">
            <label className="flex items-center gap-1">
              <input
                type="checkbox"
                checked={!!btn.confirmMessageKey}
                onChange={(e) =>
                  onUpdate({
                    confirmMessageKey: e.target.checked ? 'confirm.action' : undefined,
                  })
                }
                disabled={readonly}
              />
              <span className="text-[10px]">Confirm</span>
            </label>
            <label className="flex items-center gap-1 text-red-600">
              <input
                type="checkbox"
                checked={!!btn.danger}
                onChange={(e) => onUpdate({ danger: e.target.checked || undefined })}
                disabled={readonly}
              />
              <span className="text-[10px]">Danger</span>
            </label>
          </div>
        </div>
      )}
    </div>
  );
}

export function ActionButtonEditor({ buttons, onChange, readonly }: ActionButtonEditorProps) {
  const [expandedIndex, setExpandedIndex] = useState<number | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const sortableIds = buttons.map((btn, index) => `${btn.code}-${index}`);

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      if (!over || active.id === over.id) return;

      const oldIndex = sortableIds.indexOf(active.id as string);
      const newIndex = sortableIds.indexOf(over.id as string);
      if (oldIndex === -1 || newIndex === -1) return;

      onChange(arrayMove(buttons, oldIndex, newIndex));

      // Keep the expanded card tracking the moved item
      setExpandedIndex((prev) => {
        if (prev === oldIndex) return newIndex;
        if (oldIndex < newIndex) {
          // item moved down: items between shift up by 1
          if (prev !== null && prev > oldIndex && prev <= newIndex) return prev - 1;
        } else {
          // item moved up: items between shift down by 1
          if (prev !== null && prev >= newIndex && prev < oldIndex) return prev + 1;
        }
        return prev;
      });
    },
    [buttons, onChange, sortableIds]
  );

  const updateButton = useCallback(
    (index: number, updates: Partial<ButtonConfig>) => {
      const updated = [...buttons];
      updated[index] = { ...updated[index], ...updates };
      onChange(updated);
    },
    [buttons, onChange]
  );

  const removeButton = useCallback(
    (index: number) => {
      onChange(buttons.filter((_, i) => i !== index));
      setExpandedIndex(null);
    },
    [buttons, onChange]
  );

  const addButton = useCallback(() => {
    onChange([...buttons, { code: `action_${Date.now()}`, action: 'custom' }]);
    setExpandedIndex(buttons.length);
  }, [buttons, onChange]);

  const changeActionType = useCallback(
    (index: number, type: ActionType) => {
      const btn = { ...buttons[index] };
      delete btn.apiAction;
      delete btn.navigateTo;

      if (type === 'api') {
        btn.apiAction = { endpoint: '', method: 'post' };
      } else if (type === 'navigate') {
        btn.navigateTo = '';
      }

      const updated = [...buttons];
      updated[index] = btn;
      onChange(updated);
    },
    [buttons, onChange]
  );

  return (
    <div className="space-y-1" data-testid="action-btn-editor">
      <div className="mb-2 text-xs font-medium text-gray-600">Action Buttons</div>

      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <SortableContext items={sortableIds} strategy={verticalListSortingStrategy}>
          {buttons.map((btn, index) => (
            <SortableButtonCard
              key={`${btn.code}-${index}`}
              btn={btn}
              index={index}
              isExpanded={expandedIndex === index}
              readonly={readonly}
              onToggle={() => setExpandedIndex(expandedIndex === index ? null : index)}
              onRemove={() => removeButton(index)}
              onUpdate={(updates) => updateButton(index, updates)}
              onChangeActionType={(type) => changeActionType(index, type)}
            />
          ))}
        </SortableContext>
      </DndContext>

      <button
        onClick={addButton}
        className="w-full rounded border border-dashed py-1 text-xs text-blue-500"
        disabled={readonly}
      >
        + Add Action
      </button>
    </div>
  );
}
