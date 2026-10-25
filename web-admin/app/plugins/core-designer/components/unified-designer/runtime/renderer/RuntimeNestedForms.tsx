import type { DslBlockV3 } from '../../types';
import { DESIGNER_I18N } from '~/shared/designer';
import React from 'react';
import { RuntimeTitle } from './RuntimePrimitives';
import { RuntimeFormValueContext, RuntimeLocaleContext, useRuntimeText, type RuntimeBlockProps } from './runtimeContexts';
import { getRuntimeSelectOptions, normalizeRuntimeFieldComponent } from './runtimeFieldMetadata';
import {
  getRuntimeNestedFieldErrorKey,
  getRuntimeRepeaterFieldBlocks,
  getRuntimeRepeaterFieldKey,
  getRuntimeRepeaterRows,
  isRuntimeBlockVisible,
} from './runtimeFormLogic';
import { getBlockLabel, getSpanGridStyle } from './runtimePresentation';





export function RuntimeRepeater({ block }: RuntimeBlockProps) {
  const formContext = React.useContext(RuntimeFormValueContext);
  const locale = React.useContext(RuntimeLocaleContext);
  const isVisible = isRuntimeBlockVisible(block, formContext?.values);

  const repeaterKey = block.field || block.id;
  const fieldBlocks = getRuntimeRepeaterFieldBlocks(block);
  const [rows, setRows] = React.useState<Array<Record<string, unknown>>>(() =>
    getRuntimeRepeaterRows(block),
  );
  const hasSeededFormValue = React.useRef(false);

  React.useEffect(() => {
    if (!isVisible || !formContext || hasSeededFormValue.current) return;
    hasSeededFormValue.current = true;
    formContext.setValue(repeaterKey, rows);
  }, [formContext, isVisible, repeaterKey, rows]);

  const updateRows = React.useCallback(
    (resolveNextRows: (currentRows: Array<Record<string, unknown>>) => Array<Record<string, unknown>>) => {
      setRows((currentRows) => {
        const nextRows = resolveNextRows(currentRows).map((row) => ({ ...row }));
        formContext?.setValue(repeaterKey, nextRows);
        return nextRows;
      });
    },
    [formContext, repeaterKey],
  );

  const updateRowField = React.useCallback(
    (rowIndex: number, fieldBlock: DslBlockV3, value: unknown) => {
      const fieldKey = getRuntimeRepeaterFieldKey(fieldBlock);
      updateRows((currentRows) =>
        currentRows.map((row, index) =>
          index === rowIndex
            ? {
                ...row,
                [fieldKey]: value,
              }
            : row,
        ),
      );
    },
    [updateRows],
  );

  if (!isVisible) return null;

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-repeater-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <div className="flex items-center justify-between gap-3 border-b border-slate-100 pb-3">
        <div className="min-w-0">
          <div className="text-sm font-semibold text-slate-900">{getBlockLabel(block, locale)}</div>
          <div className="text-xs text-slate-500">{rows.length} row(s)</div>
        </div>
        <button
          type="button"
          className="rounded-md border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          data-testid={`runtime-repeater-add-${block.id}`}
          onClick={() => updateRows((currentRows) => [...currentRows, {}])}
        >
          Add row
        </button>
      </div>
      <div className="mt-3 space-y-3">
        {rows.map((row, rowIndex) => (
          <div
            key={rowIndex}
            className="rounded-md border border-slate-100 bg-slate-50 p-3"
            data-testid={`runtime-repeater-row-${block.id}-${rowIndex}`}
          >
            <div className="mb-2 flex items-center justify-between gap-3">
              <span className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                Row {rowIndex + 1}
              </span>
              <button
                type="button"
                className="rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-500 hover:text-red-600"
                data-testid={`runtime-repeater-remove-${block.id}-${rowIndex}`}
                onClick={() =>
                  updateRows((currentRows) => currentRows.filter((_, index) => index !== rowIndex))
                }
              >
                Remove
              </button>
            </div>
            <div className="grid grid-cols-12 gap-3">
              {fieldBlocks.map((fieldBlock) => (
                <RuntimeRepeaterField
                  key={fieldBlock.id}
                  block={block}
                  fieldBlock={fieldBlock}
                  row={row}
                  rowIndex={rowIndex}
                  error={
                    formContext?.errors[
                      getRuntimeNestedFieldErrorKey(
                        repeaterKey,
                        rowIndex,
                        getRuntimeRepeaterFieldKey(fieldBlock),
                      )
                    ]
                  }
                  onChange={updateRowField}
                />
              ))}
            </div>
          </div>
        ))}
        {rows.length ? null : (
          <div
            className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
            data-testid={`runtime-repeater-empty-${block.id}`}
          >
            No rows
          </div>
        )}
      </div>
    </section>
  );
}

