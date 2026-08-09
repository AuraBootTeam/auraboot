import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { readPath, resolveRuntimeValue, useRuntimeStateSubscription } from './workbenchBlockUtils';

export interface StageRailBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

type StageConfig = {
  value: string;
  label: string | Record<string, string>;
  tone?: 'blue' | 'green' | 'red' | 'gray';
};

const stateClass = {
  complete: {
    dot: 'border-accent bg-accent text-white',
    line: 'bg-accent',
    text: 'text-text',
  },
  current: {
    dot: 'border-accent bg-panel text-accent ring-4 ring-accent/15',
    line: 'bg-border-strong',
    text: 'text-accent',
  },
  upcoming: {
    dot: 'border-border-strong bg-panel text-text-3',
    line: 'bg-border-strong',
    text: 'text-text-3',
  },
} as const;

export const StageRailBlockRenderer: React.FC<StageRailBlockRendererProps> = ({
  block,
  runtime,
}) => {
  useRuntimeStateSubscription(runtime);
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const record =
    (block as any).record ||
    resolveRuntimeValue(runtime, (block as any).context) ||
    context.record ||
    {};
  const stageField = String((block as any).stageField || 'status');
  const currentValue = String(readPath(record, stageField) || '');
  const stages = (
    Array.isArray((block as any).stages) ? (block as any).stages : []
  ) as StageConfig[];
  const terminalStages = (
    Array.isArray((block as any).terminalStages) ? (block as any).terminalStages : []
  ) as StageConfig[];
  const currentIndex = stages.findIndex((stage) => stage.value === currentValue);
  const activeTerminalStage = terminalStages.find((stage) => stage.value === currentValue);
  const title = getLocalizedText(block.title || { 'zh-CN': '流程阶段', en: 'Stage' }, locale, t);

  if (stages.length === 0) return null;

  return (
    <section
      className="rounded-card border-border bg-panel border px-4 py-4 shadow-sm sm:px-5"
      data-testid={`stage-rail-${block.id || 'block'}`}
    >
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-text text-sm font-semibold">{title}</h3>
        {currentValue ? (
          <span className="rounded-pill bg-accent-weak text-accent px-2.5 py-1 text-xs font-medium">
            {getLocalizedText(
              [...stages, ...terminalStages].find((stage) => stage.value === currentValue)?.label ||
                currentValue,
              locale,
              t,
            )}
          </span>
        ) : null}
      </div>

      <div className="overflow-x-auto pb-1">
        <ol className="flex min-w-[680px] items-start" aria-label={title}>
          {stages.map((stage, index) => {
            const state =
              currentIndex === index ? 'current' : currentIndex > index ? 'complete' : 'upcoming';
            const classes = stateClass[state];
            return (
              <li
                key={stage.value}
                className="relative flex min-w-0 flex-1 flex-col items-center px-1 text-center"
                data-testid={`stage-rail-step-${stage.value}`}
                data-stage-state={state}
                aria-current={state === 'current' ? 'step' : undefined}
              >
                {index > 0 ? (
                  <span
                    aria-hidden="true"
                    className={`absolute top-3 right-1/2 h-0.5 w-full ${
                      currentIndex >= index ? stateClass.complete.line : classes.line
                    }`}
                  />
                ) : null}
                <span
                  className={`relative z-10 flex h-6 w-6 items-center justify-center rounded-full border-2 text-[11px] font-bold ${classes.dot}`}
                >
                  {state === 'complete' ? '✓' : index + 1}
                </span>
                <span className={`mt-2 text-xs font-medium whitespace-nowrap ${classes.text}`}>
                  {getLocalizedText(stage.label, locale, t)}
                </span>
              </li>
            );
          })}
        </ol>
      </div>

      {activeTerminalStage ? (
        <div className="border-border mt-3 flex flex-wrap justify-end gap-2 border-t pt-3">
          <span
            data-testid={`stage-rail-terminal-${activeTerminalStage.value}`}
            data-stage-state="current"
            className="rounded-pill bg-status-red-bg text-status-red ring-status-red/20 px-3 py-1 text-xs font-medium ring-2"
          >
            {getLocalizedText(activeTerminalStage.label, locale, t)}
          </span>
        </div>
      ) : null}
    </section>
  );
};

export default StageRailBlockRenderer;
