/**
 * Device Presets
 *
 * Predefined device sizes for preview mode.
 *
 * @since 3.2.0
 */

import type { DevicePreset, ZoomPreset } from './types';

/**
 * Device presets for preview
 */
export const DEVICE_PRESETS: DevicePreset[] = [
  {
    id: 'desktop',
    name: 'Desktop',
    icon: '🖥️',
    width: 1920,
    description: '1920px 宽',
  },
  {
    id: 'laptop',
    name: 'Laptop',
    icon: '💻',
    width: 1366,
    description: '1366px 宽',
  },
  {
    id: 'tablet-landscape',
    name: 'Tablet 横屏',
    icon: '📱',
    width: 1024,
    description: '1024px 宽',
  },
  {
    id: 'tablet',
    name: 'Tablet',
    icon: '📱',
    width: 768,
    description: '768px 宽',
  },
  {
    id: 'mobile',
    name: 'Mobile',
    icon: '📱',
    width: 375,
    description: '375px 宽',
  },
  {
    id: 'mobile-small',
    name: 'Mobile S',
    icon: '📱',
    width: 320,
    description: '320px 宽',
  },
];

/**
 * Zoom level presets
 */
export const ZOOM_PRESETS: ZoomPreset[] = [
  { value: 0.5, label: '50%' },
  { value: 0.75, label: '75%' },
  { value: 1, label: '100%' },
  { value: 1.25, label: '125%' },
  { value: 1.5, label: '150%' },
  { value: 2, label: '200%' },
];

/**
 * Zoom constraints
 */
export const ZOOM_MIN = 0.25;
export const ZOOM_MAX = 3;
export const ZOOM_STEP = 0.1;

/**
 * Get device by ID
 */
export function getDeviceById(id: string): DevicePreset | undefined {
  return DEVICE_PRESETS.find((d) => d.id === id);
}

/**
 * Get zoom label for value
 */
export function getZoomLabel(zoom: number): string {
  const preset = ZOOM_PRESETS.find((p) => Math.abs(p.value - zoom) < 0.01);
  return preset?.label || `${Math.round(zoom * 100)}%`;
}
