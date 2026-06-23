---
type: product-doc
status: active
---

# 反向代理与品牌子域名部署

本指南介绍如何把一套 AuraBoot 实例**透出到一个由其它主机承载的品牌域名下**——例如把
部署在独立机器上的 AuraBoot 以 `ai.example.com`(或 `example.com/ai/`)的形式,挂在企业
已有站点 `example.com` 后面对外。

涵盖两种布局:

- **品牌子域名**(`ai.example.com`)——**推荐**。AuraBoot 几乎零改动,应用仍运行在 URL 根路径。
- **子路径**(`example.com/ai/`)——支持,但因为 AuraBoot 是 SSR + Module Federation 架构,
  改造量明显更大。见文末「附录:子路径(`/ai/xxx`)部署」。

## AuraBoot 不是静态 SPA

选布局前先理解运行时拓扑。AuraBoot 的 web 管理端**不是**一堆可以直接丢到 `try_files`
后面的静态文件,而是一个由 Node **BFF** 托管的服务端渲染(SSR)应用,BFF 同时负责代理
API 流量:

```text
浏览器
  → 边缘反向代理               (终止 TLS,品牌域名)
  → BFF (Node, 默认 :3500)     (SSR + 提供 /assets + 代理 /api)
  → Spring Boot (默认 :6443)
```

几个关键结论:

- 边缘代理必须把**全部**流量转发给 BFF——不要在边缘直接服务静态文件,也没有 SPA
  `index.html` fallback 要配。BFF(`web-admin/app/server/bff.server.ts`)负责 SSR、
  `/assets` 挂载,以及把 `/api` 代理到后端(`SPRING_BOOT_URL`)。
- 浏览器始终只跟**一个 origin**(品牌域名)通信。前端 API 调用基于
  `window.location.origin` 推导(`web-admin/app/shared/services/http-client/URLBuilder.ts`),
  因此都是同源请求,**正常流程下无需任何 CORS 配置**。
- 企业版插件通过 **Module Federation** 在运行时加载
  (`web-admin/app/plugins/FederationManager.ts`)。它们的 `remoteEntry.js` URL 是根相对的,
  在域名根下是正确的,但**在子路径下会失效**,除非把每个 URL 都改成带前缀(见附录)。

## 子域名还是子路径

| 维度 | `ai.example.com`(子域名) | `example.com/ai/`(子路径) |
|---|---|---|
| AuraBoot 源码改动 | **几乎为 0**(仅部署配置) | Vite base + router basename + Module Federation + BFF + 后端 |
| Module Federation 风险 | 无(根相对 URL 可用) | 高(不加前缀 `remoteEntry.js` 404) |
| 边缘配置 | 一个 `server` 块 → BFF | 需带前缀且不能 rewrite path |
| TLS | 需子域名证书(`*.example.com` 通配或单签) | 复用 `example.com` 证书 |
| 与 `example.com` 共享会话/cookie | 否(不同 origin) | 是(同 origin) |

**除非有「必须与父站点在单一 origin 下共享 cookie/会话」的硬需求,否则选子域名。**
AuraBoot 用 `Authorization` header 里的 JWT 做无状态认证(见 `SecurityConfig`),它自身的
登录并不依赖与父站点共享 cookie。

---

## 品牌子域名部署(推荐)

### 1. DNS

把子域名指向负责终止 TLS 的那台主机:

- **最简单**:`ai.example.com` → 直接指向 AuraBoot 主机。该主机的 nginx 终止 TLS 并代理到
  本机 BFF,父站点完全不参与。
- **走父站点边缘**:`ai.example.com` → 父站点边缘(复用其 WAF/CDN),再由它 upstream 到
  AuraBoot 主机的 BFF。

### 2. TLS

准备覆盖 `ai.example.com` 的证书——`*.example.com` 通配证书或单独签发都可以。

### 3. 边缘反向代理

加一个 `server` 块。upstream 是 **BFF**,不是后端——BFF 既服务 UI 又把 `/api` 往后代理。

```nginx
map $http_upgrade $connection_upgrade { default upgrade; '' close; }

upstream auraboot_bff {
    server 127.0.0.1:3500;   # BFF 的 host:port(BFF_PORT)。代理与 AuraBoot
    keepalive 32;            # 不在同一台机器时,改成内网 IP。
}

server {
    listen 443 ssl;
    server_name ai.example.com;

    ssl_certificate     /etc/ssl/example.com/fullchain.pem;   # *.example.com 或 ai.example.com
    ssl_certificate_key /etc/ssl/example.com/privkey.pem;

    # 流式端点(SSE):关闭缓冲,让 token 实时下发。
    # 匹配 /api/aurabot/chat/stream、/api/agent/events/stream、
    # /api/notifications/stream、/api/admin/automation-runs/*/llm-stream。
    location ~ ^/api/.*(stream|llm-stream) {
        proxy_pass http://auraboot_bff;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-Host  $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
    }

    location / {
        proxy_pass http://auraboot_bff;     # 根路径,不做 rewrite
        proxy_http_version 1.1;

        proxy_set_header Host              $host;   # ai.example.com
        proxy_set_header X-Forwarded-Host  $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Real-IP         $remote_addr;

        # WebSocket(IM,如启用)
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection $connection_upgrade;

        client_max_body_size 50m;           # 按上传上限调整
        proxy_read_timeout   3600s;
    }
}
```

注意这里**没有 path rewrite**——品牌域名的根映射到应用的根。

### 4. CORS——无需改动

