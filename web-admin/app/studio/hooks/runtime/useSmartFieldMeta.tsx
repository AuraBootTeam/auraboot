import { useCallback, useState } from 'react';

export interface SmartFieldMeta {
  touched: boolean;
  error?: string;
  validating?: boolean;
}

interface SmartFieldMetaInput {
  field?: {
    meta?: { touched: boolean; error?: string };
    error?: string | null;
  };
  externalError?: string | null;
}

export function useSmartFieldMeta({ field, externalError }: SmartFieldMetaInput) {
  const [localTouched, setLocalTouched] = useState(false);
  const touched = field?.meta?.touched ?? localTouched;
  const error = field?.error || field?.meta?.error || externalError || undefined;
  const showError = Boolean(error) && (touched || Boolean(externalError));

  const markTouched = useCallback(() => {
    setLocalTouched(true);
  }, []);

  const meta: SmartFieldMeta = {
    touched,
    error,
    validating: false,
  };

  return { meta, showError, markTouched };
}
