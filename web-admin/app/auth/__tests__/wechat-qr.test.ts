import { describe, expect, it } from 'vitest';
import { buildWechatPcQrConnectUrl } from '../wechat-qr';

describe('wechat pc qr-connect url builder', () => {
  it('carries the qr-connect contract parameters', () => {
    const url = buildWechatPcQrConnectUrl({
      appid: 'wx1234567890abcdef',
      redirectUri: 'https://school.example.com/login/social/wechat_web/callback',
      state: 'st-123',
    });

    const parsed = new URL(url);
    expect(parsed.origin + parsed.pathname).toBe('https://open.weixin.qq.com/connect/qrconnect');
    expect(parsed.searchParams.get('appid')).toBe('wx1234567890abcdef');
    expect(parsed.searchParams.get('scope')).toBe('snsapi_login');
    expect(parsed.searchParams.get('redirect_uri')).toBe(
      'https://school.example.com/login/social/wechat_web/callback',
    );
    expect(parsed.searchParams.get('state')).toBe('st-123');
    expect(parsed.searchParams.get('login_type')).toBe('jssdk');
    expect(parsed.searchParams.get('self_redirect')).toBe('false');
  });

  it('embeds the centering stylesheet through the official href parameter', () => {
    const url = buildWechatPcQrConnectUrl({
      appid: 'wx1234567890abcdef',
      redirectUri: 'https://school.example.com/cb',
      state: 'st-123',
    });

    const href = new URL(url).searchParams.get('href');
    expect(href).toBeTruthy();
    expect(href).toMatch(/^data:text\/css;base64,/);

    // The embed renders its card top-aligned; the stylesheet must re-center it
    // while keeping taller states (full QR) from clipping via min-height.
    const css = atob((href as string).split('base64,')[1]);
    expect(css).toContain('justify-content:center');
    expect(css).toContain('align-items:center');
    expect(css).toContain('min-height:100%');
  });
});
