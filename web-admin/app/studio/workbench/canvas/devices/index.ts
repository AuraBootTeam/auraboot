/**
 * Device Preview Module
 *
 * Device preview and viewport management for the canvas.
 *
 * @since 3.2.0
 */

// Types
export type {
  DeviceType,
  DeviceOrientation,
  DevicePreset,
  CustomDevice,
  ViewportState,
  ViewportDimensions,
} from './types';

// Presets
export {
  DEVICE_PRESETS,
  DEVICE_ICONS,
  DEVICE_TYPE_LABELS,
  getDevicePreset,
  getDefaultPreset,
  getPresetsByType,
  getGroupedPresets,
} from './presets';

// Components
export { DeviceSelector, default } from './DeviceSelector';

// Hooks
export { useViewport } from './useViewport';
