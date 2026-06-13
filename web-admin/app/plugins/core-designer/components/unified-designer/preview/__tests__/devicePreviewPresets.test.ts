import { describe, it, expect } from 'vitest';
import {
  DEVICE_PREVIEW_PRESETS,
  DEFAULT_DEVICE_PREVIEW_ID,
  getDevicePreviewPreset,
  getDeviceFrameStyle,
  isDeviceFramed,
} from '../devicePreviewPresets';

describe('device preview presets (D7 — mini-program dual preview)', () => {
  it('ships full / mobile / wechat / alipay presets, full first (default)', () => {
    const ids = DEVICE_PREVIEW_PRESETS.map((p) => p.id);
    expect(ids).toEqual(['full', 'mobile', 'wechat', 'alipay']);
    expect(DEFAULT_DEVICE_PREVIEW_ID).toBe('full');
  });

  it('full preset is unframed and yields no width/safe-area constraint', () => {
    const full = getDevicePreviewPreset('full');
    expect(full.width).toBeNull();
    expect(isDeviceFramed(full)).toBe(false);
    expect(getDeviceFrameStyle(full)).toEqual({});
  });

  it('wechat preset constrains to 375px and applies safe-area insets as padding', () => {
    const wechat = getDevicePreviewPreset('wechat');
    expect(wechat.width).toBe(375);
    expect(isDeviceFramed(wechat)).toBe(true);
    expect(getDeviceFrameStyle(wechat)).toMatchObject({
      maxWidth: '375px',
      paddingTop: '44px',
      paddingBottom: '34px',
    });
  });

  it('alipay has a distinct (larger) top safe-area than wechat', () => {
    expect(getDevicePreviewPreset('alipay').safeAreaTop).toBeGreaterThan(
      getDevicePreviewPreset('wechat').safeAreaTop,
    );
  });

  it('falls back to the full-width default for an unknown id', () => {
    expect(getDevicePreviewPreset('nope').id).toBe('full');
    expect(getDevicePreviewPreset(null).id).toBe('full');
  });
});
