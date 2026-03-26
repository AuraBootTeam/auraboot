/**
 * ProfileContext — React context for accessing the current DSL profile
 */

import { createContext, useContext } from 'react';
import type { DslProfile } from './types';

const ProfileContext = createContext<DslProfile | null>(null);

export const ProfileProvider = ProfileContext.Provider;

/**
 * Hook to access the current DSL profile from context
 */
export function useProfile(): DslProfile {
  const profile = useContext(ProfileContext);
  if (!profile) {
    throw new Error(
      '[useProfile] Must be used within a ProfileProvider. Ensure DynamicPageRenderer wraps content with ProfileProvider.',
    );
  }
  return profile;
}

/**
 * Hook that returns profile or null (safe version)
 */
export function useProfileSafe(): DslProfile | null {
  return useContext(ProfileContext);
}
