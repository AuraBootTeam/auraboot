import { DESIGNER_I18N } from '~/shared/designer';
import React from 'react';
import { RuntimeHelperDataStatus, useRuntimeHelperBlockData } from './RuntimeHelperData';
import { RuntimeTitle } from './RuntimePrimitives';
import { RuntimeFormValueContext, useRuntimeText, type RuntimeBlockProps } from './runtimeContexts';
import {
  getAiSuggestedFields,
  getBpmActions,
  getFieldHistoryEntries,
  getSpanGridStyle,
  getStringProp,
  getTimelineItems,
} from './runtimePresentation';





export function RuntimeAiFillBanner({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const formContext = React.useContext(RuntimeFormValueContext);
  const t = useRuntimeText();
  const [applied, setApplied] = React.useState(false);
  const { runtimeData, loading, runtimeError, permissionCode, permissionAllowed } =
    useRuntimeHelperBlockData(
      block,
      runtimeServices,
      pageContext,
      blockPath,
    );
  const description =
    getStringProp(runtimeData?.description) ||
    getStringProp(block.props?.description) ||
    t(DESIGNER_I18N.unified.runtime.aiReviewHint);
  const feedback =
    getStringProp(runtimeData?.feedback) ||
    getStringProp(block.props?.feedback) ||
    t(DESIGNER_I18N.unified.runtime.suggestionsApplied);
  const suggestedFields = getAiSuggestedFields(
    runtimeData?.suggestedFields ?? block.props?.suggestedFields ?? block.props?.fields,
  );
  const emptyText =
    getStringProp(runtimeData?.emptyText) ||
    getStringProp(block.props?.emptyText) ||
    t(DESIGNER_I18N.unified.runtime.noSuggestions);
  const applySuggestions = () => {
    suggestedFields.forEach((field) => {
      if (field.field) {
        formContext?.setValue(field.field, field.value);
      }
    });
    setApplied(true);
  };

  return (
    <section
      className="rounded-lg border border-blue-200 bg-blue-50 p-4"
      data-testid={`runtime-ai-fill-banner-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <RuntimeTitle block={block} />
          <p
            className="text-sm text-blue-800"
            data-testid={`runtime-ai-fill-description-${block.id}`}
          >
            {description}
          </p>
          <RuntimeHelperDataStatus
            blockId={block.id}
            data={runtimeData}
            loading={loading}
            error={runtimeError}
            permissionCode={permissionCode}
            permissionAllowed={permissionAllowed}
          />
        </div>
        <button
          type="button"
          className="rounded-md border border-blue-300 bg-white px-3 py-1.5 text-sm font-medium text-blue-700 hover:bg-blue-100"
          data-testid={`runtime-ai-fill-apply-${block.id}`}
          onClick={applySuggestions}
        >
          Apply suggestions
        </button>
      </div>
      {suggestedFields.length ? (
        <div className="mt-3 grid gap-2 sm:grid-cols-2">
          {suggestedFields.map((field, index) => (
            <div
              key={`${field.field}-${index}`}
              className="rounded-md border border-blue-100 bg-white px-3 py-2 text-sm"
              data-testid={`runtime-ai-fill-field-${block.id}-${index}`}
            >
              <div className="font-medium text-slate-800">{field.label || field.field}</div>
              <div className="mt-1 text-slate-500">{field.value}</div>
            </div>
          ))}
        </div>
      ) : !loading && !runtimeError ? (
        <div
          className="mt-3 rounded-md border border-dashed border-blue-200 bg-white/70 px-3 py-3 text-sm text-blue-500"
          data-testid={`runtime-ai-fill-empty-${block.id}`}
        >
          {emptyText}
        </div>
      ) : null}
      {applied ? (
        <div className="mt-3 text-sm text-blue-700" data-testid={`runtime-ai-fill-status-${block.id}`}>
          {feedback}
        </div>
      ) : null}
    </section>
  );
}

export function RuntimeBpmPanel({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const t = useRuntimeText();
  const { runtimeData, loading, runtimeError, permissionCode, permissionAllowed } =
    useRuntimeHelperBlockData(
      block,
      runtimeServices,
      pageContext,
      blockPath,
    );
  const hasRuntimeData = runtimeData !== null;
  const status =
    getStringProp(runtimeData?.status) ||
    (hasRuntimeData ? '' : getStringProp(block.props?.status) || 'draft');
  const description =
    getStringProp(runtimeData?.description) ||
    (hasRuntimeData ? '' : getStringProp(block.props?.description));
  const assignee =
    getStringProp(runtimeData?.assignee) ||
    (hasRuntimeData ? '' : getStringProp(block.props?.assignee));
  const dueAt =
    getStringProp(runtimeData?.dueAt) || (hasRuntimeData ? '' : getStringProp(block.props?.dueAt));
  const actions = getBpmActions(runtimeData?.actions ?? block.props?.actions);
  const emptyText =
    getStringProp(runtimeData?.emptyText) ||
    getStringProp(block.props?.emptyText) ||
    t(DESIGNER_I18N.unified.runtime.noWorkflowTasks);
  const hasBpmContent = Boolean(status || description || assignee || dueAt || actions.length);

  return (
    <section
      className="rounded-lg border border-indigo-200 bg-white p-4"
      data-testid={`runtime-workflow-panel-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <RuntimeTitle block={block} />
          {description ? <p className="text-sm text-slate-500">{description}</p> : null}
          <RuntimeHelperDataStatus
            blockId={block.id}
            data={runtimeData}
            loading={loading}
            error={runtimeError}
            permissionCode={permissionCode}
            permissionAllowed={permissionAllowed}
          />
        </div>
        {status ? (
          <span
            className="rounded-full bg-indigo-50 px-2.5 py-1 text-xs font-semibold uppercase tracking-wide text-indigo-700"
            data-testid={`runtime-bpm-status-${block.id}`}
          >
            {status}
          </span>
        ) : null}
      </div>
      <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-2">
        {assignee ? (
          <div data-testid={`runtime-bpm-assignee-${block.id}`}>
            <dt className="text-xs font-medium uppercase text-slate-400">
              {t(DESIGNER_I18N.unified.runtime.assignee)}
            </dt>
            <dd className="text-slate-800">{assignee}</dd>
          </div>
        ) : null}
        {dueAt ? (
          <div data-testid={`runtime-bpm-due-${block.id}`}>
            <dt className="text-xs font-medium uppercase text-slate-400">
              {t(DESIGNER_I18N.unified.runtime.due)}
            </dt>
            <dd className="text-slate-800">{dueAt}</dd>
          </div>
        ) : null}
      </dl>
      {actions.length ? (
        <div className="mt-4 flex flex-wrap gap-2">
          {actions.map((action, index) => (
            <button
              key={`${action.label}-${index}`}
              type="button"
              className="rounded-md border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-700"
              data-testid={`runtime-bpm-action-${block.id}-${index}`}
            >
              {action.label}
            </button>
          ))}
        </div>
      ) : null}
      {!hasBpmContent && !loading && !runtimeError ? (
        <div
          className="mt-3 rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-bpm-empty-${block.id}`}
        >
          {emptyText}
        </div>
      ) : null}
    </section>
  );
}

export function RuntimeActivityTimeline({
  block,
  runtimeServices,
  pageContext,
  blockPath,
}: RuntimeBlockProps) {
  const t = useRuntimeText();
  const { runtimeData, loading, runtimeError, permissionCode, permissionAllowed } =
    useRuntimeHelperBlockData(
      block,
      runtimeServices,
      pageContext,
      blockPath,
    );
  const items = getTimelineItems(runtimeData?.items ?? block.props?.items);
  const emptyText =
    getStringProp(runtimeData?.emptyText) ||
    getStringProp(block.props?.emptyText) ||
    t(DESIGNER_I18N.unified.runtime.noActivity);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-activity-timeline-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      <RuntimeHelperDataStatus
        blockId={block.id}
        data={runtimeData}
        loading={loading}
        error={runtimeError}
        permissionCode={permissionCode}
        permissionAllowed={permissionAllowed}
      />
      {items.length ? (
        <ol className="space-y-3">
          {items.map((item, index) => (
            <li
              key={`${item.action}-${index}`}
              className="border-l-2 border-slate-200 pl-3"
              data-testid={`runtime-activity-item-${block.id}-${index}`}
            >
              <div className="text-sm font-medium text-slate-800">
                {item.actor ? `${item.actor} · ` : ''}
                {item.action}
              </div>
              {item.time ? <div className="text-xs text-slate-400">{item.time}</div> : null}
              {item.description ? (
                <div className="mt-1 text-sm text-slate-500">{item.description}</div>
              ) : null}
            </li>
          ))}
        </ol>
      ) : (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-activity-empty-${block.id}`}
        >
          {emptyText}
        </div>
      )}
    </section>
  );
}

export function RuntimeFieldHistory({
  block,
  runtimeServices,
  pageContext,
  blockPath,
}: RuntimeBlockProps) {
  const t = useRuntimeText();
  const { runtimeData, loading, runtimeError, permissionCode, permissionAllowed } =
    useRuntimeHelperBlockData(
      block,
      runtimeServices,
      pageContext,
      blockPath,
    );
  const entries = getFieldHistoryEntries(runtimeData?.entries ?? block.props?.entries);
  const emptyText =
    getStringProp(runtimeData?.emptyText) ||
    getStringProp(block.props?.emptyText) ||
    t(DESIGNER_I18N.unified.runtime.noFieldChanges);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-field-history-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      <RuntimeHelperDataStatus
        blockId={block.id}
        data={runtimeData}
        loading={loading}
        error={runtimeError}
        permissionCode={permissionCode}
        permissionAllowed={permissionAllowed}
      />
      {entries.length ? (
        <div className="overflow-hidden rounded-md border border-slate-200">
          <table className="w-full text-left text-sm">
            <thead className="bg-slate-50 text-xs uppercase text-slate-500">
              <tr>
                <th className="px-3 py-2">{t(DESIGNER_I18N.unified.runtime.field)}</th>
                <th className="px-3 py-2">{t(DESIGNER_I18N.unified.runtime.from)}</th>
                <th className="px-3 py-2">{t(DESIGNER_I18N.unified.runtime.to)}</th>
                <th className="px-3 py-2">{t(DESIGNER_I18N.unified.runtime.by)}</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry, index) => (
                <tr
                  key={`${entry.field}-${index}`}
                  className="border-t border-slate-100"
                  data-testid={`runtime-field-history-entry-${block.id}-${index}`}
                >
                  <td className="px-3 py-2 font-medium text-slate-800">{entry.field}</td>
                  <td className="px-3 py-2 text-slate-500">{entry.from}</td>
                  <td className="px-3 py-2 text-slate-800">{entry.to}</td>
                  <td className="px-3 py-2 text-slate-500">{entry.changedBy}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-field-history-empty-${block.id}`}
        >
          {emptyText}
        </div>
      )}
    </section>
  );
}
