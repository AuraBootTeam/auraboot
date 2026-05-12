import React, { createContext, useContext } from 'react';
import { DEFAULT_RUNTIME_PROFILE, type RuntimeProfile } from './runtimeProfile';

const RuntimeProfileContext = createContext<RuntimeProfile>(DEFAULT_RUNTIME_PROFILE);

export function RuntimeProfileProvider({
  children,
  value,
}: {
  children: React.ReactNode;
  value: RuntimeProfile;
}) {
  return <RuntimeProfileContext.Provider value={value}>{children}</RuntimeProfileContext.Provider>;
}

export function useRuntimeProfile(): RuntimeProfile {
  return useContext(RuntimeProfileContext);
}
