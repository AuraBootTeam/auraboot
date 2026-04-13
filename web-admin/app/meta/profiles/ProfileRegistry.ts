/**
 * ProfileRegistry — global registry for DSL profiles
 *
 * Profiles are registered at app startup. The renderer resolves
 * the correct profile from schema.profile or falls back to "admin".
 */

import type { RenderProfile } from './types';

class ProfileRegistry {
  private profiles = new Map<string, RenderProfile>();
  private defaultProfileName = 'admin';

  /** Register a profile */
  register(profile: RenderProfile): void {
    if (this.profiles.has(profile.name)) {
      console.warn(`[ProfileRegistry] Overwriting existing profile: ${profile.name}`);
    }
    this.profiles.set(profile.name, profile);
  }

  /** Get a profile by name */
  get(name: string): RenderProfile | undefined {
    return this.profiles.get(name);
  }

  /** Get profile or throw if not found */
  getOrThrow(name: string): RenderProfile {
    const profile = this.profiles.get(name);
    if (!profile) {
      throw new Error(
        `[ProfileRegistry] Profile "${name}" not registered. Available: ${this.listNames().join(', ')}`,
      );
    }
    return profile;
  }

  /** Resolve profile from schema (schema.profile > fallback > default) */
  resolve(schema: { profile?: string } | null | undefined, fallback?: string): RenderProfile {
    const name = schema?.profile || fallback || this.defaultProfileName;
    return this.getOrThrow(name);
  }

  /** Check if a profile is registered */
  has(name: string): boolean {
    return this.profiles.has(name);
  }

  /** List all registered profiles */
  list(): RenderProfile[] {
    return Array.from(this.profiles.values());
  }

  /** List profile names */
  listNames(): string[] {
    return Array.from(this.profiles.keys());
  }

  /** Set default profile name */
  setDefault(name: string): void {
    this.defaultProfileName = name;
  }
}

export const profileRegistry = new ProfileRegistry();
