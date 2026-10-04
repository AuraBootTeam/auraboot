import React, { useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import TEXT from './ActionPhaseEditor.i18n.json';
import { PHASE_TYPE_INFO, type ActionPhase, type ActionPhaseType } from './types';

interface ActionPhaseEditorProps {
  category: 'pre' | 'validate' | 'execute' | 'post';
  phases: ActionPhase[];
  onAdd: (type: ActionPhaseType) => void;
  onRemove: (phaseId: string) => void;
  onUpdate: (phaseId: string, updates: Partial<ActionPhase>) => void;
  onMove: (phaseId: string, direction: 'up' | 'down') => void;
  readonly?: boolean;
}

/**
 * Action Phase Editor - manages phases within a single category.
 * Shows an ordered list of phases with add/remove/reorder controls.
 */
export const ActionPhaseEditor: React.FC<ActionPhaseEditorProps> = ({
  category,
  phases,
  onAdd,
  onRemove,
  onUpdate,
  onMove,
  readonly = false,
}) => {
  const [showAddMenu, setShowAddMenu] = useState(false);
  const { locale } = useI18n();
  const text = (key: keyof typeof TEXT) => getLocalizedText(TEXT[key], locale);

  // Filter phase types that belong to this category
  const availableTypes = Object.entries(PHASE_TYPE_INFO)
    .filter(([_, info]) => info.category === category)
    .map(([type]) => type as ActionPhaseType);

  return (
    <div className="overflow-hidden rounded-md border border-gray-200">
      {/* Category header */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-3 py-2">
        <div>
          <span className="text-xs font-medium text-gray-700">{text(`${category}Label`)}</span>
          <span className="ml-2 text-xs text-gray-400">{text(`${category}Description`)}</span>
        </div>
        {!readonly && (
          <div className="relative">
            <button
              onClick={() => setShowAddMenu(!showAddMenu)}
              className="rounded p-1 text-gray-400 hover:bg-blue-50 hover:text-blue-600"
              title={text('add')}
            >
              <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 4v16m8-8H4"
                />
              </svg>
            </button>
            {showAddMenu && (
              <div className="absolute top-full right-0 z-10 mt-1 min-w-[140px] rounded-md border border-gray-200 bg-white py-1 shadow-lg">
                {availableTypes.map((type) => {
                  const info = PHASE_TYPE_INFO[type];
                  return (
                    <button
                      key={type}
                      onClick={() => {
                        onAdd(type);
                        setShowAddMenu(false);
                      }}
                      className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-xs hover:bg-gray-50"
                    >
                      <span>{info.icon}</span>
                      <span>{text(type)}</span>
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Phase list */}
      <div className="divide-y divide-gray-100">
        {phases.map((phase, idx) => (
          <PhaseItem
            key={phase.id}
            phase={phase}
            index={idx}
            total={phases.length}
            readonly={readonly}
            onRemove={() => onRemove(phase.id)}
            onUpdate={(updates) => onUpdate(phase.id, updates)}
            onMoveUp={() => onMove(phase.id, 'up')}
            onMoveDown={() => onMove(phase.id, 'down')}
          />
        ))}
        {phases.length === 0 && (
          <div className="px-3 py-2 text-center text-xs text-gray-400">{text('empty')}</div>
        )}
      </div>
    </div>
  );
};

interface PhaseItemProps {
  phase: ActionPhase;
  index: number;
  total: number;
  readonly: boolean;
  onRemove: () => void;
  onUpdate: (updates: Partial<ActionPhase>) => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
}

const PhaseItem: React.FC<PhaseItemProps> = ({
  phase,
  index,
  total,
  readonly,
  onRemove,
  onUpdate,
  onMoveUp,
  onMoveDown,
}) => {
  const [expanded, setExpanded] = useState(false);
  const info = PHASE_TYPE_INFO[phase.type];
  const { locale } = useI18n();
  const text = (key: keyof typeof TEXT) => getLocalizedText(TEXT[key], locale);

  return (
    <div className={`${phase.enabled === false ? 'opacity-50' : ''}`}>
      <div className="flex items-center justify-between px-3 py-1.5 hover:bg-gray-50">
        <div className="flex items-center gap-2">
          <button
            aria-label={text(expanded ? 'collapse' : 'expand')}
            onClick={() => setExpanded(!expanded)}
            className="p-0.5 text-gray-400 hover:text-gray-600"
          >
            <svg
              className={`h-3 w-3 transition-transform ${expanded ? 'rotate-90' : ''}`}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </button>
          <span className="text-xs">{info.icon}</span>
          <span className="text-xs text-gray-700">{phase.label || text(phase.type)}</span>
          {phase.onError === 'stop' && (
            <span className="text-[10px] text-red-400" title={text('stopOnFailure')}>
              ⛔
            </span>
          )}
        </div>

        {!readonly && (
          <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 hover:opacity-100">
            {index > 0 && (
              <button
                aria-label={text('moveUp')}
                onClick={onMoveUp}
                className="p-0.5 text-xs text-gray-400 hover:text-gray-600"
              >
                ↑
              </button>
            )}
            {index < total - 1 && (
              <button
                aria-label={text('moveDown')}
                onClick={onMoveDown}
                className="p-0.5 text-xs text-gray-400 hover:text-gray-600"
              >
                ↓
              </button>
            )}
            <button aria-label={text('remove')} onClick={onRemove} className="p-0.5 text-xs text-gray-400 hover:text-red-500">
              ×
            </button>
          </div>
        )}
      </div>

      {/* Expanded config editor */}
      {expanded && (
        <div className="ml-6 px-3 pt-1 pb-2">
          <PhaseConfigEditor phase={phase} readonly={readonly} onUpdate={onUpdate} />
        </div>
      )}
    </div>
  );
};

const PhaseConfigEditor: React.FC<{
  phase: ActionPhase;
  readonly: boolean;
  onUpdate: (updates: Partial<ActionPhase>) => void;
}> = ({ phase, readonly, onUpdate }) => {
  const { locale } = useI18n();
  const text = (key: keyof typeof TEXT) => getLocalizedText(TEXT[key], locale);
  const handleConfigChange = (key: string, value: any) => {
    onUpdate({ config: { ...phase.config, [key]: value } });
  };

  return (
    <div className="space-y-1.5">
      {/* Common: onError */}
      <div className="flex items-center gap-2">
        <label className="w-16 text-[10px] text-gray-500">{text('errorPolicy')}</label>
        <select
          value={phase.onError || 'stop'}
          onChange={(e) => onUpdate({ onError: e.target.value as any })}
          className="rounded border border-gray-200 px-1.5 py-0.5 text-xs"
          disabled={readonly}
        >
          <option value="stop">{text('stop')}</option>
          <option value="continue">{text('continue')}</option>
          <option value="retry">{text('retry')}</option>
        </select>
      </div>

      {/* Type-specific config fields */}
      {phase.type === 'clientValidate' && (
        <>
          <ConfigField
            label={text('expression')}
            value={phase.config.expression}
            onChange={(v) => handleConfigChange('expression', v)}
            readonly={readonly}
          />
          <ConfigField
            label={text('errorMessage')}
            value={phase.config.message}
            onChange={(v) => handleConfigChange('message', v)}
            readonly={readonly}
          />
        </>
      )}
      {phase.type === 'apiCall' && (
        <>
          <ConfigField
            label={text('endpoint')}
            value={phase.config.endpoint}
            onChange={(v) => handleConfigChange('endpoint', v)}
            readonly={readonly}
          />
          <ConfigField
            label={text('method')}
            value={phase.config.method}
            onChange={(v) => handleConfigChange('method', v)}
            readonly={readonly}
          />
        </>
      )}
      {phase.type === 'navigate' && (
        <ConfigField
          label={text('path')}
          value={phase.config.path}
          onChange={(v) => handleConfigChange('path', v)}
          readonly={readonly}
        />
      )}
      {phase.type === 'notify' && (
        <>
          <ConfigField
            label={text('message')}
            value={phase.config.message}
            onChange={(v) => handleConfigChange('message', v)}
            readonly={readonly}
          />
          <div className="flex items-center gap-2">
            <label className="w-16 text-[10px] text-gray-500">{text('type')}</label>
            <select
              value={phase.config.type || 'success'}
              onChange={(e) => handleConfigChange('type', e.target.value)}
              className="rounded border border-gray-200 px-1.5 py-0.5 text-xs"
              disabled={readonly}
            >
              <option value="success">{text('success')}</option>
              <option value="info">{text('info')}</option>
              <option value="warning">{text('warning')}</option>
              <option value="error">{text('error')}</option>
            </select>
          </div>
        </>
      )}
      {phase.type === 'refresh' && (
        <ConfigField
          label={text('target')}
          value={phase.config.target}
          onChange={(v) => handleConfigChange('target', v)}
          readonly={readonly}
        />
      )}
      {phase.type === 'openModal' && (
        <ConfigField
          label={text('modalId')}
          value={phase.config.modalId}
          onChange={(v) => handleConfigChange('modalId', v)}
          readonly={readonly}
        />
      )}
      {phase.type === 'custom' && (
        <ConfigField
          label={text('handler')}
          value={phase.config.handler}
          onChange={(v) => handleConfigChange('handler', v)}
          readonly={readonly}
        />
      )}
    </div>
  );
};

const ConfigField: React.FC<{
  label: string;
  value: string;
  onChange: (value: string) => void;
  readonly: boolean;
}> = ({ label, value, onChange, readonly }) => (
  <div className="flex items-center gap-2">
    <label className="w-16 shrink-0 text-[10px] text-gray-500">{label}</label>
    <input
      type="text"
      aria-label={label}
      value={value || ''}
      onChange={(e) => onChange(e.target.value)}
      className="flex-1 rounded border border-gray-200 px-1.5 py-0.5 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
      disabled={readonly}
    />
  </div>
);
