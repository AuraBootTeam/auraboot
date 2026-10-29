import type { PromptSubmissionFeedback, PromptSubmissionState } from './promptSubmissionFeedback';
/**
 * FormDialog - Dynamic form dialog for dialog.form action
 *
 * Listens for 'dialog:form' CustomEvent on window and renders a modal
 * with configurable fields (Input, Select, InputNumber).
 *
 * Usage:
 * - Mount <FormDialog /> in the app (e.g. in dynamic.$tableName.tsx)
 * - The dialog.form action in ActionRegistry dispatches 'dialog:form' events
 * - On submit, calls onSubmit(formData) which stores values in stateManager
 * - On cancel, calls onCancel() which rejects the action promise
 */

import {
  createContext,
  useContext,
  useState,
  useEffect,
  useLayoutEffect,
  useRef,
  useCallback,
  type ReactNode,
} from 'react';
import { createPortal, flushSync } from 'react-dom';
import { useLocation } from 'react-router';
import { useAuth } from '~/contexts/AuthContext';
import { useModalFocusTrap } from '~/hooks/useModalFocusTrap';
import { cancelCurrentFormDialog, registerFormDialogScope } from './formDialogLifecycle';
import type { ResolvedPromptPresentation } from './promptPresentation';
import { useI18n } from '~/contexts/I18nContext';
import { useTimezone } from '~/contexts/TimezoneContext';
import FORM_DIALOG_TEXT from './FormDialog.i18n.json';
import { formatInTimezone, parseDateTimeInTimezone } from '~/shared/services/dateTimeFormatService';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import { buildRequiredFieldMessage } from '~/framework/meta/utils/validationMessages';
import MemberPicker from '~/ui/smart/picker/MemberPicker';
import BomUploadReviewField, { type BomUploadReviewValue } from './BomUploadReviewField';
import PagedChoiceField from './PagedChoiceField';
import { UploadedFilesPromptField } from './UploadedFilesPromptField';
import { uploadedFilesConfig, validUploadedFilePids } from './uploadedFiles';
import type { UploadedFilesInputConfig } from '../../schemas/types';
import type { FileUploadState } from '~/ui/smart/display/FileAttachmentField';
import UPLOAD_TEXT from '~/ui/smart/display/StagedFileAttachmentField.i18n.json';

interface VisibilityRule {
  field: string;
  operator?: 'equals' | 'notEquals' | 'in' | 'notIn' | 'empty' | 'notEmpty';
  value?: any;
  values?: any[];
}

interface FormOptionConfig {
  label: string | Record<string, string>;
  value: string;
  description?: string | Record<string, string>;
  disabled?: boolean;
  visibleWhen?: VisibilityRule;
  options?: FormOptionConfig[];
}

interface FormFieldConfig {
  field: string;
  label?: string | Record<string, string>;
  group?: string | Record<string, string>;
  helpText?: string | Record<string, string>;
  type?:
    | 'text'
    | 'select'
    | 'number'
    | 'textarea'
    | 'date'
    | 'datetime'
    | 'multiselect'
    | 'grouped-multiselect'
    | 'segmented'
    | 'checkbox'
    | 'file'
    | 'uploaded-files'
    | 'reference'
    | 'bom-upload-review';
  component?: string;
  props?: Record<string, any>;
  required?: boolean;
  mustBeTrue?: boolean;
  placeholder?: string | Record<string, string>;
  defaultValue?: any;
  searchable?: boolean;
  accept?: string;
  maxBytes?: number;
  fileNameField?: string;
  fileValueMode?: 'text' | 'file';
  uploadedFiles?: UploadedFilesInputConfig;
  visibleWhen?: VisibilityRule;
  dataSource?: {
    type: 'api' | 'static';
    endpoint?: string;
    pagination?: boolean;
    data?: FormOptionConfig[];
  };
}

function localDateTimeValue(value: unknown, timezone: string): string {
  return typeof value === 'string' && value
    ? formatInTimezone(value, 'YYYY-MM-DDTHH:mm', timezone)
    : '';
}

interface FormDialogState {
  open: boolean;
  inputSession?: number;
  requestId?: number;
  submission?: PromptSubmissionFeedback;
  submissionState?: Readonly<PromptSubmissionState>;
  refreshing?: boolean;
  refreshError?: string;
  presentation?: ResolvedPromptPresentation;
  title?: string | Record<string, string>;
  fields: FormFieldConfig[];
  fieldOptions: Record<string, FormOptionConfig[]>;
  defaults: Record<string, any>;
  submitLabel?: string | Record<string, string>;
  onSubmit?: (formData: Record<string, any>) => void;
  onCancel?: () => void;
}

function matchesVisibility(rule: VisibilityRule | undefined, formData: Record<string, any>) {
  if (!rule) return true;
  const actual = formData[rule.field];
  const values = rule.values ?? (Array.isArray(rule.value) ? rule.value : []);
  switch (rule.operator ?? 'equals') {
    case 'notEquals':
      return actual !== rule.value;
    case 'in':
      return values.includes(actual);
    case 'notIn':
      return !values.includes(actual);
    case 'empty':
      return (
        actual === undefined ||
        actual === null ||
        actual === '' ||
        (Array.isArray(actual) && actual.length === 0)
      );
    case 'notEmpty':
      return (
        actual !== undefined &&
        actual !== null &&
        actual !== '' &&
        (!Array.isArray(actual) || actual.length > 0)
      );
    case 'equals':
    default:
      return actual === rule.value;
  }
}

function visibleOptions(
  field: FormFieldConfig,
  fieldOptions: Record<string, FormOptionConfig[]>,
  formData: Record<string, any>,
) {
  return (fieldOptions[field.field] || []).filter((option) =>
    matchesVisibility(option.visibleWhen, formData),
  );
}

function translatedOrFallback(t: (key: string) => string, key: string, fallback: string): string {
  const translated = t(key);
  return translated && translated !== key ? translated : fallback;
}

function isMissing(value: any) {
  return (
    value === undefined ||
    value === null ||
    value === '' ||
    (Array.isArray(value) && value.length === 0) ||
    value === false
  );
}

const SharedFormDialogContext = createContext(false);

