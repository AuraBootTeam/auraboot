/**
 * Plugin system type definitions for Module Federation hot-loading.
 */

import type { RuntimeProfile } from '~/framework/runtime';

// ========== Plugin States ==========

export type PluginState = 'loading' | 'loaded' | 'error' | 'unloaded';

export type PluginStatus = 'installed' | 'enabled' | 'disabled' | 'failed';

// ========== Plugin Manifest ==========

export interface PluginManifest {
  pluginId: string;
  namespace: string;
  version: string;
  displayName: string;
  description?: string;
  author?: string;
  clientConfig?: PluginClientConfig;
}

export interface PluginClientConfig {
  remoteEntry?: string;
  runtimeProfiles?: RuntimeProfile[];
  routeGroup?: RuntimeProfile;
  exposedModules?: ExposedModule[];
  slots?: SlotContribution[];
  storefrontSlots?: SlotContribution[];
  checkoutExtensions?: CheckoutExtensionContribution[];
  themeBlocks?: ThemeBlockContribution[];
  commerceEvents?: CommerceEventSubscription[];
  routes?: RouteContribution[];
  menuItems?: MenuContribution[];
}

export interface ExposedModule {
  name: string;
  path: string;
}

export interface SlotContribution {
  slotId: string;
  componentName: string;
  runtimeProfiles?: RuntimeProfile[];
  priority?: number;
  props?: Record<string, unknown>;
}

export interface RouteContribution {
  path: string;
  componentName: string;
  exact?: boolean;
}

export interface MenuContribution {
  key: string;
  label: string;
  icon?: string;
  path: string;
  order?: number;
  parent?: string;
}

// ========== Commerce Runtime Extension Contracts ==========

export type CommerceEventCode =
  | 'cart.updated'
  | 'checkout.created'
  | 'payment.authorized'
  | 'payment.captured'
  | 'order.created'
  | 'inventory.reserved'
  | 'fulfillment.created'
  | 'refund.created'
  | (string & {});

export interface CheckoutExtensionContribution {
  extensionPoint: 'checkout:contact' | 'checkout:shipping' | 'checkout:payment' | 'checkout:review' | 'checkout:thank-you' | (string & {});
  componentName: string;
  priority?: number;
  props?: Record<string, unknown>;
}

export interface ThemeBlockContribution {
  type: string;
  componentName: string;
  label?: string | Record<string, string>;
  schema?: Record<string, unknown>;
  previewImage?: string;
}

export interface CommerceEventSubscription {
  event: CommerceEventCode;
  handlerName: string;
  delivery?: 'frontend' | 'backend' | 'webhook';
  priority?: number;
}

// ========== Remote Plugin ==========

export interface RemotePlugin {
  pluginId: string;
  namespace: string;
  version: string;
  displayName: string;
  remoteEntry: string;
  state: PluginState;
  error?: string;
  loadedAt?: number;
  modules: Map<string, RemoteModule>;
}

export interface RemoteModule {
  name: string;
  component: React.ComponentType<unknown> | null;
  state: 'pending' | 'loading' | 'loaded' | 'error';
  error?: string;
}

// ========== Plugin API Response ==========

export interface PluginInfo {
  pid: string;
  pluginId: string;
  namespace: string;
  version: string;
  displayName: string;
  description?: string;
  author?: string;
  status: PluginStatus;
  installedAt?: string;
  enabledAt?: string;
  disabledAt?: string;
  settings?: Record<string, unknown>;
  manifest?: PluginManifest;

  // Unified package component flags
  hasConfig?: boolean;
  hasBackend?: boolean;
  hasFrontend?: boolean;

  // Backend component status
  backendPluginId?: string;
  backendStatus?: 'loaded' | 'started' | 'stopped' | 'failed';
  backendError?: string;

  // Frontend component status
  frontendRemoteUrl?: string;
  frontendStatus?: 'deployed' | 'loaded' | 'failed';
  frontendError?: string;
}

export interface PluginListResponse {
  plugins: PluginInfo[];
  total: number;
}

// ========== Slot System ==========

/**
 * Predefined extension slot IDs.
 */
export const SLOT_IDS = {
  // Page-level slots
  PAGE_HEADER_ACTIONS: 'page:header:actions',
  PAGE_FOOTER: 'page:footer',

  // Form slots
  FORM_BEFORE_SUBMIT: 'form:before-submit',
  FORM_AFTER_FIELDS: 'form:after-fields',
  FORM_TOOLBAR_EXTRA: 'form:toolbar:extra',

  // Table slots
  TABLE_TOOLBAR_EXTRA: 'table:toolbar:extra',
  TABLE_ROW_ACTIONS: 'table:row:actions',
  TABLE_FOOTER: 'table:footer',

  // Dashboard slots
  DASHBOARD_WIDGETS: 'dashboard:widgets',
  DASHBOARD_HEADER: 'dashboard:header',

  // Sidebar slots
  SIDEBAR_TOP: 'sidebar:top',
  SIDEBAR_BOTTOM: 'sidebar:bottom',

  // Detail page slots
  DETAIL_TABS_EXTRA: 'detail:tabs:extra',
  DETAIL_HEADER_EXTRA: 'detail:header:extra',
} as const;