function RuntimeRepeaterField({
  block,
  fieldBlock,
  row,
  rowIndex,
  onChange,
  error,
  controlIdPrefix = 'runtime-repeater-control',
  testIdPrefix = 'runtime-repeater-input',
}: {
  block: DslBlockV3;
  fieldBlock: DslBlockV3;
  row: Record<string, unknown>;
  rowIndex: number;
  onChange: (rowIndex: number, fieldBlock: DslBlockV3, value: unknown) => void;
  error?: string;
  controlIdPrefix?: string;
  testIdPrefix?: string;
}) {
  const t = useRuntimeText();
  const fieldKey = getRuntimeRepeaterFieldKey(fieldBlock);
  const component = normalizeRuntimeFieldComponent(fieldBlock.props?.component);
  const value = row[fieldKey];
  const stringValue = typeof value === 'string' || typeof value === 'number' ? String(value) : '';
  const readOnly = Boolean(fieldBlock.props?.readOnly);
  const controlId = `${controlIdPrefix}-${block.id}-${rowIndex}-${fieldBlock.id}`;
  const inputTestId = `${testIdPrefix}-${block.id}-${rowIndex}-${fieldBlock.id}`;
  const baseClass =
    'mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-sm text-slate-900 outline-none focus:border-blue-400';

  return (
    <label
      className="rounded-md border border-slate-100 bg-white px-3 py-2 text-sm"
      htmlFor={controlId}
      style={getSpanGridStyle(fieldBlock)}
    >
      <span className="block text-xs font-medium text-slate-600">{getBlockLabel(fieldBlock)}</span>
      {component === 'textarea' || component === 'rich-text' ? (
        <textarea
          id={controlId}
          className={`${baseClass} min-h-16 resize-y`}
          data-testid={inputTestId}
          readOnly={readOnly}
          value={stringValue}
          onChange={(event) => onChange(rowIndex, fieldBlock, event.target.value)}
        />
      ) : component === 'select' || component === 'picker' ? (
        <select
          id={controlId}
          className={baseClass}
          data-testid={inputTestId}
          disabled={readOnly}
          value={stringValue}
          onChange={(event) => onChange(rowIndex, fieldBlock, event.target.value)}
        >
          <option value="">{t(DESIGNER_I18N.unified.runtime.selectPlaceholder)}</option>
          {getRuntimeSelectOptions(fieldBlock.props?.options).map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      ) : component === 'checkbox' || component === 'switch' ? (
        <input
          id={controlId}
          type="checkbox"
          role={component === 'switch' ? 'switch' : undefined}
          className="mt-2 h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-400"
          data-testid={inputTestId}
          disabled={readOnly}
          checked={Boolean(value)}
          onChange={(event) => onChange(rowIndex, fieldBlock, event.target.checked)}
        />
      ) : component === 'radio' ? (
        <div className="mt-2 flex flex-wrap gap-3" data-testid={inputTestId} role="radiogroup">
          {getRuntimeSelectOptions(fieldBlock.props?.options).map((option) => (
            <span key={option.value} className="inline-flex items-center gap-1.5">
              <input
                id={`${controlId}-${option.value}`}
                type="radio"
                name={`${block.id}-${rowIndex}-${fieldKey}`}
                disabled={readOnly}
                checked={stringValue === option.value}
                value={option.value}
                onChange={(event) => onChange(rowIndex, fieldBlock, event.target.value)}
              />
              {option.label}
            </span>
          ))}
        </div>
      ) : (
        <input
          id={controlId}
          type={component === 'date' ? 'date' : component === 'number' ? 'number' : 'text'}
          className={baseClass}
          data-testid={inputTestId}
          readOnly={readOnly}
          value={stringValue}
          onChange={(event) => onChange(rowIndex, fieldBlock, event.target.value)}
        />
      )}
      {error ? (
        <span
          className="mt-1 block text-xs font-medium text-red-600"
          data-testid={`${testIdPrefix}-error-${block.id}-${rowIndex}-${fieldBlock.id}`}
        >
          {error}
        </span>
      ) : null}
    </label>
  );
}

export function RuntimeSubform({ block }: RuntimeBlockProps) {
  const formContext = React.useContext(RuntimeFormValueContext);
  const locale = React.useContext(RuntimeLocaleContext);
  const isVisible = isRuntimeBlockVisible(block, formContext?.values);

  const subformKey = block.field || block.id;
  const [rows, setRows] = React.useState<Array<Record<string, unknown>>>(() =>
    getRuntimeRepeaterRows(block),
  );
  const hasSeededFormValue = React.useRef(false);

  React.useEffect(() => {
    if (!isVisible || !formContext || hasSeededFormValue.current) return;
    hasSeededFormValue.current = true;
    formContext.setValue(subformKey, rows);
  }, [formContext, isVisible, rows, subformKey]);

  const updateRows = React.useCallback(
    (
      resolveNextRows: (
        currentRows: Array<Record<string, unknown>>,
      ) => Array<Record<string, unknown>>,
    ) => {
      setRows((currentRows) => {
        const nextRows = resolveNextRows(currentRows).map((row) => ({ ...row }));
        formContext?.setValue(subformKey, nextRows);
        return nextRows;
      });
    },
    [formContext, subformKey],
  );

  const updateRowField = React.useCallback(
    (rowIndex: number, fieldBlock: DslBlockV3, value: unknown) => {
      const fieldKey = getRuntimeRepeaterFieldKey(fieldBlock);
      updateRows((currentRows) =>
        currentRows.map((row, index) =>
          index === rowIndex
            ? {
                ...row,
                [fieldKey]: value,
              }
            : row,
        ),
      );
    },
    [updateRows],
  );

  if (!isVisible) return null;

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-subform-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <div className="flex items-center justify-between gap-3 border-b border-slate-100 pb-3">
        <div className="min-w-0">
          <div className="text-sm font-semibold text-slate-900">{getBlockLabel(block, locale)}</div>
          <div className="text-xs text-slate-500">{rows.length} row editor(s)</div>
        </div>
        <button
          type="button"
          className="rounded-md border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          data-testid={`runtime-subform-add-${block.id}`}
          onClick={() => updateRows((currentRows) => [...currentRows, {}])}
        >
          Add row
        </button>
      </div>
      <div className="mt-3 space-y-3">
        {rows.map((row, rowIndex) => (
          <div
            key={rowIndex}
            className="rounded-md border border-slate-100 bg-slate-50 p-3"
            data-testid={`runtime-subform-row-${block.id}-${rowIndex}`}
          >
            <div className="mb-2 flex items-center justify-between gap-3">
              <span className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                Row {rowIndex + 1}
              </span>
              <button
                type="button"
                className="rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-500 hover:text-red-600"
                data-testid={`runtime-subform-remove-${block.id}-${rowIndex}`}
                onClick={() =>
                  updateRows((currentRows) => currentRows.filter((_, index) => index !== rowIndex))
                }
              >
                Remove
              </button>
            </div>
            <div className="space-y-3">
              {block.blocks?.map((child) => (
                <RuntimeSubformRowBlock
                  key={child.id}
                  parentBlock={block}
                  block={child}
                  row={row}
                  rowIndex={rowIndex}
                  errors={formContext?.errors ?? {}}
                  onChange={updateRowField}
                />
              ))}
            </div>
          </div>
        ))}
        {rows.length ? null : (
          <div
            className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
            data-testid={`runtime-subform-empty-${block.id}`}
          >
            No rows
          </div>
        )}
      </div>
    </section>
  );
}

