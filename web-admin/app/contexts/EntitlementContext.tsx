import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { useAuth } from './AuthContext';

interface EntitlementWarning {
  code: string;
  message?: string;
  severity: string;
}

interface EntitlementSnapshot {
  pluginId: string;
  status: string;
  planCode: string;
  planDisplayName?: string;
  features: string[];
  expiresAt?: string;
  graceUntil?: string;
  source?: string;
  warning?: EntitlementWarning;
}

export function resolveFeatureEntitlement(
  ready: boolean,
  enabled: boolean,
  entitlements: readonly EntitlementSnapshot[],
  featureKey: string,
): boolean {
  if (!ready) return false;
  if (!enabled) return false;
  return entitlements.some(
    (entitlement) =>
      ['active', 'trial', 'grace'].includes(entitlement.status) &&
      entitlement.features?.includes(featureKey),
  );
}

interface EntitlementContextType {
  enabled: boolean;
  entitlements: EntitlementSnapshot[];
  loading: boolean;
  /** True once the first entitlement request (or unauthenticated shortcut) resolves. */
  ready: boolean;
  getEntitlement: (pluginId: string) => EntitlementSnapshot | undefined;
  isPluginActive: (pluginId: string) => boolean;
  hasFeature: (pluginId: string, featureKey: string) => boolean;
  /** Fail-closed feature lookup used by the plugin activation boundary. */
  hasFeatureKey: (featureKey: string) => boolean;
  getWarnings: () => EntitlementSnapshot[];
  refresh: () => void;
}

const EntitlementContext = createContext<EntitlementContextType>({
  enabled: false,
  entitlements: [],
  loading: false,
  ready: false,
  getEntitlement: () => undefined,
  isPluginActive: () => true,
  hasFeature: () => true,
  hasFeatureKey: () => false,
  getWarnings: () => [],
  refresh: () => {},
});

export function EntitlementProvider({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth();
  const [enabled, setEnabled] = useState(false);
  const [entitlements, setEntitlements] = useState<EntitlementSnapshot[]>([]);
  const [loading, setLoading] = useState(false);
  const [ready, setReady] = useState(false);

  const fetchEntitlements = useCallback(async () => {
    if (!isAuthenticated) {
      setEnabled(false);
      setEntitlements([]);
      setLoading(false);
      setReady(true);
      return;
    }
    try {
      setReady(false);
      setLoading(true);
      const resp = await fetch('/api/entitlements');
      if (resp.ok) {
        const data = await resp.json();
        const result = data.data || data;
        setEnabled(result.enabled ?? false);
        setEntitlements(result.entitlements ?? []);
      } else {
        // 404/500 = entitlement module not available (community edition) — silently disable
        setEnabled(false);
        setEntitlements([]);
      }
    } catch {
      // Network error or module not available — entitlements disabled
      setEnabled(false);
      setEntitlements([]);
    } finally {
      setLoading(false);
      setReady(true);
    }
  }, [isAuthenticated]);

  useEffect(() => {
    fetchEntitlements();
  }, [fetchEntitlements]);

  const getEntitlement = useCallback(
    (pluginId: string) => entitlements.find((e) => e.pluginId === pluginId),
    [entitlements],
  );

  const isPluginActive = useCallback(
    (pluginId: string) => {
      if (!enabled) return true;
      const e = entitlements.find((x) => x.pluginId === pluginId);
      if (!e) return true;
      return ['active', 'trial', 'grace'].includes(e.status);
    },
    [enabled, entitlements],
  );

  const hasFeature = useCallback(
    (pluginId: string, featureKey: string) => {
      if (!enabled) return true;
      const e = entitlements.find((x) => x.pluginId === pluginId);
      if (!e) return true;
      return e.features?.includes(featureKey) ?? true;
    },
    [enabled, entitlements],
  );

  const hasFeatureKey = useCallback(
    (featureKey: string) =>
      resolveFeatureEntitlement(ready, enabled, entitlements, featureKey),
    [enabled, entitlements, ready],
  );

  const getWarnings = useCallback(() => entitlements.filter((e) => e.warning), [entitlements]);

  return (
    <EntitlementContext.Provider
      value={{
        enabled,
        entitlements,
        loading,
        ready,
        getEntitlement,
        isPluginActive,
        hasFeature,
        hasFeatureKey,
        getWarnings,
        refresh: fetchEntitlements,
      }}
    >
      {children}
    </EntitlementContext.Provider>
  );
}

export function useEntitlement(pluginId?: string) {
  const ctx = useContext(EntitlementContext);
  if (pluginId) {
    return {
      ...ctx,
      snapshot: ctx.getEntitlement(pluginId),
      status: ctx.getEntitlement(pluginId)?.status,
      warning: ctx.getEntitlement(pluginId)?.warning,
    };
  }
  return ctx;
}

export default EntitlementContext;
