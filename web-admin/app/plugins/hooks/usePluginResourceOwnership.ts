/**
 * Hook for managing plugin resource ownership.
 *
 * Provides utilities for checking resource ownership, marking modifications,
 * and handling ownership-related UI flows.
 */

import { useState, useCallback, useMemo } from 'react';
import { ResultHelper } from '~/utils/type';
import {
  getResourceOwnership,
  markResourceAsModified,
  claimResource,
  getResourceDiff,
  type ResourceType,
  type OwnershipType,
  type ResourceOwnershipInfo,
  type ResourceDiff,
} from '../api/pluginUninstallApi';

export interface UsePluginResourceOwnershipOptions {
  resourceType: ResourceType;
  resourceCode: string;
  onOwnershipChange?: (info: ResourceOwnershipInfo) => void;
  onError?: (error: string) => void;
}

export interface UsePluginResourceOwnershipReturn {
  // State
  loading: boolean;
  error: string | null;
  ownershipInfo: ResourceOwnershipInfo | null;
  diffs: ResourceDiff[];

  // Derived state
  isManaged: boolean;
  ownershipType: OwnershipType | null;
  isPluginOwned: boolean;
  isShared: boolean;
  isUserClaimed: boolean;
  canModify: boolean;
  isUserModified: boolean;
  pluginPid: string | null;

  // Actions
  checkOwnership: () => Promise<ResourceOwnershipInfo | null>;
  markAsModified: () => Promise<boolean>;
  claim: () => Promise<boolean>;
  loadDiffs: () => Promise<ResourceDiff[]>;
  refresh: () => Promise<void>;
}

export function usePluginResourceOwnership(
  options: UsePluginResourceOwnershipOptions
): UsePluginResourceOwnershipReturn {
  const { resourceType, resourceCode, onOwnershipChange, onError } = options;

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ownershipInfo, setOwnershipInfo] = useState<ResourceOwnershipInfo | null>(null);
  const [diffs, setDiffs] = useState<ResourceDiff[]>([]);

  const handleError = useCallback(
    (message: string) => {
      setError(message);
      onError?.(message);
    },
    [onError]
  );

  const checkOwnership = useCallback(async (): Promise<ResourceOwnershipInfo | null> => {
    if (!resourceType || !resourceCode) return null;

    setLoading(true);
    setError(null);

    try {
      const result = await getResourceOwnership(resourceType, resourceCode);

      if (ResultHelper.isSuccess(result) && result.data) {
        setOwnershipInfo(result.data);
        onOwnershipChange?.(result.data);
        return result.data;
      } else {
        handleError(result.desc || 'Failed to check ownership');
        return null;
      }
    } catch (err) {
      handleError(err instanceof Error ? err.message : 'Unknown error');
      return null;
    } finally {
      setLoading(false);
    }
  }, [resourceType, resourceCode, onOwnershipChange, handleError]);

  const markAsModified = useCallback(async (): Promise<boolean> => {
    if (!resourceType || !resourceCode) return false;

    setLoading(true);
    setError(null);

    try {
      const result = await markResourceAsModified(resourceType, resourceCode);

      if (ResultHelper.isSuccess(result)) {
        // Refresh ownership info
        await checkOwnership();
        return true;
      } else {
        handleError(result.desc || 'Failed to mark as modified');
        return false;
      }
    } catch (err) {
      handleError(err instanceof Error ? err.message : 'Unknown error');
      return false;
    } finally {
      setLoading(false);
    }
  }, [resourceType, resourceCode, checkOwnership, handleError]);

  const claim = useCallback(async (): Promise<boolean> => {
    if (!resourceType || !resourceCode) return false;

    setLoading(true);
    setError(null);

    try {
      const result = await claimResource(resourceType, resourceCode);

      if (ResultHelper.isSuccess(result)) {
        // Refresh ownership info
        await checkOwnership();
        return true;
      } else {
        handleError(result.desc || 'Failed to claim resource');
        return false;
      }
    } catch (err) {
      handleError(err instanceof Error ? err.message : 'Unknown error');
      return false;
    } finally {
      setLoading(false);
    }
  }, [resourceType, resourceCode, checkOwnership, handleError]);

  const loadDiffs = useCallback(async (): Promise<ResourceDiff[]> => {
    if (!resourceType || !resourceCode) return [];

    setLoading(true);
    setError(null);

    try {
      const result = await getResourceDiff(resourceType, resourceCode);

      if (ResultHelper.isSuccess(result) && result.data) {
        setDiffs(result.data);
        return result.data;
      } else {
        handleError(result.desc || 'Failed to load diffs');
        return [];
      }
    } catch (err) {
      handleError(err instanceof Error ? err.message : 'Unknown error');
      return [];
    } finally {
      setLoading(false);
    }
  }, [resourceType, resourceCode, handleError]);

  const refresh = useCallback(async (): Promise<void> => {
    await checkOwnership();
  }, [checkOwnership]);

  // Derived state
  const isManaged = ownershipInfo?.managed ?? false;
  const ownershipType = ownershipInfo?.ownershipType ?? null;
  const isPluginOwned = ownershipType === 'plugin_owned';
  const isShared = ownershipType === 'shared';
  const isUserClaimed = ownershipType === 'user_claimed';
  const canModify = ownershipInfo?.canModify ?? true;
  const isUserModified = ownershipInfo?.userModified ?? false;
  const pluginPid = ownershipInfo?.pluginPid ?? null;

  return {
    loading,
    error,
    ownershipInfo,
    diffs,
    isManaged,
    ownershipType,
    isPluginOwned,
    isShared,
    isUserClaimed,
    canModify,
    isUserModified,
    pluginPid,
    checkOwnership,
    markAsModified,
    claim,
    loadDiffs,
    refresh,
  };
}

