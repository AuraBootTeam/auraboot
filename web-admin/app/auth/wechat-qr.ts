/**
 * Builder for the WeChat Open Platform PC qr-connect URL embedded on the
 * wechat-only login panel (`login_type=jssdk`).
 *
 * The embed renders its QR / quick-login card top-aligned inside the host
 * iframe, which leaves the panel bottom-heavy. The official `href` parameter
 * accepts a stylesheet URL (data: URIs included) that overrides the embed
 * styles; we center the card. `min-height` instead of a fixed `height` keeps
 * taller states (the full QR code) from clipping at the top of the frame.
 */
const WECHAT_QR_EMBED_CSS = [
  'html{height:100%!important}',
  'body{min-height:100%!important;margin:0!important;display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;background:transparent!important}',
].join('');

const WECHAT_QR_EMBED_HREF = `data:text/css;base64,${btoa(WECHAT_QR_EMBED_CSS)}`;

export function buildWechatPcQrConnectUrl(params: {
  appid: string;
  redirectUri: string;
  state: string;
}): string {
  const query = new URLSearchParams({
    appid: params.appid,
    scope: 'snsapi_login',
    redirect_uri: params.redirectUri,
    state: params.state,
    login_type: 'jssdk',
    self_redirect: 'false',
    href: WECHAT_QR_EMBED_HREF,
  });
  return `https://open.weixin.qq.com/connect/qrconnect?${query.toString()}`;
}