/** One listener per application; old page-local mounts remain standalone-compatible. */
export function FormDialogProvider({ children }: { children: ReactNode }) {
  const location = useLocation();
  const { user } = useAuth();
  const scope = `${location.key}:${user?.pid ?? user?.id ?? ''}:${user?.tenantId ?? ''}`;
  useLayoutEffect(() => {
    const controller = new AbortController();
    const unregister = registerFormDialogScope(controller.signal);
    return () => {
      controller.abort();
      unregister();
      cancelCurrentFormDialog();
    };
  }, [scope]);
  return (
    <SharedFormDialogContext.Provider value={true}>
      {children}
      <FormDialogHost key={scope} />
    </SharedFormDialogContext.Provider>
  );
}

export default function FormDialog() {
  const shared = useContext(SharedFormDialogContext);
  return shared ? null : <FormDialogHost />;
}

function FormDialogHost() {
  const { t, locale } = useI18n();
  const { timezone } = useTimezone();
  const [state, setState] = useState<FormDialogState>({
    open: false,
    fields: [],
    fieldOptions: {},
    defaults: {},
  });
  const stateRef = useRef(state);
  stateRef.current = state;
  const containerRef = useRef<HTMLDivElement>(null);
  const feedbackRef = useRef<HTMLDivElement>(null);
  const [formData, setFormData] = useState<Record<string, any>>({});
  const inputSession = useRef(0);
  const uploadStatesRef = useRef<Record<string, FileUploadState>>({});
  const [uploadStates, setUploadStates] = useState<Record<string, FileUploadState>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [dateTimeErrors, setDateTimeErrors] = useState<Record<string, string>>({});
  const [dateTimeDrafts, setDateTimeDrafts] = useState<Record<string, string>>({});
  const [searchTerms, setSearchTerms] = useState<Record<string, string>>({});
  const firstInputRef = useRef<HTMLElement | null>(null);
  useLayoutEffect(() => {
    if (!state.open) return;
    // Includes segmented choices and picker controls, never the header X.
    const wrappers = [
      ...(containerRef.current?.querySelectorAll<HTMLElement>('[data-prompt-field]') ?? []),
    ];
    firstInputRef.current =
      wrappers
        .map((wrapper) =>
          wrapper.querySelector<HTMLElement>(
            'input:not([disabled]):not([type="hidden"]):not([tabindex="-1"]),select:not([disabled]),textarea:not([disabled]),button:not([disabled]),[tabindex]:not([tabindex="-1"])',
          ),
        )
        .find((element): element is HTMLElement => Boolean(element)) ?? null;
  }, [state.open, state.fields, formData]);

  // Listen for dialog:form events
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (!detail) return;

      stateRef.current.submission?.dispose();
      stateRef.current.onCancel?.();
      const defaults = detail.defaults || {};
      const next: FormDialogState = {
        open: true,
        inputSession: ++inputSession.current,
        requestId: detail.requestId,
        submission: detail.submission,
        submissionState: detail.submission?.getState(),
        presentation: detail.presentation,
        title: detail.title,
        fields: detail.fields || [],
        fieldOptions: detail.fieldOptions || {},
        defaults,
        submitLabel: detail.submitLabel,
        onSubmit: detail.onSubmit,
        onCancel: detail.onCancel,
      };
      stateRef.current = next;
      setState(next);
      setFormData({ ...defaults });
      uploadStatesRef.current = {};
      setUploadStates({});
      setErrors({});
      setDateTimeErrors({});
      setDateTimeDrafts({});
      setSearchTerms({});
    };

    const cancel = (event: Event) => {
      const id = (event as CustomEvent<{ requestId?: number }>).detail?.requestId;
      if (id !== undefined && id !== stateRef.current.requestId) return;
      const current = stateRef.current;
      if (!current.open) return;
      stateRef.current = { ...current, open: false, onCancel: undefined, onSubmit: undefined };
      setState(stateRef.current);
      current.onCancel?.();
      current.submission?.dispose();
    };
    window.addEventListener('dialog:form', handler);
    window.addEventListener('dialog:form:cancel', cancel);
    return () => {
      window.removeEventListener('dialog:form', handler);
      window.removeEventListener('dialog:form:cancel', cancel);
      const current = stateRef.current;
      if (current.open) current.onCancel?.();
      current.submission?.dispose();
    };
  }, []);

  const handleCancel = useCallback(() => {
    const current = stateRef.current;
    if (
      !current.open ||
      current.submission?.getState().phase === 'submitting' ||
      current.refreshing
    )
      return;
    stateRef.current = { ...current, open: false, onCancel: undefined, onSubmit: undefined };
    setState(stateRef.current);
    current.onCancel?.();
    current.submission?.dispose();
  }, []);
  useModalFocusTrap(state.open, containerRef, handleCancel, firstInputRef);
  useLayoutEffect(() => {
    if (!state.open || state.submissionState?.phase !== 'failed') return;
    // Reveal a newly settled failure even when a long summary was scrolled past.
    // Field edits keep the same feedback object and do not steal focus again.
    feedbackRef.current?.scrollIntoView({ block: 'start', inline: 'nearest' });
    feedbackRef.current?.focus({ preventScroll: true });
  }, [state.open, state.inputSession, state.submissionState]);
  useEffect(() => {
    const submission = state.submission;
    if (!submission) return;
    let subscribing = true;
    const unsubscribe = submission.subscribe((result) => {
      const current = stateRef.current;
      if (
        !current.open ||
        current.submission !== submission ||
        current.requestId !== submission.requestId
      )
        return;
      if (result.phase === 'succeeded') {
        const closed = { ...current, open: false, onSubmit: undefined, onCancel: undefined };
        stateRef.current = closed;
        // User submissions have a mounted subscriber. Commit the close before
        // the executor proceeds to any fallible post-write read/download.
        const close = () => {
          if (stateRef.current === closed) setState(closed);
        };
        if (subscribing) close();
        else flushSync(close);
        submission.dispose();
      } else {
        stateRef.current = { ...current, submissionState: result };
        setState(stateRef.current);
      }
    });
    subscribing = false;
    return unsubscribe;
  }, [state.submission]);

  const handleCloseAndRefresh = useCallback(async () => {
    const current = stateRef.current,
      submission = current.submission;
    if (
      !current.open ||
      !submission?.refresh ||
      current.refreshing ||
      !['failed', 'accepted'].includes(submission.getState().phase)
    )
      return;
    stateRef.current = { ...current, refreshing: true, refreshError: undefined };
    setState(stateRef.current);
    try {
      await submission.refresh();
      if (stateRef.current.submission !== submission || submission.getState().phase === 'disposed')
        return;
      stateRef.current = { ...stateRef.current, refreshing: false };
      setState(stateRef.current);
      handleCancel();
    } catch {
      if (stateRef.current.submission !== submission || submission.getState().phase === 'disposed')
        return;
      stateRef.current = {
        ...stateRef.current,
        refreshing: false,
        refreshError: locale.startsWith('zh')
          ? '刷新失败，输入仍保留。请重试关闭并刷新。'
          : 'Refresh failed. Your inputs are retained. Try Close and refresh again.',
      };
      setState(stateRef.current);
    }
  }, [handleCancel, locale]);

  const handleSubmit = useCallback(() => {
    if (
      !stateRef.current.open ||
      (stateRef.current.submission && stateRef.current.submission.getState().phase !== 'idle')
    )
      return;
    const visibleFields = state.fields.filter((field) =>
      matchesVisibility(field.visibleWhen, formData),
    );
    const newErrors: Record<string, string> = Object.fromEntries(
      Object.entries(dateTimeErrors).filter(([key]) =>
        visibleFields.some((field) => field.field === key),
      ),
    );
    for (const field of visibleFields) {
      const value = formData[field.field];
      if (field.type === 'uploaded-files') {
        try {
          const config = uploadedFilesConfig(field.uploadedFiles);
          if (
            !validUploadedFilePids(value ?? [], config.maxCount) ||
            uploadStatesRef.current[field.field]?.blocked !== false
          )
            newErrors[field.field] = getLocalizedText(UPLOAD_TEXT.resolve, locale, t);
        } catch {
          newErrors[field.field] = getLocalizedText(UPLOAD_TEXT.invalid, locale, t);
        }
      }
      if (field.required) {
        if (isMissing(value) || (field.mustBeTrue && value !== true)) {
          const label = field.label ? getLocalizedText(field.label, locale, t) : field.field;
          newErrors[field.field] = buildRequiredFieldMessage(label, {
            dataType: field.type,
            component: field.type,
            locale,
            t,
          });
        }
      }
      if (field.type === 'grouped-multiselect') {
        const groups = visibleOptions(field, state.fieldOptions, formData);
        const groupKey = field.props?.groupField || 'group';
        const valuesKey = field.props?.valuesField || 'values';
        const selections = Array.isArray(value) ? value : [];
        const valid =
          groups.length > 0 &&
          selections.length === groups.length &&
          groups.every((group) => {
            const matching = selections.filter((item: any) => item?.[groupKey] === group.value);
            if (matching.length !== 1 || !Array.isArray(matching[0][valuesKey])) return false;
            const values = matching[0][valuesKey];
            return (
              !group.disabled &&
              values.length > 0 &&
              new Set(values).size === values.length &&
              values.every((selected: unknown) =>
                (group.options || []).some(
                  (option) => option.value === selected && !option.disabled,
                ),
              )
            );
          });
        if (!valid) {
          const label = field.label ? getLocalizedText(field.label, locale, t) : field.field;
          newErrors[field.field] = buildRequiredFieldMessage(label, {
            dataType: 'select',
            locale,
            t,
          });
        }
      }
      if (field.type === 'bom-upload-review') {
        const review = value as BomUploadReviewValue | undefined;
        if (review && review.valid !== true) {
          newErrors[field.field] =
            review.validationMessage ||
            (locale.startsWith('zh')
              ? '请完成 BOM 列选择。'
              : 'Complete the BOM column selection.');
        }
      }
      if (['select', 'segmented'].includes(field.type || '') && value != null && value !== '') {
        const options = visibleOptions(field, state.fieldOptions, formData);
        if (!options.some((option) => option.value === value && !option.disabled)) {
          const label = field.label ? getLocalizedText(field.label, locale, t) : field.field;
          newErrors[field.field] = buildRequiredFieldMessage(label, {
            dataType: field.type,
            component: field.type,
            locale,
            t,
          });
        }
      }
    }

    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      const firstError = Object.keys(newErrors)[0];
      const wrapper = [
        ...(containerRef.current?.querySelectorAll<HTMLElement>('[data-prompt-field]') ?? []),
      ].find((element) => element.dataset.promptField === firstError);
      const input = wrapper?.querySelector<HTMLElement>('input,select,textarea,button,[tabindex]');
      input?.focus({ preventScroll: true });
      input?.scrollIntoView?.({ block: 'nearest' });
      return;
    }

    const submitted = Object.fromEntries(
      visibleFields.flatMap((field) => {
        const entries: [string, any][] = [
          [
            field.field,
            field.type === 'uploaded-files' ? (formData[field.field] ?? []) : formData[field.field],
          ],
        ];
        if (field.type === 'file' && field.fileNameField) {
          entries.push([field.fileNameField, formData[field.fileNameField]]);
        }
        return entries;
      }),
    );
    const current = stateRef.current;
    if (!current.submission) {
      stateRef.current = { ...current, open: false, onSubmit: undefined, onCancel: undefined };
      setState(stateRef.current);
    }
    // The original promise resolves once; only the native executor may acknowledge it.
    current.onSubmit?.(submitted);
  }, [state.fields, state.onSubmit, formData, dateTimeErrors, locale, t]);

  const updateField = useCallback(
    (fieldName: string, value: any) => {
      if (
        stateRef.current.submission?.getState().phase === 'submitting' ||
        stateRef.current.refreshing
      )
        return;
      const dependentFields = [
        ...new Set(
          state.fields
            .filter((field) => field.field !== fieldName && field.visibleWhen?.field === fieldName)
            .map((field) => field.field),
        ),
      ];
      setFormData((prev) => {
        const next = { ...prev, [fieldName]: value };
        for (const dependentField of dependentFields) {
          const previousVariant = state.fields.findIndex(
            (field) => field.field === dependentField && matchesVisibility(field.visibleWhen, prev),
          );
          const nextVariant = state.fields.findIndex(
            (field) => field.field === dependentField && matchesVisibility(field.visibleWhen, next),
          );
          if (previousVariant >= 0 && previousVariant !== nextVariant) delete next[dependentField];
        }
        return next;
      });
      // Clear error when user starts typing
      setErrors((prev) => {
        const next = { ...prev };
        delete next[fieldName];
        dependentFields.forEach((field) => delete next[field]);
        return next;
      });
      setSearchTerms((prev) => {
        const next = { ...prev };
        dependentFields.forEach((field) => delete next[field]);
        return next;
      });
    },
    [state.fields],
  );

  const handleFileSelection = useCallback(
    async (field: FormFieldConfig, file?: File) => {
      if (!file) {
        updateField(field.field, field.fileValueMode === 'file' ? null : '');
        if (field.fileNameField) updateField(field.fileNameField, '');
        return;
      }
      const maxBytes = field.maxBytes;
      if (maxBytes && file.size > maxBytes) {
        setErrors((prev) => ({
          ...prev,
          [field.field]: locale.startsWith('zh')
            ? `文件不能超过 ${Math.ceil(maxBytes / 1024 / 1024)} MB`
            : `File must not exceed ${Math.ceil(maxBytes / 1024 / 1024)} MB`,
        }));
        return;
      }
      try {
        updateField(field.field, field.fileValueMode === 'file' ? file : await file.text());
        if (field.fileNameField) updateField(field.fileNameField, file.name);
      } catch {
        setErrors((prev) => ({
          ...prev,
          [field.field]: locale.startsWith('zh') ? '无法读取文件' : 'Unable to read file',
        }));
      }
    },
    [locale, updateField],
  );

  if (!state.open) return null;

  const dialogTitle = state.title
    ? getLocalizedText(state.title, locale, t)
    : t('common.form') || 'Form';
  const configuredFields = state.fields.map((field, sourceIndex) => ({ field, sourceIndex }));
  const visibleFields = configuredFields.filter(({ field }) =>
    matchesVisibility(field.visibleWhen, formData),
  );
  const configuredSections = configuredFields.reduce<
    Array<{
      group?: string | Record<string, string>;
      groupKey: string;
      runStartSourceIndex: number;
      fields: typeof configuredFields;
    }>
  >((sections, entry) => {
    const groupKey = entry.field.group ? JSON.stringify(entry.field.group) : '__ungrouped__';
    const previous = sections.at(-1);
    if (previous?.groupKey === groupKey) {
      previous.fields.push(entry);
    } else {
      sections.push({
        group: entry.field.group,
        groupKey,
        runStartSourceIndex: entry.sourceIndex,
        fields: [entry],
      });
    }
    return sections;
  }, []);
  const formSections = configuredSections
    .map((section) => ({
      ...section,
      fields: section.fields.filter(({ field }) => matchesVisibility(field.visibleWhen, formData)),
    }))
    .filter((section) => section.fields.length > 0);
  const hasBusinessGroups = formSections.some((section) => section.group);
  const submissionState = state.submissionState;
  const submitting = submissionState?.phase === 'submitting';
  const reviewRequired =
    submissionState?.phase === 'failed' || submissionState?.phase === 'accepted';

  return createPortal(
    <div
      ref={containerRef}
      tabIndex={-1}
      className={`fixed inset-0 z-[70] flex items-center justify-center ${state.presentation ? 'aura-dialog-presentation' : ''}`}
      data-testid="form-dialog"
      role="dialog"
      aria-modal="true"
      aria-busy={submitting || state.refreshing || undefined}
      aria-labelledby="form-dialog-title"
    >
      {/* Backdrop */}
      <div
        className={`absolute inset-0 transition-opacity duration-200 ${state.presentation ? 'aura-dialog-presentation__backdrop' : 'bg-black/50'}`}
        data-testid="form-dialog-backdrop"
        onClick={state.presentation?.closeOnBackdrop === false ? undefined : handleCancel}
      />

      {/* Dialog */}
      <div
        data-testid="form-dialog-panel"
        style={
          state.presentation?.width
            ? ({ '--dialog-width': `${state.presentation.width}px` } as React.CSSProperties)
            : undefined
        }
        className={`${state.presentation ? 'aura-dialog-presentation__panel' : ''} relative mx-4 flex max-h-[calc(100vh-2rem)] w-full scale-100 transform flex-col rounded-lg bg-white opacity-100 shadow-xl transition-all duration-200 dark:bg-gray-800 ${
          state.fields.some((field) => field.type === 'bom-upload-review')
            ? 'max-w-6xl'
            : hasBusinessGroups
              ? 'max-w-2xl'
              : 'max-w-lg'
        }`}
      >
        {/* Header */}
        <div
          className={`flex shrink-0 items-start justify-between gap-4 border-b border-gray-200 px-6 pt-6 pb-4 dark:border-gray-700 ${state.presentation ? 'aura-dialog-presentation__header' : ''}`}
        >
          <h3
            id="form-dialog-title"
            className="text-lg font-semibold text-gray-900 dark:text-white"
          >
            {dialogTitle}
          </h3>
          {state.presentation?.showClose && (
            <button
              type="button"
              data-testid="form-dialog-close"
              onClick={handleCancel}
              disabled={submitting || state.refreshing}
              aria-label={translatedOrFallback(
                t,
                'common.close',
                locale.startsWith('zh') ? '关闭' : 'Close',
              )}
              className="rounded-control text-text-3 hover:bg-hover px-2 text-xl focus-visible:shadow-[var(--shadow-focus)]"
            >
              ×
            </button>
          )}
        </div>

        {/* Body - Form Fields */}
        <div
          className={`min-h-0 flex-1 [scrollbar-gutter:stable] space-y-4 overflow-y-auto px-6 py-4 ${state.presentation ? 'aura-dialog-presentation__body' : ''}`}
          data-testid="form-dialog-body"
        >
          {submissionState && submissionState.phase !== 'idle' && (
            <div
              ref={feedbackRef}
              tabIndex={-1}
              role={submissionState.phase === 'failed' ? 'alert' : 'status'}
              aria-live="polite"
              data-testid="form-dialog-submit-feedback"
              data-submit-state={submissionState.phase}
              data-http-status={submissionState.httpStatus}
              className="rounded-control border-border bg-subtle text-text border p-3 text-sm"
            >
              <p>
                {submitting
                  ? locale.startsWith('zh')
                    ? '正在提交，请等待结果。'
                    : 'Submitting. Please wait for the result.'
                  : submissionState.message}
              </p>
              {submissionState.phase === 'failed' && (
                <p className="mt-2">
                  {locale.startsWith('zh')
                    ? state.submission?.refresh
                      ? '下方输入已保留。请关闭并刷新核对，再从详情重新发起操作。'
                      : '下方输入已保留。请关闭后从原页面刷新核对，再重新发起操作。'
                    : state.submission?.refresh
                      ? 'Your inputs are retained below. Close and refresh to review, then start a new action from the detail.'
                      : 'Your inputs are retained below. Close, refresh from the original page, then start a new action.'}
                </p>
              )}
              {state.refreshError && <p className="text-status-red mt-2">{state.refreshError}</p>}
            </div>
          )}
          {state.presentation?.summary && (
            <dl
              data-testid="form-dialog-summary"
              className={`rounded-card border-border bg-subtle grid gap-x-6 gap-y-3 border p-4 ${state.presentation.summary.columns === 2 ? 'grid-cols-2' : 'grid-cols-1'}`}
            >
              {state.presentation.summary.items.map((item) => (
                <div key={item.field} className="min-w-0">
                  <dt className="text-text-3 text-xs">{getLocalizedText(item.label, locale, t)}</dt>
                  <dd
                    className="text-text mt-1 text-sm [overflow-wrap:anywhere] break-words whitespace-pre-wrap"
                    data-summary-field={item.field}
                  >
                    {getLocalizedText(item.value, locale, t)}
                  </dd>
                </div>
              ))}
            </dl>
          )}
          <fieldset
            disabled={submitting || state.refreshing}
            className="m-0 min-w-0 space-y-4 border-0 p-0"
          >
            {formSections.map((section) => {
              const renderedFields = section.fields.map(({ field, sourceIndex }) => {
                const label = field.label ? getLocalizedText(field.label, locale, t) : field.field;
                const placeholder = field.placeholder
                  ? getLocalizedText(field.placeholder, locale, t)
                  : '';
                const helpText = field.helpText ? getLocalizedText(field.helpText, locale, t) : '';
                const fieldType = field.type || 'text';
                const component = field.component?.toLocaleLowerCase();
                const options = visibleOptions(field, state.fieldOptions, formData);
                const error = errors[field.field];
                const isFirst = sourceIndex === visibleFields[0]?.sourceIndex;
                const searchTerm = searchTerms[field.field] || '';
                const filteredOptions =
                  field.searchable && searchTerm
                    ? options.filter((option) => {
                        const optionLabel = getLocalizedText(option.label, locale, t);
                        const description = option.description
                          ? getLocalizedText(option.description, locale, t)
                          : '';
                        const keyword = searchTerm.toLocaleLowerCase();
                        return `${optionLabel} ${description}`
                          .toLocaleLowerCase()
                          .includes(keyword);
                      })
                    : options;

                return (
                  <div key={`${field.field}-${sourceIndex}`} data-prompt-field={field.field}>
                    <label
                      htmlFor={
                        component !== 'memberpicker' &&
                        ['date', 'datetime', 'select', 'number', 'textarea', 'text'].includes(
                          fieldType,
                        )
                          ? `form-dialog-${field.field}-${sourceIndex}`
                          : undefined
                      }
                      className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300"
                    >
                      {label}
                      {field.required && (
                        <span aria-hidden="true" className="ml-1 text-red-500">
                          *
                        </span>
                      )}
                    </label>

                    {component === 'memberpicker' ? (
                      <MemberPicker
                        name={field.field}
                        required={field.required}
                        value={formData[field.field]}
                        onChange={(value) => updateField(field.field, value)}
                        multiple={field.props?.multiple === true}
                        placeholder={placeholder || undefined}
                      />
                    ) : fieldType === 'select' ? (
                      <select
                        id={`form-dialog-${field.field}-${sourceIndex}`}
                        aria-required={field.required || undefined}
                        ref={
                          isFirst
                            ? (firstInputRef as React.RefObject<HTMLSelectElement>)
                            : undefined
                        }
                        data-testid={`form-dialog-field-${field.field}`}
                        value={formData[field.field] ?? ''}
                        onChange={(e) => updateField(field.field, e.target.value)}
                        className={`w-full rounded-lg border bg-white px-3 py-2 text-sm text-gray-900 transition-colors dark:bg-gray-700 dark:text-white ${
                          error
                            ? 'border-red-500 focus:border-red-500 focus:ring-red-500'
                            : 'focus:border-accent focus:ring-accent border-gray-300 dark:border-gray-600'
                        } focus:ring-2 focus:outline-none`}
                      >
                        <option value="">
                          {placeholder ||
                            `${translatedOrFallback(
                              t,
                              'common.select',
                              locale.startsWith('zh') ? '请选择' : 'Select',
                            )}...`}
                        </option>
                        {options.map((opt) => (
                          <option key={opt.value} value={opt.value}>
                            {getLocalizedText(opt.label, locale, t)}
                          </option>
                        ))}
                      </select>
                    ) : fieldType === 'segmented' ? (
                      <div
                        role="radiogroup"
                        data-testid={`form-dialog-field-${field.field}`}
                        className="grid grid-cols-2 gap-2 sm:grid-cols-3"
                      >
                        {options.map((option) => {
                          const selected = formData[field.field] === option.value;
                          return (
                            <button
                              key={option.value}
                              type="button"
                              role="radio"
                              aria-checked={selected}
                              disabled={option.disabled}
                              onClick={() => updateField(field.field, option.value)}
                              className={`focus:ring-accent min-h-10 rounded-md border px-3 py-2 text-sm font-medium transition-colors focus:ring-2 focus:outline-none disabled:cursor-not-allowed disabled:opacity-50 ${
                                selected
                                  ? 'border-blue-600 bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-200'
                                  : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-200 dark:hover:bg-gray-600'
                              }`}
                            >
                              {getLocalizedText(option.label, locale, t)}
                            </button>
                          );
                        })}
                      </div>
                    ) : fieldType === 'grouped-multiselect' ? (
                      <div
                        className={`max-h-80 space-y-3 overflow-y-auto rounded-lg border p-3 ${error ? 'border-red-500' : 'border-gray-300 dark:border-gray-600'}`}
                      >
                        {options.map((group) => {
                          const groupKey = field.props?.groupField || 'group';
                          const valuesKey = field.props?.valuesField || 'values';
                          const selections = Array.isArray(formData[field.field])
                            ? formData[field.field]
                            : [];
                          const chosen =
                            selections.find((item: any) => item?.[groupKey] === group.value)?.[
                              valuesKey
                            ] || [];
                          return (
                            <fieldset
                              key={group.value}
                              className="rounded-md border border-gray-200 p-3 dark:border-gray-600"
                              disabled={group.disabled}
                            >
                              <legend className="px-1 text-sm font-medium text-gray-900 dark:text-white">
                                {getLocalizedText(group.label, locale, t)}
                              </legend>
                              {group.description && (
                                <p className="mb-2 text-xs text-gray-500 dark:text-gray-300">
                                  {getLocalizedText(group.description, locale, t)}
                                </p>
                              )}
                              {(group.options || []).length === 0 && (
                                <p className="py-2 text-sm text-gray-500 dark:text-gray-300">
                                  {translatedOrFallback(
                                    t,
                                    'common.noData',
                                    locale.startsWith('zh') ? '暂无可选项' : 'No available options',
                                  )}
                                </p>
                              )}
                              {(group.options || []).map((option, optionIndex) => (
                                <label
                                  key={option.value}
                                  className="flex min-h-11 cursor-pointer items-center gap-3 text-sm text-gray-900 dark:text-white"
                                >
                                  <input
                                    type="checkbox"
                                    disabled={option.disabled}
                                    ref={
                                      isFirst && group === options[0] && optionIndex === 0
                                        ? (firstInputRef as React.RefObject<HTMLInputElement>)
                                        : undefined
                                    }
                                    checked={Array.isArray(chosen) && chosen.includes(option.value)}
                                    onChange={() => {
                                      const current = Array.isArray(chosen) ? chosen : [];
                                      const next = current.includes(option.value)
                                        ? current.filter((item: string) => item !== option.value)
                                        : [...current, option.value];
                                      updateField(
                                        field.field,
                                        options.map((entry) => ({
                                          [groupKey]: entry.value,
                                          [valuesKey]:
                                            entry.value === group.value
                                              ? next
                                              : selections.find(
                                                  (item: any) => item?.[groupKey] === entry.value,
                                                )?.[valuesKey] || [],
                                        })),
                                      );
                                    }}
                                  />
                                  <span>{getLocalizedText(option.label, locale, t)}</span>
                                </label>
                              ))}
                            </fieldset>
                          );
                        })}
                      </div>
                    ) : fieldType === 'multiselect' &&
                      field.dataSource?.pagination &&
                      field.dataSource.endpoint ? (
                      <div data-testid={`form-dialog-field-${field.field}`}>
                        <PagedChoiceField
                          endpoint={field.dataSource.endpoint}
                          label={getLocalizedText(field.label, locale, t)}
                          value={Array.isArray(formData[field.field]) ? formData[field.field] : []}
                          onChange={(value) => updateField(field.field, value)}
                        />
                      </div>
                    ) : fieldType === 'multiselect' ? (
                      <div
                        data-testid={`form-dialog-field-${field.field}`}
                        className={`overflow-hidden rounded-lg border ${
                          error ? 'border-red-500' : 'border-gray-300 dark:border-gray-600'
                        }`}
                      >
                        {field.searchable && (
                          <input
                            ref={
                              isFirst
                                ? (firstInputRef as React.RefObject<HTMLInputElement>)
                                : undefined
                            }
                            type="search"
                            value={searchTerm}
                            onChange={(event) =>
                              setSearchTerms((prev) => ({
                                ...prev,
                                [field.field]: event.target.value,
                              }))
                            }
                            placeholder={placeholder}
                            className="focus:ring-accent w-full border-0 border-b border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:ring-2 focus:outline-none focus:ring-inset dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                          />
                        )}
                        <div className="max-h-48 overflow-y-auto bg-white dark:bg-gray-700">
                          {filteredOptions.map((option) => {
                            const selected =
                              Array.isArray(formData[field.field]) &&
                              formData[field.field].includes(option.value);
                            return (
                              <label
                                key={option.value}
                                className={`flex min-h-11 cursor-pointer items-start gap-3 border-b border-gray-100 px-3 py-2 last:border-b-0 hover:bg-gray-50 dark:border-gray-600 dark:hover:bg-gray-600 ${
                                  option.disabled ? 'cursor-not-allowed opacity-50' : ''
                                }`}
                              >
                                <input
                                  type="checkbox"
                                  checked={selected}
                                  disabled={option.disabled}
                                  onChange={() => {
                                    const current = Array.isArray(formData[field.field])
                                      ? formData[field.field]
                                      : [];
                                    updateField(
                                      field.field,
                                      selected
                                        ? current.filter((value: string) => value !== option.value)
                                        : [...current, option.value],
                                    );
                                  }}
                                  className="focus:ring-accent mt-0.5 h-4 w-4 rounded border-gray-300 text-blue-600"
                                />
                                <span className="min-w-0">
                                  <span className="block text-sm font-medium text-gray-900 dark:text-white">
                                    {getLocalizedText(option.label, locale, t)}
                                  </span>
                                  {option.description && (
                                    <span className="mt-0.5 block text-xs text-gray-500 dark:text-gray-300">
                                      {getLocalizedText(option.description, locale, t)}
                                    </span>
                                  )}
                                </span>
                              </label>
                            );
                          })}
                          {filteredOptions.length === 0 && (
                            <div className="px-3 py-6 text-center text-sm text-gray-500 dark:text-gray-300">
                              {t('common.noData') || 'No data'}
                            </div>
                          )}
                        </div>
                      </div>
                    ) : fieldType === 'checkbox' ? (
                      <label
                        data-testid={`form-dialog-field-${field.field}`}
                        className={`flex min-h-11 cursor-pointer items-center gap-3 rounded-lg border px-3 py-2 ${
                          error
                            ? 'border-red-500'
                            : 'border-gray-300 bg-white dark:border-gray-600 dark:bg-gray-700'
                        }`}
                      >
                        <input
                          ref={
                            isFirst
                              ? (firstInputRef as React.RefObject<HTMLInputElement>)
                              : undefined
                          }
                          type="checkbox"
                          checked={formData[field.field] === true}
                          onChange={(event) => updateField(field.field, event.target.checked)}
                          className="focus:ring-accent h-4 w-4 rounded border-gray-300 text-blue-600"
                        />
                        <span className="text-sm text-gray-700 dark:text-gray-200">
                          {placeholder || label}
                        </span>
                      </label>
                    ) : fieldType === 'bom-upload-review' ? (
                      <BomUploadReviewField
                        fieldName={field.field}
                        accept={field.accept}
                        maxBytes={field.maxBytes}
                        roleOptions={field.props?.roleOptions}
                        previewRowCount={field.props?.previewRowCount}
                        onChange={(value) => updateField(field.field, value)}
                      />
                    ) : fieldType === 'uploaded-files' ? (
                      <UploadedFilesPromptField
                        key={`${state.inputSession}:${field.field}`}
                        config={field.uploadedFiles}
                        value={formData[field.field]}
                        onChange={(value) => {
                          if (
                            stateRef.current.open &&
                            stateRef.current.inputSession === state.inputSession
                          )
                            updateField(field.field, value);
                        }}
                        onStatus={(status) => {
                          if (
                            !stateRef.current.open ||
                            stateRef.current.inputSession !== state.inputSession
                          )
                            return;
                          uploadStatesRef.current = {
                            ...uploadStatesRef.current,
                            [field.field]: status,
                          };
                          setUploadStates(uploadStatesRef.current);
                        }}
                      />
                    ) : fieldType === 'file' ? (
                      <div
                        data-testid={`form-dialog-dropzone-${field.field}`}
                        className={`rounded-card bg-subtle hover:border-accent hover:bg-accent-weak relative border-2 border-dashed p-5 text-center transition-colors ${
                          error ? 'border-status-red' : 'border-border-strong'
                        }`}
                        onDragOver={(event) => event.preventDefault()}
                        onDrop={(event) => {
                          event.preventDefault();
                          void handleFileSelection(field, event.dataTransfer.files?.[0]);
                        }}
                      >
                        <input
                          ref={
                            isFirst
                              ? (firstInputRef as React.RefObject<HTMLInputElement>)
                              : undefined
                          }
                          id={`form-dialog-file-${field.field}`}
                          data-testid={`form-dialog-field-${field.field}`}
                          type="file"
                          accept={field.accept}
                          onChange={(event) => {
                            void handleFileSelection(field, event.target.files?.[0]);
                            event.target.value = '';
                          }}
                          className="sr-only"
                        />
                        <label
                          htmlFor={`form-dialog-file-${field.field}`}
                          className="flex cursor-pointer flex-col items-center"
                        >
                          <span className="text-body text-text-2 font-medium">
                            {field.fileNameField && formData[field.fileNameField]
                              ? formData[field.fileNameField]
                              : translatedOrFallback(
                                  t,
                                  'upload.button',
                                  locale.startsWith('zh')
                                    ? '点击选择文件'
                                    : 'Click to select a file',
                                )}
                          </span>
                          <span className="text-aux text-text-3 mt-1">
                            {translatedOrFallback(
                              t,
                              'upload.dragHint',
                              locale.startsWith('zh')
                                ? '或将文件拖拽到此处'
                                : 'or drag and drop it here',
                            )}
                          </span>
                        </label>
                      </div>
                    ) : fieldType === 'number' ? (
                      <input
                        id={`form-dialog-${field.field}-${sourceIndex}`}
                        aria-required={field.required || undefined}
                        ref={
                          isFirst ? (firstInputRef as React.RefObject<HTMLInputElement>) : undefined
                        }
                        data-testid={`form-dialog-field-${field.field}`}
                        type="number"
                        value={formData[field.field] ?? ''}
                        onChange={(e) =>
                          updateField(
                            field.field,
                            e.target.value === '' ? '' : Number(e.target.value),
                          )
                        }
                        placeholder={placeholder}
                        className={`w-full rounded-lg border bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 transition-colors dark:bg-gray-700 dark:text-white dark:placeholder-gray-500 ${
                          error
                            ? 'border-red-500 focus:border-red-500 focus:ring-red-500'
                            : 'focus:border-accent focus:ring-accent border-gray-300 dark:border-gray-600'
                        } focus:ring-2 focus:outline-none`}
                      />
                    ) : fieldType === 'date' || fieldType === 'datetime' ? (
                      <input
                        id={`form-dialog-${field.field}-${sourceIndex}`}
                        aria-required={field.required || undefined}
                        ref={
                          isFirst ? (firstInputRef as React.RefObject<HTMLInputElement>) : undefined
                        }
                        data-testid={`form-dialog-field-${field.field}`}
                        type={fieldType === 'date' ? 'date' : 'datetime-local'}
                        value={
                          fieldType === 'date'
                            ? (formData[field.field] ?? '')
                            : (dateTimeDrafts[field.field] ??
                              localDateTimeValue(formData[field.field], timezone))
                        }
                        onChange={(event) => {
                          const wallTime = event.target.value;
                          if (fieldType === 'date') {
                            updateField(field.field, wallTime);
                            return;
                          }
                          const instant = parseDateTimeInTimezone(wallTime, timezone);
                          setDateTimeDrafts((previous) => ({
                            ...previous,
                            [field.field]: wallTime,
                          }));
                          if (wallTime && !instant) {
                            const message = getLocalizedText(
                              FORM_DIALOG_TEXT.invalidDateTime,
                              locale,
                              t,
                            );
                            setDateTimeErrors((previous) => ({
                              ...previous,
                              [field.field]: message,
                            }));
                            setErrors((previous) => ({ ...previous, [field.field]: message }));
                            return;
                          }
                          setDateTimeErrors((previous) => {
                            const next = { ...previous };
                            delete next[field.field];
                            return next;
                          });
                          updateField(field.field, instant);
                        }}
                        aria-invalid={!!error}
                        className={`w-full rounded-lg border bg-white px-3 py-2 text-sm text-gray-900 dark:bg-gray-700 dark:text-white ${
                          error
                            ? 'border-red-500 focus:border-red-500 focus:ring-red-500'
                            : 'focus:border-accent focus:ring-accent border-gray-300 dark:border-gray-600'
                        } focus:ring-2 focus:outline-none`}
                      />
                    ) : fieldType === 'textarea' ? (
                      <textarea
                        id={`form-dialog-${field.field}-${sourceIndex}`}
                        aria-required={field.required || undefined}
                        ref={
                          isFirst
                            ? (firstInputRef as React.RefObject<HTMLTextAreaElement>)
                            : undefined
                        }
                        aria-label={label}
                        data-testid={`form-dialog-field-${field.field}`}
                        value={formData[field.field] ?? ''}
                        onChange={(e) => updateField(field.field, e.target.value)}
                        placeholder={placeholder}
                        rows={3}
                        className={`w-full rounded-lg border bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 transition-colors dark:bg-gray-700 dark:text-white dark:placeholder-gray-500 ${
                          error
                            ? 'border-red-500 focus:border-red-500 focus:ring-red-500'
                            : 'focus:border-accent focus:ring-accent border-gray-300 dark:border-gray-600'
                        } resize-none focus:ring-2 focus:outline-none`}
                      />
                    ) : (
                      <input
                        id={`form-dialog-${field.field}-${sourceIndex}`}
                        aria-required={field.required || undefined}
                        ref={
                          isFirst ? (firstInputRef as React.RefObject<HTMLInputElement>) : undefined
                        }
                        data-testid={`form-dialog-field-${field.field}`}
                        type="text"
                        value={formData[field.field] ?? ''}
                        onChange={(e) => updateField(field.field, e.target.value)}
                        placeholder={placeholder}
                        className={`w-full rounded-lg border bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 transition-colors dark:bg-gray-700 dark:text-white dark:placeholder-gray-500 ${
                          error
                            ? 'border-red-500 focus:border-red-500 focus:ring-red-500'
                            : 'focus:border-accent focus:ring-accent border-gray-300 dark:border-gray-600'
                        } focus:ring-2 focus:outline-none`}
                      />
                    )}

                    {error && <p className="mt-1 text-xs text-red-500">{error}</p>}
                    {helpText && (
                      <p
                        className="mt-1 text-xs leading-5 text-gray-500 dark:text-gray-400"
                        data-testid={`form-dialog-help-${field.field}`}
                      >
                        {helpText}
                      </p>
                    )}
                  </div>
                );
              });
              if (!section.group) {
                return (
                  <div
                    key={`${section.groupKey}-${section.runStartSourceIndex}`}
                    className="space-y-4"
                  >
                    {renderedFields}
                  </div>
                );
              }
              const groupTitle = getLocalizedText(section.group, locale, t);
              return (
                <fieldset
                  key={`${section.groupKey}-${section.runStartSourceIndex}`}
                  className="rounded-lg border border-gray-200 px-4 pb-4 dark:border-gray-700"
                  data-testid={`form-dialog-group-${section.runStartSourceIndex}`}
                >
                  <legend className="px-2 text-sm font-semibold text-gray-900 dark:text-white">
                    <span className="inline-flex items-center gap-2">
                      <span className="bg-accent h-4 w-1 rounded-full" aria-hidden="true" />
                      {groupTitle}
                    </span>
                  </legend>
                  <div className="mt-2 space-y-4">{renderedFields}</div>
                </fieldset>
              );
            })}
          </fieldset>
        </div>

        {/* Footer */}
        <div
          className={`flex shrink-0 justify-end gap-3 border-t border-gray-200 px-6 py-4 dark:border-gray-700 ${state.presentation ? 'aura-dialog-presentation__footer' : ''}`}
        >
          <button
            data-testid="form-dialog-cancel"
            disabled={submitting || state.refreshing}
            onClick={handleCancel}
            className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 focus:ring-2 focus:ring-gray-400 focus:ring-offset-2 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            {reviewRequired
              ? translatedOrFallback(t, 'common.close', locale.startsWith('zh') ? '关闭' : 'Close')
              : translatedOrFallback(
                  t,
                  'common.cancel',
                  locale.startsWith('zh') ? '取消' : 'Cancel',
                )}
          </button>
          {reviewRequired && state.submission?.refresh && (
            <button
              type="button"
              data-testid="form-dialog-close-refresh"
              onClick={() => {
                void handleCloseAndRefresh();
              }}
              disabled={state.refreshing}
              className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-hover border px-4 py-2 text-sm disabled:opacity-50"
            >
              {state.refreshing
                ? locale.startsWith('zh')
                  ? '刷新中…'
                  : 'Refreshing…'
                : locale.startsWith('zh')
                  ? '关闭并刷新'
                  : 'Close and refresh'}
            </button>
          )}
          <button
            data-testid="form-dialog-submit"
            onClick={handleSubmit}
            disabled={
              Boolean(state.submission && submissionState?.phase !== 'idle') ||
              state.refreshing ||
              state.fields.some(
                (field) =>
                  field.type === 'uploaded-files' &&
                  matchesVisibility(field.visibleWhen, formData) &&
                  uploadStates[field.field]?.blocked !== false,
              )
            }
            className="bg-accent enabled:hover:bg-accent-hover focus:ring-accent rounded-lg px-4 py-2 text-sm font-medium text-white transition-colors focus:ring-2 focus:ring-offset-2 focus:outline-none disabled:cursor-not-allowed disabled:opacity-50"
          >
            {submitting
              ? locale.startsWith('zh')
                ? '提交中…'
                : 'Submitting…'
              : state.submitLabel
                ? getLocalizedText(state.submitLabel, locale, t)
                : translatedOrFallback(
                    t,
                    'common.confirm',
                    locale.startsWith('zh') ? '确认' : 'Confirm',
                  )}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
