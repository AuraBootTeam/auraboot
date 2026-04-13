/**
 * OnboardingProvider — React Context managing onboarding state.
 *
 * Persists state to localStorage under 'auraboot_onboarding'.
 * Provides hooks for Product Tour, Quick Start Wizard, and step tracking.
 */

import React, { createContext, useContext, useReducer, useCallback, useEffect } from 'react';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface OnboardingState {
  tourActive: boolean;
  tourStep: number;
  wizardOpen: boolean;
  wizardStep: number;
  completedSteps: string[];
  dismissedAt: string | null;
}

interface OnboardingContextType {
  state: OnboardingState;
  startTour: () => void;
  nextStep: () => void;
  prevStep: () => void;
  endTour: () => void;
  openWizard: () => void;
  closeWizard: () => void;
  setWizardStep: (step: number) => void;
  markComplete: (stepId: string) => void;
  reset: () => void;
  progress: { completed: number; total: number };
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const STORAGE_KEY = 'auraboot_onboarding';

const TRACKED_STEPS = [
  'tour_complete',
  'first_template_installed',
  'first_record_created',
  'explored_designer',
  'explored_aurabot',
] as const;

const INITIAL_STATE: OnboardingState = {
  tourActive: false,
  tourStep: 0,
  wizardOpen: false,
  wizardStep: 0,
  completedSteps: [],
  dismissedAt: null,
};

// ---------------------------------------------------------------------------
// Reducer
// ---------------------------------------------------------------------------

type Action =
  | { type: 'start_tour' }
  | { type: 'next_step' }
  | { type: 'prev_step' }
  | { type: 'end_tour' }
  | { type: 'open_wizard' }
  | { type: 'close_wizard' }
  | { type: 'set_wizard_step'; step: number }
  | { type: 'mark_complete'; stepId: string }
  | { type: 'reset' }
  | { type: 'hydrate'; state: OnboardingState };

function reducer(state: OnboardingState, action: Action): OnboardingState {
  switch (action.type) {
    case 'start_tour':
      return { ...state, tourActive: true, tourStep: 0 };
    case 'next_step':
      return { ...state, tourStep: state.tourStep + 1 };
    case 'prev_step':
      return { ...state, tourStep: Math.max(0, state.tourStep - 1) };
    case 'end_tour':
      return {
        ...state,
        tourActive: false,
        tourStep: 0,
        completedSteps: state.completedSteps.includes('tour_complete')
          ? state.completedSteps
          : [...state.completedSteps, 'tour_complete'],
      };
    case 'open_wizard':
      return { ...state, wizardOpen: true, wizardStep: 0 };
    case 'close_wizard':
      return { ...state, wizardOpen: false };
    case 'set_wizard_step':
      return { ...state, wizardStep: action.step };
    case 'mark_complete': {
      if (state.completedSteps.includes(action.stepId)) return state;
      return { ...state, completedSteps: [...state.completedSteps, action.stepId] };
    }
    case 'reset':
      return { ...INITIAL_STATE };
    case 'hydrate':
      return action.state;
    default:
      return state;
  }
}

// ---------------------------------------------------------------------------
// Persistence helpers
// ---------------------------------------------------------------------------

function loadState(): OnboardingState {
  if (typeof window === 'undefined') return INITIAL_STATE;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return INITIAL_STATE;
    const parsed = JSON.parse(raw);
    return { ...INITIAL_STATE, ...parsed };
  } catch {
    return INITIAL_STATE;
  }
}

function saveState(state: OnboardingState): void {
  if (typeof window === 'undefined') return;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // localStorage full — ignore
  }
}

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

const OnboardingContext = createContext<OnboardingContextType | null>(null);

export function OnboardingProvider({ children }: { children: React.ReactNode }) {
  const [state, dispatch] = useReducer(reducer, INITIAL_STATE);

  // Hydrate from localStorage on mount
  useEffect(() => {
    const persisted = loadState();
    // Only hydrate persistent fields, not transient UI state
    dispatch({
      type: 'hydrate',
      state: {
        ...persisted,
        tourActive: false,
        wizardOpen: false,
        tourStep: 0,
        wizardStep: 0,
      },
    });
  }, []);

  // Persist on every change (skip transient tour/wizard UI state)
  useEffect(() => {
    saveState({
      ...state,
      tourActive: false,
      wizardOpen: false,
      tourStep: 0,
      wizardStep: 0,
    });
  }, [state.completedSteps, state.dismissedAt]);

  const startTour = useCallback(() => dispatch({ type: 'start_tour' }), []);
  const nextStep = useCallback(() => dispatch({ type: 'next_step' }), []);
  const prevStep = useCallback(() => dispatch({ type: 'prev_step' }), []);
  const endTour = useCallback(() => dispatch({ type: 'end_tour' }), []);
  const openWizard = useCallback(() => dispatch({ type: 'open_wizard' }), []);
  const closeWizard = useCallback(() => dispatch({ type: 'close_wizard' }), []);
  const setWizardStep = useCallback(
    (step: number) => dispatch({ type: 'set_wizard_step', step }),
    [],
  );
  const markComplete = useCallback(
    (stepId: string) => dispatch({ type: 'mark_complete', stepId }),
    [],
  );
  const reset = useCallback(() => dispatch({ type: 'reset' }), []);

  const progress = {
    completed: state.completedSteps.length,
    total: TRACKED_STEPS.length,
  };

  return (
    <OnboardingContext.Provider
      value={{
        state,
        startTour,
        nextStep,
        prevStep,
        endTour,
        openWizard,
        closeWizard,
        setWizardStep,
        markComplete,
        reset,
        progress,
      }}
    >
      {children}
    </OnboardingContext.Provider>
  );
}

export function useOnboarding(): OnboardingContextType {
  const ctx = useContext(OnboardingContext);
  if (!ctx) {
    throw new Error('useOnboarding must be used within an OnboardingProvider');
  }
  return ctx;
}
