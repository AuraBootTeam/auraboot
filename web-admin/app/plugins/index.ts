/**
 * AuraBoot Plugin System
 *
 * This module provides the client-side infrastructure for loading and managing
 * remote plugins using Module Federation.
 *
 * @example
 * ```tsx
 * import {
 *   useFederationStore,
 *   usePluginSync,
 *   PluginSlot,
 *   PluginLoader,
 *   SLOT_IDS,
 * } from '~/plugins';
 *
 * // In your app root
 * function App() {
 *   const { isReady, error } = usePluginSync({ autoSync: true });
 *
 *   if (!isReady) return <LoadingScreen />;
 *   if (error) return <ErrorScreen error={error} />;
 *
 *   return <AppContent />;
 * }
 *
 * // In your pages/components
 * function PageHeader() {
 *   return (
 *     <header>
 *       <h1>My Page</h1>
 *       <PluginSlot slotId={SLOT_IDS.PAGE_HEADER_ACTIONS} />
 *     </header>
 *   );
 * }
 * ```
 */

// Core store and utilities
export {
  useFederationStore,
  selectPlugin,
  selectAllPlugins,
  selectLoadedPlugins,
  selectSlotContributions,
  selectIsPluginLoaded,
  selectPluginError,
  initializeFederation,
} from './FederationManager';

// Components
export { PluginLoader } from './components/PluginLoader';
export {
  PluginSlot,
  ConditionalSlot,
  useSlotHasContributions,
  useSlotContributionCount,
} from './components/PluginSlot';

// Hooks
export {
  usePluginSync,
  usePluginStatus,
  useLoadedPlugins,
} from './hooks/usePluginSync';

export {
  usePluginResourceOwnership,
  useModificationWarning,
} from './hooks/usePluginResourceOwnership';

// Resource Ownership Components
export {
  PluginResourceWarningModal,
  PluginResourceBlockedModal,
} from './components/PluginResourceWarningModal';

export {
  ResourceDiffViewer,
  CompactDiff,
  DiffBadge,
} from './components/ResourceDiffViewer';

export { PluginUninstallPreview } from './components/PluginUninstallPreview';

// Resource Ownership API
export * from './api/pluginUninstallApi';

// Types
export type {
  PluginState,
  PluginStatus,
  PluginManifest,
  PluginClientConfig,
  ExposedModule,
  SlotContribution,
  RouteContribution,
  MenuContribution,
  CommerceEventCode,
  CheckoutExtensionContribution,
  ThemeBlockContribution,
  CommerceEventSubscription,
  RemotePlugin,
  RemoteModule,
  PluginInfo,
  PluginListResponse,
  SlotId,
  SlotProps,
  SlotContributionWithComponent,
  SlotComponentProps,
  FederationState,
  FederationActions,
  FederationStore,
  PluginContextValue,
  PluginLoaderProps,
  PluginSyncState,
  // Unified Package Types
  ComponentStatus,
  PackageStatus,
  PackageHistoryEntry,
  PackageInstallOptions,
  PackageUninstallOptions,
} from './types';

// Constants
export { SLOT_IDS } from './types';
