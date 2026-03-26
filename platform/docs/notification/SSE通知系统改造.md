# 通知系统改造：从定时拉取到 SSE 推送模式

## 概述

本次改造将通知未读计数从定时拉取模式（每 60 秒轮询）改为 SSE（Server-Sent Events）实时推送模式，以提升用户体验和减少无效请求。

## 技术选型

选择 SSE 而非 WebSocket 的原因：

| 对比项 | SSE | WebSocket |
|-------|-----|-----------|
| 通信方向 | 单向（服务器→客户端）✅ | 双向 |
| 复杂度 | 低 ✅ | 高 |
| 依赖 | Spring MVC 原生支持 ✅ | 需额外配置 |
| 浏览器支持 | 原生 EventSource ✅ | 需处理兼容 |
| 自动重连 | 原生支持 ✅ | 需手动实现 |

## 架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│                        前端 (Header.tsx)                         │
│   EventSource('/api/notifications/stream')                      │
│   └── 监听 'unread-count' 事件 → setUnreadCount(data.count)     │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ SSE Connection
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                 NotificationSseController                        │
│   GET /api/notifications/stream (TEXT_EVENT_STREAM)             │
│   └── 调用 NotificationSseService.subscribe(userId)             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  NotificationSseService                          │
│   - userEmitters: Map<Long, CopyOnWriteArrayList<SseEmitter>>   │
│   - subscribe(userId): 创建并注册 SSE 连接                       │
│   - pushUnreadCount(userId, count): 推送更新到所有连接           │
│   - sendHeartbeat(): 每 30 秒发送心跳防止连接超时                │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │ pushUnreadCount()
         ┌────────────────────┴────────────────────┐
         │                                          │
┌────────┴────────┐                      ┌─────────┴─────────┐
│ NotificationService │                  │ NotificationQueryService │
│ createInAppNotification() │           │ markAsRead() / markAllAsRead() │
│ └── 新通知创建后推送   │              │ └── 标记已读后推送              │
└─────────────────────────┘             └───────────────────────────────┘
```

## 文件变更清单

### 新增文件

| 文件路径 | 说明 |
|---------|------|
| `notification/service/NotificationSseService.java` | SSE 服务接口 |
| `notification/service/impl/NotificationSseServiceImpl.java` | SSE 服务实现 |
| `notification/controller/NotificationSseController.java` | SSE 端点控制器 |

### 修改文件

| 文件路径 | 变更内容 |
|---------|----------|
| `notification/service/impl/NotificationServiceImpl.java` | 新通知创建后推送更新 |
| `notification/service/impl/NotificationQueryServiceImpl.java` | 标记已读后推送更新 |
| `notification/mapper/NotificationMapper.java` | 新增 `findUserIdById()` 方法 |
| `web-admin/app/routes/Header.tsx` | 从定时轮询改为 SSE 监听 |
| `web-admin/vite.config.ts` | 为 SSE 端点配置代理 |

## SSE 事件类型

| 事件名称 | 数据格式 | 说明 |
|---------|----------|------|
| `connected` | `{"status": "connected"}` | 连接建立确认 |
| `unread-count` | `{"count": number}` | 未读计数更新 |
| `heartbeat` | `{"timestamp": number}` | 心跳保活（每 30 秒） |

## 关键实现细节

### 1. 连接管理

```java
// 支持同一用户多个浏览器标签页
Map<Long, CopyOnWriteArrayList<SseEmitter>> userEmitters;

// 无超时设置，保持长连接
SseEmitter emitter = new SseEmitter(0L);
```

### 2. 清理机制

- `onCompletion`: 连接正常关闭
- `onTimeout`: 连接超时
- `onError`: 连接错误

### 3. 心跳机制

```java
@Scheduled(fixedRate = 30000)
public void sendHeartbeat() {
    // 防止代理/负载均衡器关闭空闲连接
}
```

### 4. 前端自动重连

EventSource 原生支持断线自动重连，无需额外代码。

## 测试验证

1. **启动后端服务**
   ```bash
   cd platform && ./gradlew bootRun
   ```

2. **启动前端服务**
   ```bash
   cd web-admin && npm run dev
   ```

3. **手动验证**
   - 打开浏览器 DevTools → Network → 过滤 EventStream
   - 登录系统，确认 `/api/notifications/stream` 连接建立
   - 通过其他方式触发新通知
   - 观察页面未读计数实时更新

## 注意事项

1. **多租户支持**: SSE 推送时需要考虑 tenantId
2. **认证**: SSE 端点复用现有 Cookie/Session 认证
3. **资源释放**: 用户登出或页面关闭时连接会自动清理
4. **Vite 代理**: 需要禁用 SSE 端点的响应缓冲
