import { useCallback, useRef } from 'react';
import {
  commandRequestMetadata,
  projectCommandPayload,
  type CommandRequestIdentity,
} from '~/framework/meta/utils/commandRequestPolicy';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { resolveCommandErrorMessage } from '~/framework/meta/hooks/useActionHandler';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import {
  buildCommandTargetParams,
  getCommandResultRecordPid,
} from '~/framework/meta/utils/publicRecordId';
import { resolveCommandValidationSummary } from './formValidationValues';
import {
  expandBomUploadReviewPayload,
  buildFormCommandPayload,
  buildDynamicCrudFormPayload,
  collectAttachmentRelationIds,
} from './formPayload';
import {
  resolveAsyncCommandDispatch,
  pollFormAsyncCommandTask,
  resolveActionType,
  shouldBypassFormSubmit,
  resolveAfterSubmitRedirect,
  resolveDynamicCrudFormOperation,
  resolveFormSubmitEndpoint,
  inferEditCommandCode,
  resolveSubmitCommandCode,
} from './formActions';
import type { useFormPageState } from './useFormPageState';
import type { useFormRecord } from './useFormRecord';
import type { useFormPageRuntime } from './useFormPageRuntime';
import type { useFormMetadata } from './useFormMetadata';
import type { useFormValidation } from './useFormValidation';

type UseFormSubmissionArgs = Pick<
  ReturnType<typeof useFormPageState>,
  | 'onCancelOverride'
  | 'onSubmitOverride'
  | 'setFieldErrors'
  | 'setSummaryErrors'
  | 'formData'
  | 'showInfoToast'
  | 'showErrorToast'
  | 'recordPid'
  | 'initialFormData'
  | 'sourceRecordPid'
  | 'token'
  | 'clearFormDraftRef'
  | 'urlCommandCode'
  | 'schema'
  | 'dirtyFieldsRef'
  | 'showSuccessToast'
  | 'tableName'
> &
  Pick<ReturnType<typeof useFormRecord>, 'setError' | 'handleAction' | 'setSubmitting'> &
  Pick<ReturnType<typeof useFormPageRuntime>, 'schemaFieldNames' | 't' | 'navigate' | 'locale'> &
  Pick<ReturnType<typeof useFormMetadata>, 'fieldMetaLoaded' | 'modelFields'> &
  Pick<
    ReturnType<typeof useFormValidation>,
    'validateFormBeforeSubmit' | 'notifyValidationFailure'
  >;

