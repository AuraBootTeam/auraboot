/**
 * Canvas Viewport Types
 *
 * Type definitions for canvas zoom, pan, and device preview.
 *
 * @since 3.2.0
 */

/**
 * Viewport state
 */
export interface ViewportState {
  /** Zoom level (0.5 - 2.0) */
  zoom: number;
  /** Pan offset */
  pan: { x: number; y: number };
  /** Device preview width (null for no constraint) */
  deviceWidth: number | null;
  /** Device preview name */
  deviceName: string | null;
}

/**
 * Device preset for preview
 */
export interface DevicePreset {
  id: string;
  name: string;
  icon: string;
  width: number;
  description?: string;
}

/**
 * Zoom level preset
 */
export interface ZoomPreset {
  value: number;
  label: string;
}

/**
 * Viewport actions
 */
export interface ViewportActions {
  setZoom: (zoom: number) => void;
  zoomIn: () => void;
  zoomOut: () => void;
  zoomToFit: () => void;
  zoomToSelection: () => void;
  resetZoom: () => void;
  setPan: (pan: { x: number; y: number }) => void;
  panBy: (delta: { x: number; y: number }) => void;
  resetPan: () => void;
  setDevice: (deviceId: string | null) => void;
  reset: () => void;
}

/**
 * Viewport hook result
 */
export interface UseViewportResult extends ViewportState, ViewportActions {
  /** Is panning mode active (Space held) */
  isPanning: boolean;
  /** CSS transform string */
  transform: string;
  /** Container style */
  containerStyle: React.CSSProperties;
  /** Canvas style */
  canvasStyle: React.CSSProperties;
}