export type SlotId = typeof SLOT_IDS[keyof typeof SLOT_IDS] | string;

export interface SlotProps {
  slotId: SlotId;
  context?: Record<string, unknown>;
  fallback?: React.ReactNode;
  className?: string;
}

export interface SlotContributionWithComponent extends SlotContribution {
  pluginId: string;
  component: React.ComponentType<SlotComponentProps>;
}

export interface SlotComponentProps {
  pluginId: string;
  namespace: string;
  slotId: SlotId;
  context: Record<string, unknown>;
}

// ========== Federation Manager State ==========

export interface FederationState {
  runtimeProfile: RuntimeProfile;
  plugins: Map<string, RemotePlugin>;
  slots: Map<SlotId, SlotContributionWithComponent[]>;
  isInitialized: boolean;
  error: string | null;
}

export interface FederationActions {
  loadPlugin: (manifest: PluginManifest) => Promise<void>;
  unloadPlugin: (pluginId: string) => void;
  reloadPlugin: (pluginId: string) => Promise<void>;
  loadModule: (pluginId: string, moduleName: string) => Promise<React.ComponentType<unknown> | null>;
  getSlotContributions: (slotId: SlotId) => SlotContributionWithComponent[];
  refreshPlugins: () => Promise<void>;
  setRuntimeProfile: (runtimeProfile: RuntimeProfile) => void;
  setError: (error: string | null) => void;
}

export type FederationStore = FederationState & FederationActions;

// ========== Plugin Context ==========

export interface PluginContextValue {
  pluginId: string;
  namespace: string;
  version: string;
  settings: Record<string, unknown>;
}

// ========== Loader Props ==========

export interface PluginLoaderProps {
  pluginId: string;
  moduleName: string;
  fallback?: React.ReactNode;
  errorFallback?: React.ReactNode | ((error: Error) => React.ReactNode);
  props?: Record<string, unknown>;
}

// ========== Sync State ==========

export interface PluginSyncState {
  lastSyncAt: number | null;
  isSyncing: boolean;
  syncError: string | null;
  enabledPlugins: string[];
}

// ========== Unified Package Types ==========

/**
 * Component status for unified packages.
 */
export type ComponentStatus = 'pending' | 'success' | 'failed' | 'skipped';

/**
 * Package status for unified plugin packages.
 */
export interface PackageStatus {
  pluginPid: string;
  pluginId: string;
  namespace: string;
  version: string;
  displayName: string;
  status: PluginStatus;

  // Component flags
  hasConfig: boolean;
  configStatus?: ComponentStatus;

  hasBackend: boolean;
  backendStatus?: string;
  backendPluginId?: string;
  backendError?: string;

  hasFrontend: boolean;
  frontendStatus?: string;
  frontendRemoteUrl?: string;
  frontendError?: string;

  // Resource counts
  resourceCounts?: Record<string, number>;

  // Timestamps
  installedAt?: string;
  enabledAt?: string;
  updatedAt?: string;
}

/**
 * Package history entry.
 */
export interface PackageHistoryEntry {
  pid: string;
  pluginPid?: string;
  pluginId: string;
  namespace: string;
  version: string;
  displayName?: string;

  sourceType: 'upload' | 'path' | 'url';
  sourceName?: string;

  configEnabled: boolean;
  configStatus?: ComponentStatus;
  configResourceCounts?: Record<string, number>;

  backendEnabled: boolean;
  backendStatus?: ComponentStatus;

  frontendEnabled: boolean;
  frontendStatus?: ComponentStatus;
  frontendRemoteUrl?: string;

  status: 'pending' | 'parsing' | 'installing_config' | 'installing_backend' | 'installing_frontend' | 'success' | 'failed' | 'rolling_back' | 'rolled_back';
  errorMessage?: string;
  canRollback: boolean;

  startedAt?: string;
  completedAt?: string;
  createdAt: string;
  createdBy?: number;
}

/**
 * Install options for unified packages.
 */
export interface PackageInstallOptions {
  skipConfig?: boolean;
  skipBackend?: boolean;
  skipFrontend?: boolean;
  forceOverwrite?: boolean;
  conflictStrategy?: 'skip' | 'overwrite' | 'fail';
  autoEnable?: boolean;
  startBackend?: boolean;
  broadcastFrontend?: boolean;
  dryRun?: boolean;
}

/**
 * Uninstall options for unified packages.
 */
export interface PackageUninstallOptions {
  skipConfig?: boolean;
  skipBackend?: boolean;
  skipFrontend?: boolean;
  removeAllData?: boolean;
  force?: boolean;
  defaultDecision?: 'delete' | 'detach' | 'keep';
  resourceDecisions?: Record<string, 'delete' | 'detach' | 'keep'>;
  removeFrontendAssets?: boolean;
  removeBackendJar?: boolean;
}