export function useFormSubmission({
  onCancelOverride,
  onSubmitOverride,
  setFieldErrors,
  setSummaryErrors,
  setError,
  handleAction,
  formData,
  schemaFieldNames,
  fieldMetaLoaded,
  showInfoToast,
  t,
  validateFormBeforeSubmit,
  notifyValidationFailure,
  setSubmitting,
  showErrorToast,
  recordPid,
  initialFormData,
  sourceRecordPid,
  modelFields,
  token,
  clearFormDraftRef,
  urlCommandCode,
  schema,
  dirtyFieldsRef,
  showSuccessToast,
  navigate,
  tableName,
  locale,
}: UseFormSubmissionArgs) {
  const requestIdentity = useRef<CommandRequestIdentity | undefined>(undefined);
  // In new mode, override form button commandCodes with the create command
  // so that save_draft/submit use CREATE instead of UPDATE
  const handleFormAction = useCallback(
    async (button: { commandCode?: string; [key: string]: any }) => {
      const leaveActions = ['cancel', 'back', 'close'];
      if (
        onCancelOverride &&
        (leaveActions.includes(resolveActionType(button.action).toLowerCase()) ||
          leaveActions.includes(String(button.code || '').toLowerCase()))
      ) {
        onCancelOverride();
        return;
      }
      // L1 SDK: delegate to external submit handler when provided
      if (onSubmitOverride) {
        const actionType = resolveActionType(button.action);
        if (shouldBypassFormSubmit(button, actionType)) {
          setFieldErrors({});
          setSummaryErrors([]);
          setError(null);
          return handleAction(button as any, formData as any);
        }
        const shouldValidate =
          ['submit', 'create', 'update', 'edit', 'save', 'command'].includes(
            actionType.toLowerCase(),
          ) || !actionType;
        if (shouldValidate && schemaFieldNames.size > 0 && !fieldMetaLoaded) {
          showInfoToast(t('common.loading') || 'Loading...');
          return;
        }
        if (shouldValidate) {
          const validationResult = validateFormBeforeSubmit();
          if (
            Object.keys(validationResult.fieldErrors).length > 0 ||
            validationResult.summaryErrors.length > 0
          ) {
            setFieldErrors(validationResult.fieldErrors);
            setSummaryErrors(validationResult.summaryErrors);
            setError(null);
            notifyValidationFailure(validationResult);
            return;
          }
        }
        setFieldErrors({});
        setSummaryErrors([]);
        setSubmitting(true);
        onSubmitOverride(formData)
          .catch((err) => {
            const errorMessage = err instanceof Error ? err.message : 'Submit failed';
            setError(errorMessage);
            showErrorToast(errorMessage);
          })
          .finally(() => setSubmitting(false));
        return;
      }
      const mergedEditData = recordPid
        ? { ...(initialFormData || {}), ...formData, pid: recordPid }
        : formData;
      const actionRecord = recordPid
        ? mergedEditData
        : sourceRecordPid
          ? { ...formData, sourceRecordPid }
          : formData;
      // BomUploadReview fields carry a local File + confirmed column mapping; both
      // executors below need the uploaded file id and the review payload, so expand
      // once here before dispatching.
      const { expansions: reviewExpansions } = await expandBomUploadReviewPayload(
        actionRecord,
        modelFields,
        token || undefined,
      );
      const dispatchActionRecord = Object.keys(reviewExpansions).length
        ? { ...actionRecord, ...reviewExpansions }
        : actionRecord;
      const effectiveAction = button.action;
      const effectiveActionType = resolveActionType(effectiveAction);
      const effectiveButton = {
        ...button,
        action: effectiveAction,
      };
      if (shouldBypassFormSubmit(effectiveButton, effectiveActionType)) {
        setFieldErrors({});
        setSummaryErrors([]);
        setError(null);
        // T10: an explicit cancel/back/close abandons the form — drop the draft
        // so it doesn't resurrect on the next create. (refresh/reload keep it.)
        const leaveActions = ['cancel', 'back', 'close'];
        if (
          leaveActions.includes(String(effectiveActionType).toLowerCase()) ||
          leaveActions.includes(String((button as any)?.code || '').toLowerCase())
        ) {
          clearFormDraftRef.current();
        }
        return handleAction(effectiveButton as any, dispatchActionRecord as any);
      }
      // When action is an object like {type: "command", command: "xx:update_xx"},
      // extract the command code from it as well.
      const actionCommandCode =
        effectiveAction && typeof effectiveAction === 'object'
          ? (effectiveAction as Record<string, unknown>).command
          : undefined;
      const explicitCommandCode = inferEditCommandCode(
        urlCommandCode ||
          effectiveButton.commandCode ||
          (typeof actionCommandCode === 'string' ? actionCommandCode : null) ||
          null,
        Boolean(recordPid),
      );
      // Convention over configuration: an explicit command (URL / button /
      // action) wins; otherwise route through the model's CRUD command the
      // server resolved onto `schema.commands` (update when editing, create when
      // new). Pure-CRUD models stay null and fall back to the dynamic CRUD API
      // below.
      const effectiveCommandCode = resolveSubmitCommandCode(
        explicitCommandCode,
        schema?.commands as Record<string, string> | undefined,
        Boolean(recordPid),
      );
      const shouldValidate =
        ['submit', 'create', 'update', 'edit', 'save', 'command'].includes(
          effectiveActionType.toLowerCase(),
        ) || !effectiveActionType;
      if (shouldValidate && schemaFieldNames.size > 0 && !fieldMetaLoaded) {
        showInfoToast(t('common.loading') || 'Loading...');
        return;
      }
      if (shouldValidate) {
        const validationResult = validateFormBeforeSubmit();
        if (
          Object.keys(validationResult.fieldErrors).length > 0 ||
          validationResult.summaryErrors.length > 0
        ) {
          setFieldErrors(validationResult.fieldErrors);
          setSummaryErrors(validationResult.summaryErrors);
          setError(null);
          notifyValidationFailure(validationResult);
          return;
        }
      }
      setFieldErrors({});
      setSummaryErrors([]);
      const commandPayload = projectCommandPayload(
        buildFormCommandPayload(
          dispatchActionRecord,
          modelFields,
          schema?.blocks,
          recordPid ? dirtyFieldsRef.current : undefined,
        ),
        schema.extension?.commandRequest,
      );
      // Review metadata is command input rather than model data, so it is intentionally absent
      // from the model-field whitelist used by buildFormCommandPayload.
      Object.assign(commandPayload, reviewExpansions);

      // Ensure sourceRecordPid is passed through to backend for SideEffect resolution
      if (sourceRecordPid && !commandPayload.sourceRecordPid) {
        commandPayload.sourceRecordPid = sourceRecordPid;
      }

      // Form command path: execute command directly with explicit operation context.
      // This avoids ambiguity from generic action routing branches (navigate/action registry).
      const submitEndpoint = resolveFormSubmitEndpoint(schema, recordPid || null, actionRecord);
      if (submitEndpoint && ['save', 'submit', 'create', 'update'].includes(effectiveActionType)) {
        setSubmitting(true);
        return fetchResult(submitEndpoint.endpoint, {
          method: submitEndpoint.method,
          params: commandPayload,
          token: token || undefined,
        })
          .then((result) => {
            if (!ResultHelper.isSuccess(result)) {
              throw new Error(resolveCommandErrorMessage(result, submitEndpoint.endpoint, t));
            }
            showSuccessToast(t('common.saveSuccess') || 'Saved successfully');
            dirtyFieldsRef.current.clear();
            clearFormDraftRef.current();
            navigate(resolveAfterSubmitRedirect(schema, tableName, result.data, recordPid));
          })
          .catch((err) => {
            const errorMessage = err instanceof Error ? err.message : 'Failed to submit form';
            // Server-side model-rule rejections (e.g. cross-field checks) carry
            // the human-readable reason; surface it in the inline summary next
            // to the form as well as the toast.
            setSummaryErrors([errorMessage]);
            setError(errorMessage);
            showErrorToast(errorMessage);
          })
          .finally(() => setSubmitting(false));
      }

      if (effectiveCommandCode) {
        const operationType =
          effectiveActionType === 'delete'
            ? 'delete'
            : effectiveActionType === 'create'
              ? 'create'
              : effectiveActionType === 'edit' || effectiveActionType === 'update'
                ? 'update'
                : effectiveActionType === 'save' || effectiveActionType === 'command'
                  ? recordPid
                    ? 'update'
                    : 'create'
                  : undefined;
        const targetRecordPid = recordPid || undefined;

        if ((operationType === 'update' || operationType === 'delete') && !targetRecordPid) {
          showErrorToast('Missing target record for update/delete');
          return;
        }

        const request = commandRequestMetadata(
          schema.extension?.commandRequest,
          recordPid ? initialFormData : undefined,
          {
            command: effectiveCommandCode,
            targetRecordPid,
            payload: commandPayload,
            version: initialFormData?.[schema.extension?.commandRequest?.versionField || ''],
          },
          requestIdentity.current,
        );
        requestIdentity.current = request.identity;
        setSubmitting(true);
        fetchResult(`/api/meta/commands/execute/${effectiveCommandCode}`, {
          method: 'post',
          params: {
            ...buildCommandTargetParams(targetRecordPid),
            payload: commandPayload,
            operationType,
            ...request.metadata,
          },
          token: token || undefined,
        })
          .then(async (result) => {
            if (!ResultHelper.isSuccess(result)) {
              const contextError = (result as any).context?.error;
              if (contextError) {
                setError(null);
                setSummaryErrors(
                  resolveCommandValidationSummary(contextError, effectiveCommandCode, t, locale),
                );
                return;
              }
              // result.desc / result.message are the generic envelope ("Business error").
              // The reason a command refused the submit lives in context.detail.
              throw new Error(resolveCommandErrorMessage(result, effectiveCommandCode, t));
            }
            const asyncDispatch = resolveAsyncCommandDispatch(
              result.data,
              schema?.extension?.asyncSubmitMode,
            );
            const responseData = asyncDispatch
              ? await (async () => {
                  const message = t('common.asyncProcessing');
                  showInfoToast(
                    message && message !== 'common.asyncProcessing'
                      ? message
                      : '已提交，后台处理中...',
                  );
                  return pollFormAsyncCommandTask(asyncDispatch.taskCode, token || undefined);
                })()
              : result.data;
            if (schema.extension?.linkFileAttachments === true) {
              const savedPid = recordPid || getCommandResultRecordPid(responseData);
              if (!savedPid)
                throw new Error(
                  getLocalizedText(
                    {
                      'zh-CN': '记录已保存，但未取得附件关联目标。请重新打开记录检查。',
                      en: 'Saved, but attachment target is missing. Reopen the record to check.',
                    },
                    locale,
                    t,
                  ),
                );
              for (const [field, meta] of Object.entries(modelFields)) {
                if (
                  !schema.extension?.attachmentFields?.includes(field) ||
                  commandPayload[field] === undefined
                )
                  continue;
                let fileIds: string[];
                try {
                  fileIds = collectAttachmentRelationIds(commandPayload[field]);
                } catch {
                  throw new Error(
                    getLocalizedText(
                      {
                        'zh-CN': '记录已保存，但附件信息不完整。请重新上传附件。',
                        en: 'Saved, but attachment metadata is incomplete. Upload the files again.',
                      },
                      locale,
                      t,
                    ),
                  );
                }
                const relation = await fetchResult('/api/file/relation', {
                  method: 'post',
                  token: token || undefined,
                  params: {
                    entityType: schema.modelCode || tableName,
                    entityId: savedPid,
                    fieldName: field,
                    fileIds,
                  },
                });
                if (!ResultHelper.isSuccess(relation))
                  throw new Error(
                    getLocalizedText(
                      {
                        'zh-CN': '记录已保存，但附件共享关联失败。请重新打开记录检查附件。',
                        en: 'Saved, but attachment sharing failed. Reopen the record to check files.',
                      },
                      locale,
                      t,
                    ),
                  );
              }
            }
            setFieldErrors({});
            setSummaryErrors([]);
            dirtyFieldsRef.current.clear();
            clearFormDraftRef.current();
            // The direct-submit and dynamic-CRUD branches both confirm a
            // successful save before redirecting. Keep command-backed forms
            // on the same interaction contract; otherwise the record is
            // created and the user is silently dropped back on the list.
            showSuccessToast(t('common.saveSuccess') || 'Saved successfully');
            navigate(resolveAfterSubmitRedirect(schema, tableName, responseData, recordPid));
          })
          .catch((err) => {
            const errorMessage = err instanceof Error ? err.message : 'Failed to execute command';
            setError(errorMessage);
            showErrorToast(errorMessage);
          })
          .finally(() => setSubmitting(false));
        return;
      }

      // MODEL form path: when no commandCode is provided, persist via dynamic CRUD API.
      const dynamicCrudOperation = resolveDynamicCrudFormOperation(effectiveActionType, recordPid);
      if (!effectiveButton.commandCode && dynamicCrudOperation) {
        const targetModelCode = schema?.modelCode || tableName;
        const endpoint =
          dynamicCrudOperation === 'update'
            ? `/api/dynamic/${targetModelCode}/${recordPid}`
            : `/api/dynamic/${targetModelCode}`;
        const method = dynamicCrudOperation === 'update' ? 'put' : 'post';
        const normalizedPayload = buildDynamicCrudFormPayload(
          actionRecord,
          modelFields,
          dirtyFieldsRef.current,
          dynamicCrudOperation,
          initialFormData,
        );

        setSubmitting(true);
        return fetchResult(endpoint, {
          method,
          params: normalizedPayload,
          token: token || undefined,
        })
          .then((result) => {
            if (!ResultHelper.isSuccess(result)) {
              throw new Error(
                result.desc || result.message || `Failed to ${effectiveActionType} record`,
              );
            }
            showSuccessToast(t('common.saveSuccess') || 'Saved successfully');
            dirtyFieldsRef.current.clear();
            clearFormDraftRef.current();
            navigate(resolveAfterSubmitRedirect(schema, targetModelCode, result.data, recordPid));
          })
          .catch((err) => {
            const errorMessage =
              err instanceof Error ? err.message : `Failed to ${effectiveActionType} record`;
            setError(errorMessage);
            showErrorToast(errorMessage);
          })
          .finally(() => setSubmitting(false));
      }

      return handleAction(effectiveButton as any, actionRecord as any);
    },
    [
      formData,
      handleAction,
      initialFormData,
      modelFields,
      navigate,
      onSubmitOverride,
      onCancelOverride,
      notifyValidationFailure,
      recordPid,
      schema?.modelCode,
      schema?.blocks,
      setError,
      fieldMetaLoaded,
      schemaFieldNames,
      showErrorToast,
      showInfoToast,
      showSuccessToast,
      sourceRecordPid,
      t,
      tableName,
      token,
      urlCommandCode,
      validateFormBeforeSubmit,
      clearFormDraftRef,
      dirtyFieldsRef,
      setFieldErrors,
      setSubmitting,
      setSummaryErrors,
    ],
  );
  return { handleFormAction };
}
