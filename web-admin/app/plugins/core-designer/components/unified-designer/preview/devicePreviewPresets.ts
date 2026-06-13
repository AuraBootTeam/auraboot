/**
 * Device-preview presets for the Unified Designer runtime preview.
 *
 * The unified designer preview (RecursiveBlockRenderer) renders full-width by
 * default. For pages whose real target is a phone / mini-program (e.g. AuraQR
 * scan-landing pages), authors need to preview at the real device width with the
 * platform's safe-area insets so chrome (WeChat/Alipay capsule + home indicator)
 * does not overlap content. This module is the preset data + a pure style helper;
 * the workbench renders a selector and wraps the preview in the resulting frame.
 *
 * Side-effect free — safe to unit test.
 */
import type { CSSProperties } from 'react';

export interface DevicePreviewPreset {
  /** Stable id used as the selector value. */
  id: string;
  /** Human label (zh-CN). */
  label: string;
  /**
   * Frame width in CSS px. `null` = full width (no device frame), used for the
   * desktop default.
   */
  width: number | null;
  /** Top safe-area inset in px (status bar / mini-program nav capsule). */
  safeAreaTop: number;
  /** Bottom safe-area inset in px (home indicator / tab bar). */
  safeAreaBottom: number;
}

/**
 * Ordered presets. `full` is first so it is the default.
 * Mini-program insets approximate the iOS logical viewport (375pt) with the
 * WeChat/Alipay nav capsule on top and the home-indicator inset at the bottom.
 */
export const DEVICE_PREVIEW_PRESETS: DevicePreviewPreset[] = [
  { id: 'full', label: '桌面', width: null, safeAreaTop: 0, safeAreaBottom: 0 },
  { id: 'mobile', label: '手机 H5', width: 375, safeAreaTop: 0, safeAreaBottom: 0 },
  { id: 'wechat', label: '微信小程序', width: 375, safeAreaTop: 44, safeAreaBottom: 34 },
  { id: 'alipay', label: '支付宝小程序', width: 375, safeAreaTop: 48, safeAreaBottom: 34 },
];

export const DEFAULT_DEVICE_PREVIEW_ID = DEVICE_PREVIEW_PRESETS[0].id;

/** Resolve a preset by id, falling back to the first (full-width) preset. */
export function getDevicePreviewPreset(id: string | null | undefined): DevicePreviewPreset {
  return DEVICE_PREVIEW_PRESETS.find((preset) => preset.id === id) ?? DEVICE_PREVIEW_PRESETS[0];
}

/**
 * CSS style for the device frame wrapper.
 * Full-width presets return an empty style (no constraint); device presets
 * constrain the width and apply the safe-area insets as padding.
 */
export function getDeviceFrameStyle(preset: DevicePreviewPreset): CSSProperties {
  if (preset.width == null) return {};
  return {
    maxWidth: `${preset.width}px`,
    marginLeft: 'auto',
    marginRight: 'auto',
    paddingTop: `${preset.safeAreaTop}px`,
    paddingBottom: `${preset.safeAreaBottom}px`,
  };
}

/** Whether a preset draws a device frame (i.e. is not the full-width default). */
export function isDeviceFramed(preset: DevicePreviewPreset): boolean {
  return preset.width != null;
}
