import { resolveFormPresentation, formGridClass, formFieldSpanClass } from './formPresentation';
import { WorkspaceGuidance } from '../form/WorkspaceGuidance';
import { FormSection } from '../form/FormSection';
import { Link } from 'react-router';
import { DynamicPageUnavailable } from '../DynamicPageUnavailable';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator';
import { DataSourceProvider } from '~/framework/meta/contexts/DataSourceContext';
import { SubTable } from '~/framework/meta/components/SubTable';
import { ContributedSubTableViewer as SubTableViewer } from '~/framework/extensions/contributed-components';
import { ComponentLoader } from '~/framework/meta/rendering/components/ComponentLoader';
import { BlockRenderer, BlockErrorBoundary } from '@auraboot/runtime-kernel';
import { FormFillReceipt } from '../form/FormFillReceipt';
import { DslFormFillProvider } from '~/framework/meta/rendering/DslFormFillContext';
import { deriveTestId, buttonTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import { RestoreDraftBanner } from '~/framework/meta/rendering/pages/form/RestoreDraftBanner';
import {
  mergeRefTarget,
  resolveComponentByFieldMeta,
  mergeFieldValidationRules,
} from './formFieldMetadata';
import {
  resolveFormButtonContent,
  isFormButtonDisabled,
  canRenderFormButton,
  isFormAuthoringDenied,
  isPureCrudFormAuthoringDenied,
  resolveFormBackLink,
  resolveCreateSubTableEmptyState,
} from './formActions';
import type { useFormPageState } from './useFormPageState';
import type { useFormRecord } from './useFormRecord';
import type { useFormPageRuntime } from './useFormPageRuntime';
import type { useFormMetadata } from './useFormMetadata';
import type { useFormFields } from './useFormFields';
import type { useFormChildren } from './useFormChildren';
import type { useFormSubmission } from './useFormSubmission';

type RenderFormPageViewArgs = Pick<
  ReturnType<typeof useFormPageState>,
  | 'schema'
  | 'tableName'
  | 'isEditMode'
  | 'onCancelOverride'
  | 'onSubmitOverride'
  | 'urlCommandCode'
  | 'summaryErrors'
  | 'fieldErrors'
  | 'fieldPermissions'
  | 'recordPid'
  | 'formData'
  | 'token'
  | 'sidebarWidth'
> &
  Pick<ReturnType<typeof useFormRecord>, 'mainRecordLoaded' | 'error' | 'loading' | 'submitting'> &
  Pick<
    ReturnType<typeof useFormPageRuntime>,
    | 'schemaFieldNames'
    | 'pageContext'
    | 'hasPermission'
    | 'dataSourceManager'
    | 'locale'
    | 't'
    | 'urlSeededModelCode'
    | 'runtime'
  > &
  Pick<
    ReturnType<typeof useFormMetadata>,
    'fieldMetaLoaded' | 'aiFill' | 'aiFields' | 'modelFields'
  > &
  Pick<
    ReturnType<typeof useFormFields>,
    | 'restorableDraft'
    | 'handleRestoreDraft'
    | 'discardDraft'
    | 'customBlockRuntime'
    | 'renderSmartField'
  > &
  Pick<
    ReturnType<typeof useFormChildren>,
    | 'subTableBlocks'
    | 'enhancedColumns'
    | 'subTableColumnsMap'
    | 'editDataLoaded'
    | 'subTableData'
    | 'setSubTableData'
  > &
  Pick<ReturnType<typeof useFormSubmission>, 'handleFormAction'>;

export function renderFormPageView({
  schema,
  mainRecordLoaded,
  schemaFieldNames,
  fieldMetaLoaded,
  tableName,
  pageContext,
  hasPermission,
  dataSourceManager,
  aiFill,
  isEditMode,
  locale,
  t,
  onCancelOverride,
  onSubmitOverride,
  urlCommandCode,
  summaryErrors,
  error,
  restorableDraft,
  handleRestoreDraft,
  discardDraft,
  urlSeededModelCode,
  runtime,
  aiFields,
  customBlockRuntime,
  fieldErrors,
  fieldPermissions,
  modelFields,
  renderSmartField,
  subTableBlocks,
  enhancedColumns,
  subTableColumnsMap,
  recordPid,
  formData,
  token,
  editDataLoaded,
  subTableData,
  setSubTableData,
  sidebarWidth,
  handleFormAction,
  loading,
  submitting,
}: RenderFormPageViewArgs) {
  // Null schema guard
  if (!schema) {
    return null;
  }

  // Extract form, sub-table, and button blocks
  const allBlocks = schema.blocks || [];

  const formBlocks = allBlocks.filter((block: any) => block.blockType === 'form-section');
  const layoutBlocks = allBlocks.filter((block: any) => block.blockType === 'tabs');
  // Custom block support — surfaces blockType:"custom" entries that DSL
  // pages declare for visual companions to the form (e.g. position
  // ruler, designer panels). Rendered above the form-section blocks so
  // operators see the visualization first.
  const customBlocks = allBlocks.filter((block: any) => block.blockType === 'custom');
  // subTableBlocks computed via useMemo above (used for metadata fetching and rendering)
  const buttonBlock = allBlocks.find((block: any) => block.blockType === 'form-buttons');
  const effectiveButtonBlock = buttonBlock || null;
  // Field metadata decides the concrete widget type and its controlled-value adapter.
  // Rendering editable provisional controls before metadata resolves lets a fast user type into
  // a SmartInput that is then replaced by (for example) SmartNumberInput. Keep the form in one
  // explicit loading state until both record and field contracts are authoritative so values can
  // never live only in a provisional control's local state.
  const formInteractionReady = mainRecordLoaded && (schemaFieldNames.size === 0 || fieldMetaLoaded);

  // B-003 (DR-20260715-B-003): kind:form previously hard-coded form-section / tabs /
  // custom / form-buttons / sub-table and silently dropped every other top-level block —
  // an ai-fill-banner declared at the top of a form vanished. Dispatch the rest through
  // the kernel BlockRenderer (mirrors the ListPageContent / DetailPageContent G7 fallback);
  // unknown types surface a visible placeholder + console.warn instead of disappearing.
  const FORM_SPECIALIZED_BLOCK_TYPES = new Set<string>([
    'form-section',
    'tabs',
    'custom',
    'form-buttons',
    'sub-table',
  ]);
  const miscFormBlocks = allBlocks.filter(
    (block: any) => !FORM_SPECIALIZED_BLOCK_TYPES.has(block.blockType),
  );
  const workspace = schema.extension?.presentation === 'workspace';
  const embedded = Boolean(onCancelOverride || onSubmitOverride);
  const presentation = resolveFormPresentation(schema.extension, embedded, {
    fieldCount: formBlocks.reduce(
      (count: number, block: any) => count + (block.fields?.length || 0),
      0,
    ),
    hasWideContent:
      subTableBlocks.length > 0 ||
      layoutBlocks.length > 0 ||
      miscFormBlocks.length > 0 ||
      customBlocks.length > 0,
  });
  const hasGuidance = presentation.guidance === 'aside' && Boolean(schema.extension?.formGuidance);
  const fixedActions = presentation.actions === 'fixed';
  const backLink = resolveFormBackLink(schema, tableName);
  const activeButtons = (effectiveButtonBlock?.buttons || []).filter(
    (button: any) => !button.visibleWhen || evaluateCondition(button.visibleWhen, pageContext),
  );
  if (
    isFormAuthoringDenied(activeButtons, hasPermission) ||
    isPureCrudFormAuthoringDenied(
      schema,
      effectiveButtonBlock?.buttons || [],
      isEditMode,
      embedded || Boolean(urlCommandCode),
      hasPermission,
    )
  ) {
    return <DynamicPageUnavailable message="Access denied" />;
  }

  return (
    <DataSourceProvider manager={dataSourceManager}>
      {/* B-003: give top-level blocks (ai-fill-banner) a working applyFields wired to formData. */}
      <DslFormFillProvider target={aiFill.target}>
        {/* Centered, width-capped form: full-width inputs stretched edge-to-edge on
          wide screens read as sparse and hurt scanability. max-w-6xl (~1152px) keeps
          a comfortable 2-column line length while staying roomy for sub-tables. */}
        <div
          className={
            embedded
              ? 'w-full min-w-0'
              : `mx-auto w-full min-w-0 bg-[#F7F7F8] px-4 py-6 sm:px-6 ${fixedActions ? 'pb-24' : ''} ${presentation.width === 'comfortable' ? 'max-w-4xl' : presentation.width === 'wide' ? 'max-w-6xl' : ''}`
          }
          data-testid={deriveTestId('form', schema?.modelCode || tableName, 'container')}
        >
          <div className="bg-transparent">
            {/* The embedding host owns its heading and navigation. */}
            {!embedded && (
              <div className="pt-2 pb-5">
                <div className="flex items-start justify-between">
                  <div>
                    <h2 className="text-text text-2xl font-semibold tracking-tight">
                      {getLocalizedText(
                        (isEditMode
                          ? schema.extension?.editTitle
                          : schema.extension?.createTitle) || schema.title,
                        locale,
                        t,
                      )}
                    </h2>
                    {schema.extension?.pageDescription && (
                      <p className="text-text-2 mt-2 text-xs">
                        {getLocalizedText(schema.extension.pageDescription, locale, t)}
                      </p>
                    )}
                  </div>
                  {onCancelOverride ? (
                    <button
                      type="button"
                      onClick={onCancelOverride}
                      data-testid="form-back-link"
                      className="text-accent text-sm hover:text-blue-800"
                    >
                      {t('action.back')}
                    </button>
                  ) : backLink ? (
                    <Link
                      to={backLink}
                      data-testid="form-back-link"
                      className="text-accent text-sm hover:text-blue-800"
                    >
                      {t('action.back')}
                    </Link>
                  ) : null}
                </div>
              </div>
            )}

            <div
              className={
                hasGuidance
                  ? 'grid min-w-0 grid-cols-1 items-start gap-5 xl:grid-cols-[minmax(0,1fr)_246px]'
                  : ''
              }
            >
              <div className="min-w-0">
                {/* Error Summary */}
                {summaryErrors.length > 0 ? (
                  <div
                    className="rounded-control bg-status-red-bg mx-6 mt-4 border border-red-200 p-4"
                    data-testid="form-error-summary"
                  >
                    <p className="text-sm font-medium text-red-700">
                      {summaryErrors.length > 1
                        ? `请先修正以下 ${summaryErrors.length} 项问题`
                        : '请先修正以下问题'}
                    </p>
                    <ul className="text-status-red mt-2 list-disc space-y-1 pl-5">
                      {summaryErrors.map((message) => (
                        <li key={message}>{message}</li>
                      ))}
                    </ul>
                  </div>
                ) : error ? (
                  <div className="rounded-control bg-status-red-bg mx-6 mt-4 border border-red-200 p-4">
                    <p className="text-status-red">{error}</p>
                  </div>
                ) : null}

                {/* T10: restore-draft prompt — offered when a non-expired local draft
              differs from the initial create-form values. */}
                {restorableDraft && (
                  <RestoreDraftBanner
                    savedAt={restorableDraft.savedAt}
                    locale={locale}
                    t={t}
                    onRestore={handleRestoreDraft}
                    onDiscard={discardDraft}
                  />
                )}

                {/* URL-prefill hint: shown when modelCode was seeded from ?modelCode=xxx */}
                {urlSeededModelCode && !isEditMode && (
                  <div
                    className="rounded-control bg-accent-weak mx-6 mt-4 border border-blue-200 p-3"
                    data-testid="form-modelcode-prefill-hint"
                  >
                    <p className="text-accent text-sm">
                      {(
                        t('pageSchemaForm.modelCodePrefillHint') ||
                        `Creating from model "{modelCode}"`
                      ).replace('{modelCode}', urlSeededModelCode)}
                    </p>
                  </div>
                )}

                {/* Form Content - Using ComponentLoader Pattern */}
                <form
                  className="space-y-4"
                  data-testid="dynamic-form"
                  onSubmit={(e) => e.preventDefault()}
                >
                  {!formInteractionReady ? (
                    <div
                      className="text-text-3 py-8 text-center text-sm"
                      data-testid="dynamic-form-loading"
                    >
                      {t('common.loading') || 'Loading...'}
                    </div>
                  ) : (
                    <>
                      {/* B-003: top-level non-whitelist blocks (ai-fill-banner etc.) via the kernel
                    BlockRenderer, rendered above the form so operators see them first. */}
                      {runtime &&
                        miscFormBlocks.map((block: any, idx: number) => (
                          <div
                            key={block.id || `misc-form-${idx}`}
                            data-block-id={block.id}
                            className="mb-4"
                          >
                            <BlockRenderer block={block} runtime={runtime} areaId="form-misc" />
                          </div>
                        ))}
                      {aiFill.receipt && (
                        <FormFillReceipt
                          receipt={aiFill.receipt}
                          fields={aiFields}
                          t={t}
                          onUndo={aiFill.undo}
                        />
                      )}
                      {customBlocks.length > 0 &&
                        customBlocks.map((block: any) => {
                          // Honour DSL visibility condition (matches form-section behavior below).
                          if (
                            block.visibleWhen &&
                            !evaluateCondition(block.visibleWhen, pageContext)
                          ) {
                            return null;
                          }
                          // Missing component name → surface a visible error, mirroring
                          // BlockRenderer's pattern. Silent-null would hide DSL typos.
                          if (!block.component) {
                            return (
                              <BlockErrorBoundary
                                key={block.id}
                                blockType="custom"
                                blockId={block.id}
                              >
                                <div
                                  className="border-status-red bg-status-red-bg mb-5 rounded border p-4"
                                  data-block-id={block.id}
                                >
                                  <p className="text-red-800">
                                    Custom block missing `component`: {block.id}
                                  </p>
                                </div>
                              </BlockErrorBoundary>
                            );
                          }
                          return (
                            <BlockErrorBoundary
                              key={block.id}
                              blockType="custom"
                              blockId={block.id}
                            >
                              <div
                                data-block-id={block.id}
                                className={`block-custom mb-5 ${block.className || ''}`}
                              >
                                {block.extension?.collapsible ? (
                                  <FormSection
                                    title={getLocalizedText(block.title, locale, t)}
                                    collapsible
                                    defaultCollapsed={block.extension.defaultCollapsed === true}
                                    testId={`custom-section-${block.id}`}
                                  >
                                    <ComponentLoader
                                      componentName={block.component}
                                      props={{
                                        block,
                                        runtime: customBlockRuntime,
                                      }}
                                    />
                                  </FormSection>
                                ) : (
                                  <ComponentLoader
                                    componentName={block.component}
                                    props={{
                                      block,
                                      runtime: customBlockRuntime,
                                    }}
                                  />
                                )}
                              </div>
                            </BlockErrorBoundary>
                          );
                        })}
                      {formBlocks && formBlocks.length > 0 && (
                        <div className="space-y-5">
                          {formBlocks.map((block: any, blockIndex: number) => {
                            // Check block visibility condition
                            if (block.visibleWhen) {
                              const isVisible = evaluateCondition(block.visibleWhen, pageContext);
                              if (!isVisible) {
                                return null;
                              }
                            }
                            const blockTitle = block.title
                              ? getLocalizedText(block.title, locale, t)
                              : '';
                            const blockDescription = block.description
                              ? getLocalizedText(block.description, locale, t)
                              : '';
                            const displayVariant = String(
                              block.extension?.displayVariant || block.extension?.variant || '',
                            );
                            const isSettingsCard = displayVariant === 'settings-card';

                            return (
                              <FormSection
                                key={block.id || `block-${blockIndex}`}
                                surface={presentation.surface}
                                density={presentation.density}
                                title={blockTitle}
                                index={
                                  workspace && !block.extension?.collapsible
                                    ? String(blockIndex + 1).padStart(2, '0')
                                    : undefined
                                }
                                description={blockDescription}
                                collapsible={
                                  (block.collapsible ?? block.extension?.collapsible) === true
                                }
                                defaultCollapsed={
                                  (block.defaultCollapsed ?? block.extension?.defaultCollapsed) ===
                                  true
                                }
                                hasError={(block.fields || []).some((field: any) =>
                                  Boolean(fieldErrors[field.field]),
                                )}
                                testId={
                                  isSettingsCard && block.id
                                    ? `settings-card-${block.id}`
                                    : `form-section-${block.id || blockIndex}`
                                }
                              >
                                {/* Section Fields */}
                                {block.fields && block.fields.length > 0 && (
                                  <div
                                    className={`grid ${formGridClass(block.columns ?? block.layout?.columns ?? presentation.columns)} ${presentation.density === 'compact' ? 'gap-x-4 gap-y-3' : 'gap-x-6 gap-y-4'} ${
                                      isSettingsCard ? 'p-4 sm:p-5' : ''
                                    }`}
                                  >
                                    {block.fields.map((rawField: any) => {
                                      // L1 SDK: skip hidden fields from external fieldPermissions
                                      const externalPerm = fieldPermissions?.[rawField.field];
                                      if (externalPerm === 'hidden') return null;

                                      // Enrich field with metadata (component, dictCode) when available
                                      const meta = modelFields[rawField.field];
                                      const mergedValidationRules = mergeFieldValidationRules(
                                        rawField,
                                        meta,
                                        t,
                                        locale,
                                      );
                                      const maxLength = Number(
                                        meta?.feature?.validation?.maxLength,
                                      );
                                      // Use pre-computed extensionProps (stable reference from modelFields)
                                      const extensionProps = meta?.extensionProps;
                                      let field = meta
                                        ? {
                                            ...rawField,
                                            modelCode: schema?.modelCode || tableName,
                                            label:
                                              rawField.label || meta.displayName || rawField.label,
                                            dataType: rawField.dataType || meta.dataType,
                                            component:
                                              rawField.component ||
                                              meta.component ||
                                              resolveComponentByFieldMeta(meta.dataType),
                                            dictCode:
                                              rawField.dictCode ||
                                              meta.dictCode ||
                                              extensionProps?.dictCode,
                                            refTarget: mergeRefTarget(
                                              rawField.refTarget,
                                              meta.refTarget,
                                            ),
                                            referenceModelCode:
                                              rawField.referenceModelCode ||
                                              meta.referenceModelCode,
                                            // A read-only field is never user-required (it is
                                            // auto-generated / system-managed and cannot be typed
                                            // into), so the `*` marker must match the submit gate,
                                            // which excludes read-only fields from required
                                            // validation (mergeFieldValidationRules: `!rawField.readOnly`).
                                            // Without this guard an auto-numbered read-only field
                                            // (e.g. sc_code) shows a misleading required `*`.
                                            required: rawField.readOnly
                                              ? false
                                              : (rawField.required ?? meta.required),
                                            readOnly:
                                              rawField.readOnly ??
                                              meta.extension?.readOnly ??
                                              (meta.extension as any)?.extension?.readOnly ??
                                              (extensionProps?.computed === true
                                                ? true
                                                : undefined),
                                            validation: mergedValidationRules,
                                            props: {
                                              ...(extensionProps || {}),
                                              ...(rawField.props || {}),
                                              ...(Number.isFinite(maxLength) &&
                                              maxLength > 0 &&
                                              !rawField.props?.maxLength
                                                ? { maxLength }
                                                : {}),
                                            },
                                          }
                                        : {
                                            ...rawField,
                                            modelCode: schema?.modelCode || tableName,
                                          };

                                      // L1 SDK: apply external readonly permission override
                                      if (externalPerm === 'readonly') {
                                        field = { ...field, readOnly: true };
                                      }

                                      // Check field visibility condition
                                      if (field.visibleWhen) {
                                        const isVisible = evaluateCondition(
                                          field.visibleWhen,
                                          pageContext,
                                        );
                                        if (!isVisible) {
                                          return null;
                                        }
                                      }

                                      // Calculate column span based on field layout or DSL span
                                      const colSpan =
                                        field.layout?.colSpan ||
                                        field.colSpan ||
                                        (field.span ? field.span * 6 : 6);
                                      const fieldSpanClass = formFieldSpanClass(
                                        colSpan,
                                        block.columns ??
                                          block.layout?.columns ??
                                          presentation.columns,
                                      );

                                      return (
                                        <div
                                          key={field.field}
                                          data-testid={`form-field-${field.field}`}
                                          data-ab-testid={deriveTestId(
                                            'form',
                                            schema?.modelCode || tableName,
                                            'field',
                                            field.field,
                                          )}
                                          className={fieldSpanClass}
                                        >
                                          {renderSmartField(field)}
                                        </div>
                                      );
                                    })}
                                  </div>
                                )}
                              </FormSection>
                            );
                          })}
                        </div>
                      )}
                      {runtime && layoutBlocks.length > 0 && (
                        <div className="mt-5 space-y-5" data-testid="form-layout-blocks">
                          {layoutBlocks.map((block: any, blockIndex: number) => (
                            <BlockRenderer
                              key={block.id || `form-layout-${blockIndex}`}
                              block={block}
                              runtime={runtime}
                              areaId={`form-layout-${blockIndex}`}
                            />
                          ))}
                        </div>
                      )}
                    </>
                  )}

                  {/* Sub-table blocks */}
                  {subTableBlocks.map((block: any, blockIndex: number) => {
                    const childModel = block.subTable?.childModel || (block as any).childModel;
                    const subTableConfig = block.subTable;
                    const hasCommands =
                      subTableConfig?.commands?.create || subTableConfig?.commands?.delete;
                    // Use enhanced columns (with proper types) if available, fall back to DSL-derived columns
                    const columns = childModel
                      ? enhancedColumns[childModel] || subTableColumnsMap[childModel]
                      : undefined;
                    const blockKey = block.id || `sub-table-${blockIndex}`;
                    const createEmptyState = resolveCreateSubTableEmptyState(
                      subTableConfig,
                      locale,
                      t,
                    );

                    return (
                      <div key={blockKey} className="mt-6">
                        {block.title && (
                          <h3 className="border-border text-text mb-4 border-b pb-2 text-base font-semibold">
                            {getLocalizedText(block.title, locale, t)}
                          </h3>
                        )}
                        {isEditMode && hasCommands && subTableConfig ? (
                          /* Edit mode with commands: use SubTableViewer for command-based CRUD */
                          <SubTableViewer
                            key={`${blockKey}-${recordPid}`}
                            config={subTableConfig}
                            parentRecordPid={recordPid!}
                            parentRecordData={{ ...formData, pid: recordPid }}
                            token={token || undefined}
                            locale={locale}
                            t={t}
                            isEditable={!subTableConfig.readOnly}
                            runtime={runtime}
                          />
                        ) : !editDataLoaded ? (
                          <div className="text-text-3 py-4 text-center text-sm">
                            {t('common.loading') || 'Loading...'}
                          </div>
                        ) : !isEditMode ? (
                          /* Create mode: show placeholder — lines can be added after saving */
                          <div className="rounded-card border-border-strong flex flex-col items-center border border-dashed px-6 py-8 text-center">
                            <div className="text-text-2 text-sm font-medium">
                              {createEmptyState.title}
                            </div>
                            <div className="text-text-3 mt-2 max-w-xl text-sm leading-6">
                              {createEmptyState.description}
                            </div>
                          </div>
                        ) : columns && columns.length > 0 ? (
                          <SubTable
                            key={`${blockKey}-${recordPid || 'new'}`}
                            columns={columns}
                            value={subTableData[blockKey] || []}
                            onChange={(rows) =>
                              setSubTableData((prev) => ({
                                ...prev,
                                [blockKey]: rows,
                              }))
                            }
                          />
                        ) : null}
                      </div>
                    );
                  })}

                  {/* Form Buttons */}
                  {mainRecordLoaded &&
                    effectiveButtonBlock &&
                    effectiveButtonBlock.buttons &&
                    effectiveButtonBlock.buttons.length > 0 && (
                      <div
                        className={
                          embedded
                            ? 'border-border mt-5 flex flex-wrap justify-end gap-3 border-t pt-4'
                            : fixedActions
                              ? 'border-border bg-panel fixed right-0 bottom-0 z-30 flex flex-wrap items-center justify-end gap-3 border-t px-8 py-4'
                              : `border-border bg-panel rounded-card ${presentation.actions === 'sticky' ? 'sticky bottom-0 z-20' : ''} mt-6 flex flex-wrap justify-end gap-3 border px-5 py-3 shadow-sm`
                        }
                        style={fixedActions ? { left: sidebarWidth } : undefined}
                        data-testid="form-action-footer"
                      >
                        {schema.extension?.footerNote && (
                          <p className="text-text-3 mr-auto text-xs">
                            {getLocalizedText(schema.extension.footerNote, locale, t)}
                          </p>
                        )}
                        {[...effectiveButtonBlock.buttons]
                          .sort(
                            (a: any, b: any) =>
                              Number(Boolean(a.primary)) - Number(Boolean(b.primary)),
                          )
                          .map((button: any) => {
                            if (!canRenderFormButton(button, hasPermission)) return null;
                            // Check button visibility condition
                            if (button.visibleWhen) {
                              const isVisible = evaluateCondition(button.visibleWhen, pageContext);
                              if (!isVisible) {
                                return null;
                              }
                            }

                            return (
                              <button
                                type="button"
                                key={button.code}
                                data-testid={`form-btn-${button.code}`}
                                data-ab-testid={buttonTestId(
                                  'form',
                                  schema?.modelCode || tableName,
                                  button.code,
                                )}
                                onClick={() => handleFormAction(button)}
                                disabled={isFormButtonDisabled(
                                  button,
                                  loading,
                                  submitting,
                                  formInteractionReady,
                                )}
                                className={`rounded-control px-4 py-2 text-sm font-medium ${
                                  button.primary
                                    ? 'bg-accent hover:bg-accent-hover text-white disabled:bg-blue-400'
                                    : 'border-border-strong bg-panel text-text-2 hover:bg-hover border disabled:bg-gray-100'
                                } ${button.danger ? 'bg-red-600 text-white hover:bg-red-700' : ''} disabled:cursor-not-allowed`}
                              >
                                {(loading || submitting) &&
                                  (button.code === 'submit' ||
                                    button.code === 'save' ||
                                    button.primary) && (
                                    <span className="loading loading-spinner loading-sm mr-2"></span>
                                  )}
                                {resolveFormButtonContent(button, locale, t)}
                              </button>
                            );
                          })}
                      </div>
                    )}
                </form>
              </div>
              {hasGuidance && (
                <WorkspaceGuidance
                  config={
                    (isEditMode && schema.extension?.editGuidance) || schema.extension.formGuidance
                  }
                  locale={locale}
                  t={t}
                />
              )}
            </div>
          </div>
        </div>
      </DslFormFillProvider>
    </DataSourceProvider>
  );
}
