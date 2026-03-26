/**
 * Generic versioning hook for designers.
 * Manages version history panel state, fetching, and rollback operations.
 */

import { useState, useCallback, useEffect, useRef } from 'react';
import type { VersionEntry } from './types';

interface VersionService {
  getHistory(resourcePid: string): Promise<VersionEntry[]>;
  getVersion(resourcePid: string, versionPid: string): Promise<VersionEntry>;
  rollback(resourcePid: string, versionPid: string): Promise<VersionEntry>;
}

interface UseVersioningOptions {
  /** API service for version operations */
  service: VersionService;
  /** PID of the resource to manage versions for */
  resourcePid: string | undefined;
  /** Callback after a successful rollback */
  onRollbackComplete?: () => void;
}

interface UseVersioningReturn {
  /** Whether the version panel is open */
  isOpen: boolean;
  /** Toggle the version panel */
  togglePanel: () => void;
  /** Close the version panel */
  closePanel: () => void;
  /** Version history entries */
  versions: VersionEntry[];
  /** Whether versions are loading */
  isLoading: boolean;
  /** Currently viewing version PID (null = editing current) */
  viewingVersionPid: string | null;
  /** Currently viewing version snapshot data */
  viewingSnapshot: Record<string, unknown> | null;
  /** Load and preview a historical version */
  previewVersion: (versionPid: string) => Promise<void>;
  /** Stop previewing and return to current version */
  exitPreview: () => void;
  /** Rollback to a specific version */
  rollbackToVersion: (versionPid: string) => Promise<void>;
  /** Whether a rollback is in progress */
  isRollingBack: boolean;
  /** Refresh the version list */
  refreshVersions: () => Promise<void>;
}

export function useVersioning({
  service,
  resourcePid,
  onRollbackComplete,
}: UseVersioningOptions): UseVersioningReturn {
  const [isOpen, setIsOpen] = useState(false);
  const [versions, setVersions] = useState<VersionEntry[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [viewingVersionPid, setViewingVersionPid] = useState<string | null>(null);
  const [viewingSnapshot, setViewingSnapshot] = useState<Record<string, unknown> | null>(null);
  const [isRollingBack, setIsRollingBack] = useState(false);
  const fetchedRef = useRef<string | null>(null);

  const fetchVersions = useCallback(async () => {
    if (!resourcePid) return;
    setIsLoading(true);
    try {
      const result = await service.getHistory(resourcePid);
      setVersions(result);
      fetchedRef.current = resourcePid;
    } catch (error) {
      console.error('Failed to fetch versions:', error);
    } finally {
      setIsLoading(false);
    }
  }, [resourcePid, service]);

  // Fetch versions when panel opens
  useEffect(() => {
    if (isOpen && resourcePid && fetchedRef.current !== resourcePid) {
      fetchVersions();
    }
  }, [isOpen, resourcePid, fetchVersions]);

  const togglePanel = useCallback(() => {
    setIsOpen((prev) => {
      if (!prev && resourcePid) {
        // Will fetch on next render via useEffect
        fetchedRef.current = null;
      }
      return !prev;
    });
  }, [resourcePid]);

  const closePanel = useCallback(() => {
    setIsOpen(false);
    setViewingVersionPid(null);
    setViewingSnapshot(null);
  }, []);

  const previewVersion = useCallback(
    async (versionPid: string) => {
      if (!resourcePid) return;
      try {
        const version = await service.getVersion(resourcePid, versionPid);
        setViewingVersionPid(versionPid);
        setViewingSnapshot(version.schemaSnapshot || null);
      } catch (error) {
        console.error('Failed to load version:', error);
      }
    },
    [resourcePid, service],
  );

  const exitPreview = useCallback(() => {
    setViewingVersionPid(null);
    setViewingSnapshot(null);
  }, []);

  const rollbackToVersion = useCallback(
    async (versionPid: string) => {
      if (!resourcePid) return;
      setIsRollingBack(true);
      try {
        await service.rollback(resourcePid, versionPid);
        setViewingVersionPid(null);
        setViewingSnapshot(null);
        // Refresh versions and notify caller
        await fetchVersions();
        onRollbackComplete?.();
      } catch (error) {
        console.error('Failed to rollback:', error);
        throw error;
      } finally {
        setIsRollingBack(false);
      }
    },
    [resourcePid, service, fetchVersions, onRollbackComplete],
  );

  return {
    isOpen,
    togglePanel,
    closePanel,
    versions,
    isLoading,
    viewingVersionPid,
    viewingSnapshot,
    previewVersion,
    exitPreview,
    rollbackToVersion,
    isRollingBack,
    refreshVersions: fetchVersions,
  };
}
