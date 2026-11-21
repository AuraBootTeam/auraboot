import { useEffect, useMemo, useReducer, type ReactNode } from 'react';
import type { BlockConfig, DetailBodyGroupConfig } from '../../../schemas/types';
import type { SchemaRuntime } from '../../../runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { resolveDetailBodyGroups } from '../../layout/pageUxPresentation';

interface Props {
  groups: DetailBodyGroupConfig[];
  blocks: BlockConfig[];
  runtime: SchemaRuntime;
  renderBlock: (block: BlockConfig) => ReactNode;
  hasPermission: (permission: string) => boolean;
  evaluateVisibleWhen: (expression: string | undefined) => boolean;
  locale: string;
  t: (key: string, ...args: any[]) => string;
}

/** Presentation only: every child retains the existing page runtime and form scope. */
export function DetailBodySections({ groups, blocks, runtime, renderBlock, hasPermission,
  evaluateVisibleWhen, locale, t }: Props) {
  const [, refresh] = useReducer((value: number) => value + 1, 0);
  useEffect(() => {
    const stop = runtime.subscribeContext(() => refresh());
    // Child effects can populate a draft before this subscription is installed.
    refresh();
    return stop;
  }, [runtime]);
  const units = useMemo(() => resolveDetailBodyGroups(groups, blocks), [groups, blocks]);
  return <div className="detail-body-sections" data-testid="detail-body-sections">
    {units.map(unit => {
      const visible = unit.blocks.filter(block => (!block.permissionCode || hasPermission(block.permissionCode))
        && evaluateVisibleWhen(block.visibleWhen));
      if (!visible.length) return null;
      if (!unit.group) return <div key={unit.key}>{visible.map(renderBlock)}</div>;
      const group = unit.group;
      const headingId = `detail-body-section-${group.id}`;
      return <section key={unit.key} className="detail-body-section"
        data-testid="detail-body-section" data-section-id={group.id} data-section-layout={group.layout || 'stack'}
        aria-labelledby={group.title ? headingId : undefined}>
        {group.title && <header className="detail-body-section__header">
          <h2 id={headingId}>{getLocalizedText(group.title, locale, t)}</h2>
          {group.description && <p>{getLocalizedText(group.description, locale, t)}</p>}
        </header>}
        <div className="detail-body-section__body">{visible.map(renderBlock)}</div>
      </section>;
    })}
  </div>;
}