/**
 * Hook for handling first-time modification warning.
 * Shows a modal when user first modifies a shared resource.
 */
export interface UseModificationWarningOptions {
  resourceType: ResourceType;
  resourceCode: string;
  onContinue?: () => void;
  onCancel?: () => void;
  onDetach?: () => void;
}

export interface UseModificationWarningReturn {
  shouldShowWarning: boolean;
  isCheckingOwnership: boolean;
  ownershipInfo: ResourceOwnershipInfo | null;
  handleBeforeModify: () => Promise<'continue' | 'show-warning' | 'blocked'>;
  handleContinue: () => Promise<void>;
  handleCancel: () => void;
  handleDetach: () => Promise<void>;
  dismissWarning: () => void;
}

export function useModificationWarning(
  options: UseModificationWarningOptions
): UseModificationWarningReturn {
  const { resourceType, resourceCode, onContinue, onCancel, onDetach } = options;

  const [shouldShowWarning, setShouldShowWarning] = useState(false);

  const {
    loading: isCheckingOwnership,
    ownershipInfo,
    checkOwnership,
    markAsModified,
    claim,
  } = usePluginResourceOwnership({
    resourceType,
    resourceCode,
  });

  const handleBeforeModify = useCallback(async (): Promise<'continue' | 'show-warning' | 'blocked'> => {
    const info = await checkOwnership();

    if (!info) {
      // Error checking ownership, allow modification
      return 'continue';
    }

    if (!info.managed) {
      // Not managed by plugin, allow modification
      return 'continue';
    }

    if (info.ownershipType === 'plugin_owned') {
      // Plugin owned, block modification
      return 'blocked';
    }

    if (info.ownershipType === 'user_claimed') {
      // User already claimed, allow modification
      return 'continue';
    }

    if (info.ownershipType === 'shared') {
      if (info.userModified) {
        // Already marked as modified, allow modification
        return 'continue';
      }
      // First modification, show warning
      setShouldShowWarning(true);
      return 'show-warning';
    }

    return 'continue';
  }, [checkOwnership]);

  const handleContinue = useCallback(async (): Promise<void> => {
    await markAsModified();
    setShouldShowWarning(false);
    onContinue?.();
  }, [markAsModified, onContinue]);

  const handleCancel = useCallback((): void => {
    setShouldShowWarning(false);
    onCancel?.();
  }, [onCancel]);

  const handleDetach = useCallback(async (): Promise<void> => {
    await claim();
    setShouldShowWarning(false);
    onDetach?.();
  }, [claim, onDetach]);

  const dismissWarning = useCallback((): void => {
    setShouldShowWarning(false);
  }, []);

  return {
    shouldShowWarning,
    isCheckingOwnership,
    ownershipInfo,
    handleBeforeModify,
    handleContinue,
    handleCancel,
    handleDetach,
    dismissWarning,
  };
}
