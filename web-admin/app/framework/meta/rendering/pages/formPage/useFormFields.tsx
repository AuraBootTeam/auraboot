import { useCallback, useMemo } from 'react';
import { createFieldRenderer } from '~/framework/meta/utils/createFieldRenderer';
import { applyFormFieldChange } from '../form/applyFormFieldChange';
import { useFormDraft } from '~/framework/meta/rendering/pages/form/useFormDraft';
import { getFormFieldValueWithAlias } from './formRecordValues';
import type { useFormPageState } from './useFormPageState';
import type { useFormPageRuntime } from './useFormPageRuntime';
import type { useFormRecord } from './useFormRecord';

type UseFormFieldsArgs = Pick<
  ReturnType<typeof useFormPageState>,
  | 'formData'
  | 'setFormData'
  | 'fieldErrors'
  | 'dirtyFieldsRef'
  | 'initialFormData'
  | 'recordPid'
  | 'tableName'
  | 'token'
  | 'schema'
  | 'isEditMode'
  | 'onSubmitOverride'
  | 'clearFormDraftRef'
> &
  Pick<
    ReturnType<typeof useFormPageRuntime>,
    'pageContext' | 'runtime' | 'locale' | 't' | 'schemaDefaultValues' | 'urlDefaultValues'
  > &
  Pick<ReturnType<typeof useFormRecord>, 'syncRuntimeFieldValue' | 'clearFieldError'>;

export function useFormFields({
  formData,
  setFormData,
  pageContext,
  fieldErrors,
  syncRuntimeFieldValue,
  runtime,
  dirtyFieldsRef,
  clearFieldError,
  initialFormData,
  recordPid,
  tableName,
  token,
  locale,
  t,
  schema,
  isEditMode,
  onSubmitOverride,
  schemaDefaultValues,
  urlDefaultValues,
  clearFormDraftRef,
}: UseFormFieldsArgs) {
  // Render smart field using utility
  const renderSmartField = useMemo(
    () =>
      createFieldRenderer(formData, setFormData, pageContext, fieldErrors, (fieldCode, value) => {
        syncRuntimeFieldValue(fieldCode, value);
        const patch = applyFormFieldChange(runtime, fieldCode, value);
        for (const changedField of Object.keys(patch)) {
          dirtyFieldsRef.current.add(changedField);
          clearFieldError(changedField);
        }
        setFormData((prev) => ({ ...prev, ...patch }));
      }),
    [
      formData,
      pageContext,
      fieldErrors,
      clearFieldError,
      syncRuntimeFieldValue,
      runtime,
      dirtyFieldsRef,
      setFormData,
    ],
  );

  // Stable runtime context for custom blocks. Memoized so the props
  // reference passed to ComponentLoader is identity-stable across re-renders
  // unless one of the source values changes; otherwise every keystroke in
  // a form input would re-mount the custom block subtree and blow away
  // local state (drag selection, cached fetches, etc.).
  // Must be declared before the schema null-guard early return so hook order
  // stays stable across renders (react-hooks/rules-of-hooks).
  const customBlockRuntime = useMemo(
    () => ({
      record: formData,
      initialRecord: initialFormData ?? formData,
      recordPid,
      tableName,
      token,
      locale,
      t,
      getFieldValue: (fieldCode: string) => getFormFieldValueWithAlias(formData, fieldCode),
      updateField: (fieldCode: string, value: unknown) => {
        dirtyFieldsRef.current.add(fieldCode);
        clearFieldError(fieldCode);
        setFormData((prev) => {
          if (prev[fieldCode] === value) return prev;
          return {
            ...prev,
            [fieldCode]: value,
          };
        });
      },
      getContext: () => ({ record: formData, pageContext }),
    }),
    [
      formData,
      initialFormData,
      recordPid,
      tableName,
      token,
      locale,
      t,
      clearFieldError,
      pageContext,
      dirtyFieldsRef,
      setFormData,
    ],
  );

  // --- T10: local draft autosave (create-form focused) -----------------------
  // Persist in-progress create-form input to localStorage so an accidental
  // reload/navigation doesn't lose work, with restore-on-reopen + clear-on-submit.
  // Scoped to create mode: edit forms hydrate from the server record and we don't
  // want a stale local draft to silently shadow loaded values. (The store key
  // still scopes by recordPid, so enabling edit later is a one-line change.)
  const draftModelCode = (schema as any)?.modelCode || tableName;
  const draftPageKey = (schema as any)?.pageKey;
  const draftEnabled = !isEditMode && !onSubmitOverride;
  const {
    restorable: restorableDraft,
    restore: restoreDraft,
    discard: discardDraft,
    clearDraft: clearFormDraft,
  } = useFormDraft({
    enabled: draftEnabled,
    modelCode: draftModelCode,
    pageKey: draftPageKey,
    recordPid,
    values: formData,
    initialValues: useMemo(
      () => ({ ...schemaDefaultValues, ...urlDefaultValues }),
      [schemaDefaultValues, urlDefaultValues],
    ),
  });

  const handleRestoreDraft = useCallback(() => {
    const values = restoreDraft();
    if (!values) return;
    setFormData((prev) => {
      const next = { ...prev, ...values };
      for (const fieldCode of Object.keys(values)) {
        dirtyFieldsRef.current.add(fieldCode);
      }
      return next;
    });
  }, [restoreDraft, dirtyFieldsRef, setFormData]);

  // `handleFormAction` (declared above) clears the draft on a successful submit
  // via this ref, since the hook's `clearFormDraft` is defined after it.
  clearFormDraftRef.current = clearFormDraft;
  return {
    renderSmartField,
    customBlockRuntime,
    restorableDraft,
    discardDraft,
    handleRestoreDraft,
  };
}