function RuntimeSubformRowBlock({
  parentBlock,
  block,
  row,
  rowIndex,
  errors,
  onChange,
}: {
  parentBlock: DslBlockV3;
  block: DslBlockV3;
  row: Record<string, unknown>;
  rowIndex: number;
  errors: Record<string, string>;
  onChange: (rowIndex: number, fieldBlock: DslBlockV3, value: unknown) => void;
}) {
  if (block.blockType === 'field') {
    const subformKey = parentBlock.field || parentBlock.id;
    const fieldKey = getRuntimeRepeaterFieldKey(block);
    return (
      <RuntimeRepeaterField
        block={parentBlock}
        fieldBlock={block}
        row={row}
        rowIndex={rowIndex}
        error={errors[getRuntimeNestedFieldErrorKey(subformKey, rowIndex, fieldKey)]}
        onChange={onChange}
        controlIdPrefix="runtime-subform-control"
        testIdPrefix="runtime-subform-input"
      />
    );
  }

  if (block.blockType === 'form-section' || block.blockType === 'detail-section') {
    return (
      <section
        className="rounded-md border border-slate-200 bg-white p-3"
        data-testid={`runtime-subform-section-${parentBlock.id}-${rowIndex}-${block.id}`}
        style={getSpanGridStyle(block)}
      >
        <RuntimeTitle block={block} />
        <div className="grid grid-cols-12 gap-3">
          {block.blocks?.map((child) => (
            <RuntimeSubformRowBlock
              key={child.id}
              parentBlock={parentBlock}
              block={child}
              row={row}
              rowIndex={rowIndex}
              errors={errors}
              onChange={onChange}
            />
          ))}
        </div>
      </section>
    );
  }

  if (block.blocks?.length) {
    return (
      <section
        className="rounded-md border border-slate-200 bg-white p-3"
        data-testid={`runtime-subform-block-${parentBlock.id}-${rowIndex}-${block.id}`}
      >
        <RuntimeTitle block={block} />
        <div className="grid grid-cols-12 gap-3">
          {block.blocks.map((child) => (
            <RuntimeSubformRowBlock
              key={child.id}
              parentBlock={parentBlock}
              block={child}
              row={row}
              rowIndex={rowIndex}
              errors={errors}
              onChange={onChange}
            />
          ))}
        </div>
      </section>
    );
  }

  return null;
}
