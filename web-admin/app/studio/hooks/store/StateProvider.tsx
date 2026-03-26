import React, { createContext, useContext, useMemo, useState } from 'react';
import { PageStateManager, type PageState } from './PageStateManager';

interface StateContextValue {
  state: PageState;
  stateManager: PageStateManager;
  subscribe: (event: string, callback: (state: PageState) => void) => () => void;
}

const StateContext = createContext<StateContextValue | undefined>(undefined);

export function StateProvider({ children }: { children: React.ReactNode }) {
  const stateManager = useMemo(() => new PageStateManager(), []);
  const [state] = useState<PageState>(() => stateManager.getState());

  const value = useMemo<StateContextValue>(
    () => ({
      state,
      stateManager,
      subscribe: (_event, _callback) => () => {},
    }),
    [state, stateManager],
  );

  return <StateContext.Provider value={value}>{children}</StateContext.Provider>;
}

export function useStateContext(): StateContextValue {
  const context = useContext(StateContext);
  if (!context) {
    throw new Error('useStateContext must be used within a StateProvider');
  }
  return context;
}
