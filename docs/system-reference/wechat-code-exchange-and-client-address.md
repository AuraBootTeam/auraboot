---
type: system-reference
status: active
updated: 2026-10-26
---

# Server-side WeChat code exchange and client addresses

`WechatOAuthClient` exchanges a one-use code against the explicitly selected application's credentials. It does not create platform users, bind business accounts, issue sessions, validate browser state, or grant domain permissions. Callers must validate and consume their browser-bound state before exchange and bind the returned identity to the original application and business request. Do not accept an openid supplied by a browser as verified identity.

The client requires the requested OAuth scope. Official-account `snsapi_base` may also accept a `snsapi_userinfo` grant; website-app `snsapi_login` does not satisfy either official-account scope. `WechatPcClient` uses the same exchange client with its website-app scope and treats userinfo solely as optional profile hints. Token-bearing URLs and provider response data are excluded from public exceptions. Grant identity and token fields are excluded from Jackson output and diagnostic strings.

The default API is `https://api.weixin.qq.com`. Explicit configuration may select an owned HTTP loopback fixture; other API destinations, credentials in authority, paths, query strings and fragments are rejected. The HTTP request has a ten-second deadline and no application retry. Production callers must not pass browser-controlled API bases. The shared HTTP client is closed with its owning Spring component or by a programmatic owner.

`TrustedClientAddress` consumes the **raw socket peer** and X-Forwarded-For values. Empty trusted-proxy configuration uses only the peer and ignores forwarded input. For a configured trusted peer, exactly one bounded header is required; hops are peeled from the right until the first untrusted address, so arbitrary values to its left cannot become the client. Addresses and CIDRs must be numeric literals; hostnames are never resolved. Missing or ambiguous evidence from a trusted proxy fails closed.

The caller must disable servlet/container forwarded-header rewriting before supplying the peer. The ingress must remove or correctly append untrusted forwarding headers, and backend network access must preserve the declared trust boundary. This utility does not establish those deployment facts. It does not consume Forwarded or X-Real-IP implicitly, infer proxy trust from private address ranges, or verify a payment product's eligibility.

Protocol references: [Tencent's official-account integration guide](https://docs.cloudbase.net/en/integration/wechat-official-oauth), [WeChat webpage authorization](https://developers.weixin.qq.com/doc/offiaccount/OA_Web_Apps/Wechat_webpage_authorization.html). Actual merchant, browser and callback-domain eligibility require separate integration acceptance.
