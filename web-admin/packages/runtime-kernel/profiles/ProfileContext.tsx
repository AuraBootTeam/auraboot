/**
 * ProfileContext — React context for accessing the current DSL profile
 */

import { createContext, useContext } from 'react';
import type { RenderProfile } from './types';

const ProfileContext = createContext<RenderProfile | null>(null);

export const ProfileProvider = ProfileContext.Provider;

/**
 * Hook to access the current DSL profile from context
 */
export function useProfile(): RenderProfile {
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
export function useProfileSafe(): RenderProfile | null {
  return useContext(ProfileContext);
}
