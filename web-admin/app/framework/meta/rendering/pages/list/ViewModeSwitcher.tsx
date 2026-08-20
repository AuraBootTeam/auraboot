import React, { useCallback, useMemo, useRef } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { ViewType } from '~/framework/smart/types/savedView';
import { cn } from '~/utils/cn';

interface ViewModeDefinition {
  type: ViewType;
  labelKey: string;
  fallback: string;
  icon: React.ReactNode;
}

const MODE_DEFINITIONS: ViewModeDefinition[] = [
  {
    type: 'table',
    labelKey: 'common.saved_view_type_table',
    fallback: '列表',
    icon: (
      <svg viewBox="0 0 20 20" fill="none" aria-hidden="true" className="h-4 w-4">
        <path
          d="M4 5.5h12M4 10h12M4 14.5h12"
          stroke="currentColor"
          strokeWidth="1.6"
          strokeLinecap="round"
        />
      </svg>
    ),
  },
  {
    type: 'kanban',
    labelKey: 'common.saved_view_type_kanban',
    fallback: '看板',
    icon: (
      <svg viewBox="0 0 20 20" fill="none" aria-hidden="true" className="h-4 w-4">
        <rect x="3.25" y="4" width="4" height="12" rx="1" stroke="currentColor" strokeWidth="1.5" />
        <rect
          x="8.25"
          y="4"
          width="3.5"
          height="8"
          rx="1"
          stroke="currentColor"
          strokeWidth="1.5"
        />
        <rect
          x="12.75"
          y="4"
          width="4"
          height="10"
          rx="1"
          stroke="currentColor"
          strokeWidth="1.5"
        />
      </svg>
    ),
  },
];

export interface ViewModeSwitcherProps {
  activeType: ViewType;
  availableTypes: ViewType[];
  onChange: (type: ViewType) => void;
  className?: string;
}

export const ViewModeSwitcher: React.FC<ViewModeSwitcherProps> = ({
  activeType,
  availableTypes,
  onChange,
  className,
}) => {
  const { t } = useI18n();
  const buttonRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const modes = useMemo(
    () => MODE_DEFINITIONS.filter((definition) => availableTypes.includes(definition.type)),
    [availableTypes],
  );
  const groupLabel = t('common.saved_view_mode_switch', undefined, '切换商机视图');

  const moveFocus = useCallback(
    (currentIndex: number, direction: -1 | 1) => {
      if (modes.length === 0) return;
      const nextIndex = (currentIndex + direction + modes.length) % modes.length;
      const nextMode = modes[nextIndex];
      buttonRefs.current[nextIndex]?.focus();
      onChange(nextMode.type);
    },
    [modes, onChange],
  );

  if (modes.length < 2) return null;

  return (
    <div
      role="radiogroup"
      aria-label={groupLabel}
      className={cn(
        'border-border bg-subtle rounded-control inline-flex h-9 items-center border p-0.5',
        className,
      )}
      data-testid="list-view-mode-switcher"
    >
      {modes.map((mode, index) => {
        const active = mode.type === activeType;
        const label = t(mode.labelKey, undefined, mode.fallback);
        return (
          <button
            key={mode.type}
            ref={(node) => {
              buttonRefs.current[index] = node;
            }}
            type="button"
            role="radio"
            aria-checked={active}
            aria-label={label}
            tabIndex={active ? 0 : -1}
            data-testid={`list-view-mode-${mode.type}`}
            onClick={() => onChange(mode.type)}
            onKeyDown={(event) => {
              if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
                event.preventDefault();
                moveFocus(index, -1);
              } else if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
                event.preventDefault();
                moveFocus(index, 1);
              } else if (event.key === 'Home') {
                event.preventDefault();
                buttonRefs.current[0]?.focus();
                onChange(modes[0].type);
              } else if (event.key === 'End') {
                event.preventDefault();
                const lastIndex = modes.length - 1;
                buttonRefs.current[lastIndex]?.focus();
                onChange(modes[lastIndex].type);
              }
            }}
            className={cn(
              'inline-flex h-8 items-center gap-1.5 rounded-[5px] px-3 text-sm font-medium transition-colors',
              'focus:shadow-focus focus:outline-none',
              active
                ? 'bg-panel text-text shadow-sm'
                : 'text-text-2 hover:bg-hover hover:text-text',
            )}
          >
            {mode.icon}
            <span>{label}</span>
          </button>
        );
      })}
    </div>
  );
};

export default ViewModeSwitcher;
