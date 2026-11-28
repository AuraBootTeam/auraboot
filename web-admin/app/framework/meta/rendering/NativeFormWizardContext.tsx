import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import type { BlockConfig } from '../schemas/types';

interface FormWizardHost {
  ready: boolean;
  submitting: boolean;
  validateStep: (blocks: readonly BlockConfig[]) => boolean;
  renderStep: (blocks: readonly BlockConfig[]) => ReactNode;
  reportStep: (id: string, last: boolean) => void;
  canSubmit: boolean;
}

const NativeFormWizardContext = createContext<FormWizardHost | null>(null);

export function NativeFormWizardProvider({
  wizardIds,
  ready,
  submitting,
  validateStep,
  renderStep,
  children,
}: Omit<FormWizardHost, 'reportStep' | 'canSubmit'> & {
  wizardIds: readonly string[];
  children: ReactNode;
}) {
  const [lastSteps, setLastSteps] = useState<Record<string, boolean>>({});
  const reportStep = useCallback((id: string, last: boolean) => {
    setLastSteps((previous) => (previous[id] === last ? previous : { ...previous, [id]: last }));
  }, []);
  const validIds = wizardIds.every(Boolean) && new Set(wizardIds).size === wizardIds.length;
  const canSubmit = validIds && wizardIds.every((id) => lastSteps[id] === true);
  return (
    <NativeFormWizardContext.Provider
      value={{ ready, submitting, validateStep, renderStep, reportStep, canSubmit }}
    >
      {children}
    </NativeFormWizardContext.Provider>
  );
}

export function useNativeFormWizard() {
  return useContext(NativeFormWizardContext);
}

/** Navigation and cancellation stay available before the final step. */
export function FormWizardActionGate({
  bypass,
  children,
}: {
  bypass: boolean;
  children: ReactNode;
}) {
  const host = useNativeFormWizard();
  return bypass || !host || host.canSubmit ? children : null;
}
