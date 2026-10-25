import { usePresentationNavigation } from '~/shared/layout/PagePresentationContext';
import { useState, useCallback, useMemo, useRef } from 'react';
import { useSearchParams } from 'react-router';
import { useToastContext } from '~/contexts/ToastContext';
import { getPublicRecordPid } from '~/framework/meta/utils/publicRecordId';
import { isSingletonRecordSource } from './formActions';
import { type PageContentProps } from '@auraboot/runtime-kernel';

type UseFormPageStateArgs = { props: PageContentProps };

export function useFormPageState({ props }: UseFormPageStateArgs) {
  const { sidebarWidth } = usePresentationNavigation();
  const {
    schema,
    tableName,
    token,
    initialValues,
    fieldPermissions,
    onSubmitOverride,
    onCancelOverride,
  } = props;

  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToastContext();
  const showToast = useCallback(
    (message: string, type: 'success' | 'error' | 'warning' | 'info') => {
      switch (type) {
        case 'success':
          showSuccessToast(message);
          break;
        case 'error':
          showErrorToast(message);
          break;
        case 'warning':
          showWarningToast(message);
          break;
        case 'info':
          showInfoToast(message);
          break;
      }
    },
    [showSuccessToast, showErrorToast, showWarningToast, showInfoToast],
  );

  // State management
  const [formData, setFormData] = useState<Record<string, any>>({});
  const [initialFormData, setInitialFormData] = useState<Record<string, any> | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [summaryErrors, setSummaryErrors] = useState<string[]>([]);
  const dirtyFieldsRef = useRef<Set<string>>(new Set());
  // T10: holds the latest draft-clear fn so submit handlers (defined before the
  // draft hook) can clear the persisted local draft on success.
  const clearFormDraftRef = useRef<() => void>(() => {});

  // Read URL params:
  // - commandCode: explicit submit command provided by navigation actions
  // - sourceRecordPid: source business record id for create-by-context flows
  const [searchParams] = useSearchParams();
  const urlCommandCode = searchParams.get('commandCode');
  const sourceRecordPid = searchParams.get('sourceRecordPid');
  const routeRecordPid = props.recordPid;
  const isEditMode = !!routeRecordPid || isSingletonRecordSource(schema);
  const recordPid = useMemo(
    () => getPublicRecordPid({ pid: routeRecordPid }) || '',
    [routeRecordPid],
  );
  return {
    sidebarWidth,
    schema,
    tableName,
    token,
    initialValues,
    fieldPermissions,
    onSubmitOverride,
    onCancelOverride,
    showSuccessToast,
    showErrorToast,
    showInfoToast,
    showToast,
    formData,
    setFormData,
    initialFormData,
    setInitialFormData,
    fieldErrors,
    setFieldErrors,
    summaryErrors,
    setSummaryErrors,
    dirtyFieldsRef,
    clearFormDraftRef,
    searchParams,
    urlCommandCode,
    sourceRecordPid,
    routeRecordPid,
    isEditMode,
    recordPid,
  };
}