浏览器位于 `ai.example.com`,调用 `ai.example.com/api/*`,由 BFF 在服务端代理给后端
(浏览器看不到跨域请求)。后端的 CORS 白名单(`cors.allowed-origins`,在 `SecurityConfig`
中注册于 `/api/**`)在这个流程里**不会被触发**。如果你另有面向浏览器的跨域客户端,把它的
origin 加进 `CORS_ALLOWED_ORIGINS`;标准同源部署保持现状即可。

### 5. 可选——绝对 URL 与 OAuth 回调

上面的配置已足够支撑登录、页面、API 和 SSE。唯一需要感知外部域名的,是**后端自己拼出的
绝对 URL**——主要是邮箱集成的 OAuth 回调。若你启用了这些功能:

1. 让后端信任代理头:

   ```bash
   SERVER_FORWARD_HEADERS_STRATEGY=framework    # 映射到 server.forward-headers-strategy
   ```

   当请求里没有 `X-Forwarded-*` 时它是 no-op,因此按部署单独设置是安全的(直连部署的默认值
   仍为 `none`)。

2. 在 Provider 后台把回调 URI 注册成品牌域名,例如 `https://ai.example.com/...`。前端本就用
   `window.location.origin` 推导回调 URI,会自动落到品牌域名。

> **已知限制**:BFF 目前不会把 `X-Forwarded-*` 转发给后端
> (`web-admin/app/server/services/BffProxyService.ts`)。如果某个后端自建的绝对 URL 必须反映
> 外部域名,需要扩展 BFF 把这些头转发出去。标准的品牌透出场景不需要这一步。

### 6. 验收

```bash
curl -I https://ai.example.com/                 # 200,text/html(BFF SSR)
curl -i https://ai.example.com/api/auth/login   # 后端响应,而非 404
```

浏览器里检查:

- 所有 JS/CSS/图片从品牌域名根 `200` 加载(没有 `*/assets` 404)。
- 打开一个企业版(`ent-*`)插件页 → 它的 `remoteEntry.js` `200` 加载。
- 直达/刷新一个动态页(`/p/<pageKey>`)→ 正常渲染,不 404、不跳转。
- 某个流式功能(如智能助手)持续流式输出,未被缓冲。
- (若启用 OAuth)授权往返回到品牌域名。

---

## 附录:子路径(`/ai/xxx`)部署

子路径托管能与父站点保持单一 origin,但要求应用在每个环节都吐出带前缀的 URL。因为
AuraBoot 是 SSR + Module Federation,单靠边缘解决不了——即便在代理处剥掉前缀,应用仍会吐出
根相对 URL(`/assets/*`、`/api/*`、`/p/*`),浏览器会把它们解析到父站点的根。前缀必须全链路
携带。

需要的改动,按区域(成本更高——逐项验证):

| 区域 | 位置 | 改动 |
|---|---|---|
| 资源 base | `web-admin/vite.config.ts` | 设 `base`(如 `/ai/`),使构建产物吐出 `/ai/assets/*` |
| BFF 静态挂载 | `web-admin/app/server/bff.server.ts` | 把 assets 挂到 `/ai/assets`,与新 base 对齐 |
| Router basename | React Router 配置 | 设 `basename`,使 `/ai/p/...` 能匹配 |
| Module Federation | `web-admin/app/plugins/usePluginSync.ts`、`FederationManager.ts` | 给 `remoteEntry.js` URL 加前缀,或让后端返回带前缀的 URL——**否则插件 404** |
| API base(CSR) | `web-admin/app/shared/services/http-client/URLBuilder.ts` | 从 `window.location.origin` 推导 base 时带上前缀 |
| 后端路径 | `application.yml` **或** BFF | 要么 `server.servlet.context-path=/ai`,要么在 BFF 转发前剥掉 `/ai` |
| 菜单路径 | `web-admin/app/shared/services/menu.ts` + 后端 `/api/menu/user` | 返回/导航时带前缀 |
| 硬编码跳转 | 如 `window.location.href = '/login'`;后端 `sendRedirect("/email/settings")` | 改成带前缀 |
| Forwarded 前缀 | BFF + 后端 | 为后端自建绝对 URL 注入/读取 `X-Forwarded-Prefix` |

子路径的边缘 nginx 保留前缀(`proxy_pass` 不带尾斜杠、不 rewrite):

```nginx
location ^~ /ai/ {
    proxy_pass http://auraboot_bff;        # 不要写成 http://auraboot_bff/(那会剥掉 /ai)
    proxy_http_version 1.1;
    proxy_set_header Host              $host;
    proxy_set_header X-Forwarded-Host  $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Prefix /ai;
    proxy_set_header Upgrade    $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
    proxy_read_timeout 3600s;
}
```

## 故障排查

| 现象 | 可能原因 |
|---|---|
| 白屏,JS/CSS 在根路径 404 | 子路径下没设 Vite `base` / BFF 静态挂载前缀 |
| 企业版插件页空白 | Module Federation `remoteEntry.js` 请求到了错误路径 |
| 动态页刷新 404 | 边缘没把全部路径转发给 BFF |
| API 请求丢了前缀 | CSR base 未带前缀(仅子路径) |
| OAuth `redirect_uri` 不匹配 | Provider 未按品牌域名注册,或未设 `SERVER_FORWARD_HEADERS_STRATEGY` |
| 流式响应一次性返回 | stream `location` 未关闭 `proxy_buffering` |

## 参见

- [配置参考](configuration.md) —— 环境变量与 `application.yml`
- [Docker](docker.md) —— Docker Compose 生产部署
- [Kubernetes](kubernetes.md) —— K8s/Helm 部署
