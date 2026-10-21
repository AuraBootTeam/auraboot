import type { DslBlockV3, ModelFieldDefinition } from '../../types';
import { buildPreviewFieldConfig } from '../platformFieldPreview';
import {
  normalizeRuntimeExecutionError,
  type RuntimeExecutionIssue,
  type RuntimeExecutionServices,
} from '../runtimeExecution';
import { ControlledFieldRenderer } from '~/framework/meta/rendering/ControlledFieldRenderer';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { DESIGNER_I18N } from '~/shared/designer';
import React from 'react';
import { RuntimePermissionNotice } from './RuntimePrimitives';
import {
  RuntimeFormValueContext,
  RuntimeListSelectionContext,
  RuntimeLocaleContext,
  RuntimeModelFieldsContext,
  RuntimePermissionContext,
  useRuntimeText,
  type RuntimeBlockProps,
  type RuntimeFieldComponent,
  type RuntimeFormContextValue,
  type RuntimePageContext,
  type RuntimeSelectionContextValue,
  type RuntimeSelectOption,
} from './runtimeContexts';
import {
  getRuntimeFieldControlId,
  getRuntimePickerMeta,
  getRuntimeSelectOptions,
  hasExecutablePickerDataSource,
  isDesignerRuntimeOnlyComponent,
  normalizeRuntimeFieldComponent,
  normalizeRuntimePickerOptions,
} from './runtimeFieldMetadata';
import { getRuntimeNumber, isRuntimeBlockVisible } from './runtimeFormLogic';
import {
  getBlockLabel,
  getBooleanProp,
  getRuntimeBlockContext,
  getRuntimePermissionCode,
  getSpanGridStyle,
  getStringProp,
  isRuntimeBlockPermissionAllowed,
} from './runtimePresentation';





