/**
 * Device Presets
 *
 * Predefined device configurations for preview.
 *
 * @since 3.2.0
 */

import type { DevicePreset } from './types';

/**
 * Device icons (SVG paths)
 */
export const DEVICE_ICONS = {
  desktop:
    'M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
  laptop: 'M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z',
  tablet: 'M12 18h.01M7 21h10a2 2 0 002-2V5a2 2 0 00-2-2H7a2 2 0 00-2 2v14a2 2 0 002 2z',
  mobile: 'M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z',
  custom:
    'M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z',
};

/**
 * Default device presets
 */
export const DEVICE_PRESETS: DevicePreset[] = [
  // Desktop
  {
    id: 'desktop-1920',
    name: '桌面 (1920×1080)',
    type: 'desktop',
    width: 1920,
    height: 1080,
    icon: DEVICE_ICONS.desktop,
    isDefault: true,
  },
  {
    id: 'desktop-1440',
    name: '桌面 (1440×900)',
    type: 'desktop',
    width: 1440,
    height: 900,
    icon: DEVICE_ICONS.desktop,
  },
  {
    id: 'desktop-1280',
    name: '桌面 (1280×720)',
    type: 'desktop',
    width: 1280,
    height: 720,
    icon: DEVICE_ICONS.desktop,
  },

  // Laptop
  {
    id: 'laptop-1366',
    name: '笔记本 (1366×768)',
    type: 'laptop',
    width: 1366,
    height: 768,
    icon: DEVICE_ICONS.laptop,
  },
  {
    id: 'macbook-pro-16',
    name: 'MacBook Pro 16"',
    type: 'laptop',
    width: 1728,
    height: 1117,
    dpr: 2,
    icon: DEVICE_ICONS.laptop,
  },
  {
    id: 'macbook-air-13',
    name: 'MacBook Air 13"',
    type: 'laptop',
    width: 1440,
    height: 900,
    dpr: 2,
    icon: DEVICE_ICONS.laptop,
  },

  // Tablet
  {
    id: 'ipad-pro-12',
    name: 'iPad Pro 12.9"',
    type: 'tablet',
    width: 1024,
    height: 1366,
    dpr: 2,
    icon: DEVICE_ICONS.tablet,
  },
  {
    id: 'ipad-10',
    name: 'iPad 10.2"',
    type: 'tablet',
    width: 810,
    height: 1080,
    dpr: 2,
    icon: DEVICE_ICONS.tablet,
  },
  {
    id: 'ipad-mini',
    name: 'iPad Mini',
    type: 'tablet',
    width: 744,
    height: 1133,
    dpr: 2,
    icon: DEVICE_ICONS.tablet,
  },
  {
    id: 'surface-pro',
    name: 'Surface Pro',
    type: 'tablet',
    width: 912,
    height: 1368,
    icon: DEVICE_ICONS.tablet,
  },

  // Mobile
  {
    id: 'iphone-15-pro-max',
    name: 'iPhone 15 Pro Max',
    type: 'mobile',
    width: 430,
    height: 932,
    dpr: 3,
    icon: DEVICE_ICONS.mobile,
  },
  {
    id: 'iphone-15-pro',
    name: 'iPhone 15 Pro',
    type: 'mobile',
    width: 393,
    height: 852,
    dpr: 3,
    icon: DEVICE_ICONS.mobile,
  },
  {
    id: 'iphone-se',
    name: 'iPhone SE',
    type: 'mobile',
    width: 375,
    height: 667,
    dpr: 2,
    icon: DEVICE_ICONS.mobile,
  },
  {
    id: 'pixel-8',
    name: 'Pixel 8',
    type: 'mobile',
    width: 412,
    height: 915,
    dpr: 2.625,
    icon: DEVICE_ICONS.mobile,
  },
  {
    id: 'samsung-s24',
    name: 'Samsung S24',
    type: 'mobile',
    width: 360,
    height: 780,
    dpr: 3,
    icon: DEVICE_ICONS.mobile,
  },

  // Custom placeholder
  {
    id: 'custom',
    name: '自定义尺寸',
    type: 'custom',
    width: 1024,
    height: 768,
    icon: DEVICE_ICONS.custom,
  },
];

/**
 * Get device preset by ID
 */
export function getDevicePreset(id: string): DevicePreset | undefined {
  return DEVICE_PRESETS.find((d) => d.id === id);
}

/**
 * Get default device preset
 */
export function getDefaultPreset(): DevicePreset {
  return DEVICE_PRESETS.find((d) => d.isDefault) || DEVICE_PRESETS[0];
}

/**
 * Get presets by type
 */
export function getPresetsByType(type: DevicePreset['type']): DevicePreset[] {
  return DEVICE_PRESETS.filter((d) => d.type === type);
}

/**
 * Group presets by type
 */
export function getGroupedPresets(): Map<DevicePreset['type'], DevicePreset[]> {
  const grouped = new Map<DevicePreset['type'], DevicePreset[]>();

  DEVICE_PRESETS.forEach((preset) => {
    const list = grouped.get(preset.type) || [];
    list.push(preset);
    grouped.set(preset.type, list);
  });

  return grouped;
}

/**
 * Type labels
 */
export const DEVICE_TYPE_LABELS: Record<DevicePreset['type'], string> = {
  desktop: '桌面',
  laptop: '笔记本',
  tablet: '平板',
  mobile: '手机',
  custom: '自定义',
};

export default DEVICE_PRESETS;
