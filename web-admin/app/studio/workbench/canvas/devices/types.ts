/**
 * Device Preview Types
 *
 * Types for device preview functionality.
 *
 * @since 3.2.0
 */

/**
 * Device type
 */
export type DeviceType = 'desktop' | 'laptop' | 'tablet' | 'mobile' | 'custom';

/**
 * Device orientation
 */
export type DeviceOrientation = 'portrait' | 'landscape';

/**
 * Device preset
 */
export interface DevicePreset {
  /** Device ID */
  id: string;
  /** Display name */
  name: string;
  /** Device type */
  type: DeviceType;
  /** Width in pixels */
  width: number;
  /** Height in pixels */
  height: number;
  /** Device pixel ratio */
  dpr?: number;
  /** Icon */
  icon: string;
  /** Whether this is a default preset */
  isDefault?: boolean;
}

/**
 * Custom device
 */
export interface CustomDevice {
  /** Display name */
  name: string;
  /** Width */
  width: number;
  /** Height */
  height: number;
}

/**
 * Viewport state
 */
export interface ViewportState {
  /** Current device ID */
  deviceId: string;
  /** Current orientation */
  orientation: DeviceOrientation;
  /** Custom width (for custom device) */
  customWidth?: number;
  /** Custom height (for custom device) */
  customHeight?: number;
  /** Zoom level (percentage) */
  zoom: number;
  /** Pan offset X */
  panX: number;
  /** Pan offset Y */
  panY: number;
}

/**
 * Viewport dimensions
 */
export interface ViewportDimensions {
  /** Effective width */
  width: number;
  /** Effective height */
  height: number;
  /** Scale factor */
  scale: number;
}