export function RuntimeField({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const formContext = React.useContext(RuntimeFormValueContext);
  const listContext = React.useContext(RuntimeListSelectionContext);
  const hasPermission = React.useContext(RuntimePermissionContext);
  const modelFields = React.useContext(RuntimeModelFieldsContext);
  const t = useRuntimeText();
  if (!isRuntimeBlockVisible(block, formContext?.values)) return null;

  const permissionCode = getRuntimePermissionCode(block);
  const permissionAllowed = isRuntimeBlockPermissionAllowed(block, hasPermission);
  if (!permissionAllowed) {
    return (
      <div
        className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm"
        data-permission-allowed="false"
        data-permission-code={permissionCode}
        data-testid={`runtime-field-${block.id}`}
        style={getSpanGridStyle(block)}
      >
        <RuntimePermissionNotice
          permissionCode={permissionCode}
          testId={`runtime-field-permission-${block.id}`}
        />
      </div>
    );
  }

  // True WYSIWYG: render the real platform control for `field` blocks when model
  // metadata resolves the field OR the block has an explicit component configured.
  // The generic HTML fallback remains for unconfigured `field` blocks without either —
  // these are progressively replaced as more blocks get bound in M4.
  if (block.blockType === 'field' && !isDesignerRuntimeOnlyComponent(block)) {
    const modelField = block.field
      ? modelFields.find((candidate) => candidate.code === block.field)
      : undefined;
    const hasExplicitComponent = Boolean(block.props?.component);
    if (modelField || hasExplicitComponent) {
      return <RuntimePlatformField block={block} modelField={modelField} />;
    }
  }

  const fieldKey = block.field || block.id;
  const isFormField = block.blockType === 'field' && Boolean(formContext);
  const isFilterField = block.blockType === 'filter-field' && Boolean(listContext);
  const component = normalizeRuntimeFieldComponent(block.props?.component);
  const controlId = getRuntimeFieldControlId(block, component);
  const value = formContext?.values[fieldKey];
  const filterValue = listContext?.filterValues[fieldKey];
  const stringValue = typeof value === 'string' || typeof value === 'number' ? String(value) : '';
  const filterStringValue =
    typeof filterValue === 'string' || typeof filterValue === 'number' ? String(filterValue) : '';
  const placeholder = getStringProp(block.props?.placeholder);
  const helpText = getStringProp(block.props?.helpText);
  const readOnly = Boolean(block.props?.readOnly);
  const error = formContext?.errors[fieldKey] ?? '';

  return (
    <div
      className="rounded-md border border-slate-200 bg-white px-3 py-2 text-sm"
      data-testid={`runtime-field-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <label className="block font-medium text-slate-700" htmlFor={controlId}>
        {getBlockLabel(block)}
      </label>
      {block.props?.required ? <span className="ml-1 text-red-500">*</span> : null}
      {isFormField
        ? renderRuntimeFieldControl({
            block,
            component,
            fieldKey,
            value,
            stringValue,
            placeholder,
            readOnly,
            formContext,
            runtimeServices,
            pageContext,
            blockPath,
            t,
          })
        : isFilterField
          ? renderRuntimeFilterControl({
              block,
              blockPath,
              component,
              fieldKey,
              filterValue,
              stringValue: filterStringValue,
              placeholder,
              listContext,
              pageContext,
              runtimeServices,
              t,
            })
        : null}
      {helpText ? (
        <div className="mt-1 text-xs text-slate-500" data-testid={`runtime-field-help-${block.id}`}>
          {helpText}
        </div>
      ) : null}
      {error ? (
        <div className="mt-1 text-xs text-red-600" data-testid={`runtime-field-error-${block.id}`}>
          {error}
        </div>
      ) : null}
    </div>
  );
}

/**
 * WYSIWYG form-field preview: renders the exact same platform control the live
 * `/p/` page uses ({@link ControlledFieldRenderer}), driven by the field's resolved
 * model metadata (`renderComponent`, label, dictCode, extension props). This replaces
 * the legacy generic-input representative preview for `field` blocks whose model
 * metadata is available.
 */
function RuntimePlatformField({
  block,
  modelField,
}: {
  block: DslBlockV3;
  modelField?: ModelFieldDefinition;
}) {
  const locale = React.useContext(RuntimeLocaleContext);
  const formContext = React.useContext(RuntimeFormValueContext);
  const hasPermission = React.useContext(RuntimePermissionContext);
  const [localValue, setLocalValue] = React.useState<unknown>(undefined);

  const fieldConfig = React.useMemo(
    () => buildPreviewFieldConfig(block, modelField),
    [block, modelField],
  );
  const expressionContext = React.useMemo(() => createExpressionContext({ locale }), [locale]);

  const permissionCode = getRuntimePermissionCode(block);
  const permissionAllowed = isRuntimeBlockPermissionAllowed(block, hasPermission);
  if (!permissionAllowed) {
    return (
      <div
        className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm"
        data-permission-allowed="false"
        data-permission-code={permissionCode}
        data-testid={`runtime-field-${block.id}`}
        style={getSpanGridStyle(block)}
      >
        <RuntimePermissionNotice
          permissionCode={permissionCode}
          testId={`runtime-field-permission-${block.id}`}
        />
      </div>
    );
  }

  const fieldKey = block.field || block.id;
  const value = formContext ? formContext.values[fieldKey] : localValue;
  const rawError = formContext?.errors[fieldKey];
  const handleChange = (next: unknown) => {
    if (formContext) {
      formContext.setValue(fieldKey, next);
    } else {
      setLocalValue(next);
    }
  };

  return (
    <div
      data-testid={`runtime-field-${block.id}`}
      data-field-component={fieldConfig.component ?? undefined}
      data-wysiwyg="platform"
      style={getSpanGridStyle(block)}
    >
      <ControlledFieldRenderer
        field={fieldConfig}
        value={value}
        onChange={handleChange}
        context={expressionContext}
        error={typeof rawError === 'string' ? rawError : undefined}
      />
    </div>
  );
}

function renderRuntimeFieldControl({
  block,
  component,
  fieldKey,
  value,
  stringValue,
  placeholder,
  readOnly,
  formContext,
  runtimeServices,
  pageContext,
  blockPath,
  t,
}: {
  block: DslBlockV3;
  component: RuntimeFieldComponent;
  fieldKey: string;
  value: unknown;
  stringValue: string;
  placeholder: string;
  readOnly: boolean;
  formContext: RuntimeFormContextValue | null;
  runtimeServices?: RuntimeExecutionServices;
  pageContext: RuntimePageContext;
  blockPath: string[];
  t: (entry: Record<string, string>) => string;
}) {
  const baseClass =
    'mt-2 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm text-slate-900 outline-none focus:border-blue-400';
  const controlId = getRuntimeFieldControlId(block, component);

  if (component === 'textarea') {
    return (
      <textarea
        id={controlId}
        className={`${baseClass} min-h-20 resize-y`}
        data-testid={`runtime-textarea-${block.id}`}
        name={fieldKey}
        placeholder={placeholder}
        readOnly={readOnly}
        value={stringValue}
        onChange={(event) => formContext?.setValue(fieldKey, event.target.value)}
      />
    );
  }

  if (component === 'select') {
    const options = getRuntimeSelectOptions(block.props?.options);
    return (
      <select
        id={controlId}
        className={baseClass}
        data-testid={`runtime-select-${block.id}`}
        name={fieldKey}
        disabled={readOnly}
        value={stringValue}
        onChange={(event) => formContext?.setValue(fieldKey, event.target.value)}
      >
        <option value="">{placeholder || t(DESIGNER_I18N.unified.runtime.selectPlaceholder)}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    );
  }

  if (component === 'picker') {
    return (
      <RuntimePickerControl
        baseClass={baseClass}
        block={block}
        blockPath={blockPath}
        controlId={controlId}
        fieldKey={fieldKey}
        formContext={formContext}
        pageContext={pageContext}
        placeholder={placeholder}
        readOnly={readOnly}
        runtimeServices={runtimeServices}
        stringValue={stringValue}
      />
    );
  }

  if (component === 'radio') {
    const options = getRuntimeSelectOptions(block.props?.options);
    return (
      <div
        id={controlId}
        className="mt-2 flex flex-wrap gap-3"
        data-testid={`runtime-radio-${block.id}`}
        role="radiogroup"
      >
        {options.map((option) => {
          const optionId = `${controlId}-${option.value}`;
          return (
            <label
              key={option.value}
              className="inline-flex items-center gap-1.5 text-sm text-slate-700"
              htmlFor={optionId}
            >
              <input
                id={optionId}
                type="radio"
                className="h-4 w-4 border-slate-300 text-blue-600 focus:ring-blue-400"
                data-testid={`runtime-radio-${block.id}-${option.value}`}
                name={fieldKey}
                disabled={readOnly}
                checked={stringValue === option.value}
                value={option.value}
                onChange={(event) => formContext?.setValue(fieldKey, event.target.value)}
              />
              {option.label}
            </label>
          );
        })}
        {options.length ? null : (
          <div className="text-xs text-slate-500" data-testid={`runtime-radio-empty-${block.id}`}>
            {t(DESIGNER_I18N.unified.runtime.noOptionsConfigured)}
          </div>
        )}
      </div>
    );
  }

  if (component === 'rich-text') {
    return (
      <div className="mt-2 overflow-hidden rounded-md border border-slate-200 bg-white">
        <div
          className="flex gap-1 border-b border-slate-100 bg-slate-50 px-2 py-1 text-xs font-semibold text-slate-500"
          data-testid={`runtime-rich-text-toolbar-${block.id}`}
        >
          <span>B</span>
          <span>I</span>
          <span>{t(DESIGNER_I18N.unified.runtime.link)}</span>
        </div>
        <textarea
          id={controlId}
          className="min-h-28 w-full resize-y border-0 px-2 py-1.5 text-sm text-slate-900 outline-none focus:ring-0"
          data-testid={`runtime-rich-text-${block.id}`}
          name={fieldKey}
          placeholder={placeholder}
          readOnly={readOnly}
          value={stringValue}
          onChange={(event) => formContext?.setValue(fieldKey, event.target.value)}
        />
      </div>
    );
  }

  if (component === 'checkbox' || component === 'switch') {
    return (
      <input
        id={controlId}
        type="checkbox"
        role={component === 'switch' ? 'switch' : undefined}
        className="mt-2 h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-400"
        data-testid={`runtime-${component}-${block.id}`}
        name={fieldKey}
        disabled={readOnly}
        checked={Boolean(value)}
        onChange={(event) => formContext?.setValue(fieldKey, event.target.checked)}
      />
    );
  }

  if (component === 'upload') {
    const accept = getStringProp(block.props?.accept);
    const multiple = Boolean(block.props?.multiple);
    const maxFiles = getRuntimeNumber(block.props?.maxFiles);
    const selectedFiles = Array.isArray(value)
      ? value.map((item) => String(item)).filter(Boolean)
      : [];
    return (
      <div className="mt-2 space-y-2">
        <input
          id={controlId}
          type="file"
          className={baseClass}
          data-testid={`runtime-upload-${block.id}`}
          name={fieldKey}
          accept={accept || undefined}
          multiple={multiple}
          disabled={readOnly}
          onChange={(event) => {
            const files = Array.from(event.target.files ?? []);
            const effectiveLimit = maxFiles && maxFiles > 0 ? maxFiles : files.length;
            const selected = files.slice(0, effectiveLimit).map((file) => file.name);
            formContext?.setValue(fieldKey, selected);
          }}
        />
        {selectedFiles.length ? (
          <ul
            className="space-y-1 text-xs text-slate-600"
            data-testid={`runtime-upload-files-${block.id}`}
          >
            {selectedFiles.map((fileName) => (
              <li key={fileName} className="rounded bg-slate-50 px-2 py-1">
                {fileName}
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    );
  }

  const inputType = component === 'date' ? 'date' : component === 'number' ? 'number' : 'text';
  return (
    <input
      id={controlId}
      type={inputType}
      className={baseClass}
      data-testid={`runtime-input-${block.id}`}
      name={fieldKey}
      placeholder={placeholder}
      readOnly={readOnly}
      value={stringValue}
      onChange={(event) => formContext?.setValue(fieldKey, event.target.value)}
    />
  );
}

function renderRuntimeFilterControl({
  block,
  blockPath,
  component,
  fieldKey,
  filterValue,
  stringValue,
  placeholder,
  listContext,
  pageContext,
  runtimeServices,
  t,
}: {
  block: DslBlockV3;
  blockPath: string[];
  component: RuntimeFieldComponent;
  fieldKey: string;
  filterValue: unknown;
  stringValue: string;
  placeholder: string;
  listContext: RuntimeSelectionContextValue | null;
  pageContext: RuntimePageContext;
  runtimeServices?: RuntimeExecutionServices;
  t: (entry: Record<string, string>) => string;
}) {
  const baseClass =
    'mt-2 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm text-slate-900 outline-none focus:border-blue-400';
  const setFilterValue = (value: unknown) => listContext?.setFilterValue(fieldKey, value);

  if (component === 'picker') {
    return (
      <RuntimePickerControl
        baseClass={baseClass}
        block={block}
        blockPath={blockPath}
        controlId={getRuntimeFieldControlId(block, component)}
        fieldKey={fieldKey}
        formContext={null}
        onValueChange={setFilterValue}
        pageContext={pageContext}
        placeholder={placeholder || t(DESIGNER_I18N.unified.runtime.allRecords)}
        readOnly={false}
        runtimeServices={runtimeServices}
        stringValue={stringValue}
      />
    );
  }

  if (component === 'select') {
    const options = getRuntimeSelectOptions(block.props?.options);
    return (
      <select
        className={baseClass}
        data-testid={`runtime-filter-select-${block.id}`}
        name={fieldKey}
        value={stringValue}
        onChange={(event) => setFilterValue(event.target.value)}
      >
        <option value="">{placeholder || t(DESIGNER_I18N.unified.runtime.all)}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    );
  }

  if (component === 'checkbox' || component === 'switch') {
    return (
      <input
        type="checkbox"
        role={component === 'switch' ? 'switch' : undefined}
        className="mt-2 h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-400"
        data-testid={`runtime-filter-${component}-${block.id}`}
        name={fieldKey}
        checked={Boolean(filterValue)}
        onChange={(event) => setFilterValue(event.target.checked)}
      />
    );
  }

  const inputType = component === 'date' ? 'date' : component === 'number' ? 'number' : 'text';
  return (
    <input
      type={inputType}
      className={baseClass}
      data-testid={`runtime-filter-input-${block.id}`}
      name={fieldKey}
      placeholder={placeholder}
      value={stringValue}
      onChange={(event) => setFilterValue(event.target.value)}
    />
  );
}

function RuntimePickerControl({
  baseClass,
  block,
  blockPath,
  controlId,
  fieldKey,
  formContext,
  onValueChange,
  pageContext,
  placeholder,
  readOnly,
  runtimeServices,
  stringValue,
}: {
  baseClass: string;
  block: DslBlockV3;
  blockPath: string[];
  controlId: string;
  fieldKey: string;
  formContext: RuntimeFormContextValue | null;
  onValueChange?: (value: string) => void;
  pageContext: RuntimePageContext;
  placeholder: string;
  readOnly: boolean;
  runtimeServices?: RuntimeExecutionServices;
  stringValue: string;
}) {
  const t = useRuntimeText();
  const staticOptions = getRuntimeSelectOptions(block.props?.options);
  const [dynamicOptions, setDynamicOptions] = React.useState<RuntimeSelectOption[]>([]);
  const [searchText, setSearchText] = React.useState('');
  const [loading, setLoading] = React.useState(false);
  const [runtimeError, setRuntimeError] = React.useState<RuntimeExecutionIssue | null>(null);
  const pickerSignature = JSON.stringify({
    dataSource: block.dataSource ?? null,
    pickerDataSource: block.props?.pickerDataSource,
    pickerSource: block.props?.pickerSource,
    pickerQueryCode: block.props?.pickerQueryCode,
    valueField: block.props?.valueField,
    displayField: block.props?.displayField,
    pageSize: block.props?.pageSize,
    limit: block.props?.limit,
    parameters: block.props?.parameters,
    pickerParameters: block.props?.pickerParameters,
    searchable: block.props?.searchable,
    searchField: block.props?.searchField,
    searchParameter: block.props?.searchParameter,
  });
  const shouldLoadOptions = Boolean(
    runtimeServices?.loadPickerOptions && hasExecutablePickerDataSource(block),
  );
  const isSearchablePicker = shouldLoadOptions && getBooleanProp(block.props?.searchable);

  React.useEffect(() => {
    if (!shouldLoadOptions || !runtimeServices?.loadPickerOptions) {
      setDynamicOptions([]);
      setRuntimeError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setRuntimeError(null);
    void runtimeServices
      .loadPickerOptions(
        block,
        getRuntimeBlockContext(block, pageContext, blockPath, {
          pickerSearch: isSearchablePicker ? searchText.trim() || undefined : undefined,
        }),
      )
      .then((options) => {
        if (!cancelled) setDynamicOptions(normalizeRuntimePickerOptions(options));
      })
      .catch((loadError: unknown) => {
        if (!cancelled) {
          setDynamicOptions([]);
          setRuntimeError(
            normalizeRuntimeExecutionError(
              loadError,
              t(DESIGNER_I18N.unified.runtime.pickerOptionsFailed),
            ),
          );
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [
    block,
    blockPath,
    isSearchablePicker,
    pageContext,
    pickerSignature,
    runtimeServices,
    searchText,
    shouldLoadOptions,
  ]);

  const options = shouldLoadOptions ? dynamicOptions : staticOptions;
  const meta = getRuntimePickerMeta(block);
  const searchPlaceholder =
    getStringProp(block.props?.searchPlaceholder) || t(DESIGNER_I18N.unified.runtime.searchRecords);

  return (
    <div className="mt-2 space-y-1">
      {isSearchablePicker ? (
        <input
          type="search"
          className={baseClass}
          data-testid={`runtime-picker-search-${block.id}`}
          placeholder={searchPlaceholder}
          value={searchText}
          disabled={readOnly}
          onChange={(event) => setSearchText(event.target.value)}
        />
      ) : null}
      <select
        id={controlId}
        className={baseClass}
        data-testid={`runtime-picker-${block.id}`}
        name={fieldKey}
        disabled={readOnly || loading}
        value={stringValue}
        onChange={(event) => {
          if (onValueChange) {
            onValueChange(event.target.value);
            return;
          }
          formContext?.setValue(fieldKey, event.target.value);
        }}
      >
        <option value="">
          {loading
            ? t(DESIGNER_I18N.unified.runtime.loadingRecords)
            : placeholder || t(DESIGNER_I18N.unified.runtime.selectRecord)}
        </option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {meta ? (
        <div className="text-xs text-slate-500" data-testid={`runtime-picker-meta-${block.id}`}>
          {meta}
        </div>
      ) : null}
      {loading ? (
        <div className="text-xs text-blue-600" data-testid={`runtime-picker-loading-${block.id}`}>
          Loading records
        </div>
      ) : null}
      {runtimeError ? (
        <div
          className="text-xs font-medium text-red-600"
          data-testid={`runtime-picker-error-${block.id}`}
          data-error-kind={runtimeError.kind}
          data-error-code={runtimeError.code || undefined}
        >
          {runtimeError.message}
        </div>
      ) : null}
    </div>
  );
}
