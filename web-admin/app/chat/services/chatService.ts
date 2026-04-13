/**
 * Chat API 服务
 */

import type { ChatMessage, ChatRequest, SSEEvent } from '~/chat/types';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

const API_BASE_URL = '/api/ai';

/**
 * 发送聊天消息（SSE 流式）
 *
 * SSE 流式响应需要直接使用 fetch（无法使用 fetchResult）
 * 但需要手动添加 Authorization header
 *
 * @param request 聊天请求
 * @param token JWT token（从服务器端 loader 传递）
 */
export async function* streamChat(
  request: ChatRequest,
  token: string | null,
): AsyncGenerator<SSEEvent> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };

  // 添加 Authorization header
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const response = await fetch(`${API_BASE_URL}/chat`, {
    method: 'post',
    headers,
    body: JSON.stringify(request),
    credentials: 'include',
  });

  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`);
  }

  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error('Response body is null');
  }

  const decoder = new TextDecoder();
  let buffer = '';
  let currentEventType: string | null = null;

  try {
    while (true) {
      const { done, value } = await reader.read();

      if (done) {
        break;
      }

      buffer += decoder.decode(value, { stream: true });

      // 处理 SSE 事件
      const lines = buffer.split('\n');
      buffer = lines.pop() || ''; // 保留最后一行（可能不完整）

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEventType = line.substring(6).trim();
          continue;
        }

        if (line.startsWith('data:')) {
          const dataStr = line.substring(5).trim();

          if (dataStr === '[DONE]') {
            return;
          }

          try {
            const data = JSON.parse(dataStr);

            // 使用之前解析的 event 类型
            let eventType: SSEEvent['type'] = 'text_delta';

            if (currentEventType === 'text_delta') {
              eventType = 'text_delta';
            } else if (currentEventType === 'reasoning_delta') {
              eventType = 'reasoning_delta';
            } else if (currentEventType === 'citation') {
              eventType = 'citation';
            } else if (currentEventType === 'tool_status') {
              eventType = 'tool_status';
            } else if (currentEventType === 'error') {
              eventType = 'error';
            } else if (currentEventType === 'done') {
              eventType = 'done';
            } else if (data.citations !== undefined) {
              eventType = 'citation';
            } else if (data.content !== undefined) {
              eventType = 'text_delta';
            } else if (data.message !== undefined) {
              eventType = 'error';
            }

            yield {
              type: eventType,
              data,
            };

            // 重置事件类型（SSE 规范：每个事件后重置）
            currentEventType = null;
          } catch (e) {
            console.error('Failed to parse SSE data:', dataStr, e);
          }
        }

        // 空行表示事件结束
        if (line === '') {
          currentEventType = null;
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

/**
 * 获取会话历史
 * 使用 fetchResult 统一 API 调用（会自动处理 token）
 */
export async function getSessionHistory(
  sessionId: string,
  limit: number = 50,
  offset: number = 0,
): Promise<ChatMessage[]> {
  try {
    const result = await fetchResult<ChatMessage[]>(
      `${API_BASE_URL}/sessions/{sessionId}/history`,
      {
        method: 'get',
        params: {
          sessionId,
          limit,
          offset,
        },
      },
    );

    // 检查返回结果
    if (!ResultHelper.isSuccess(result) || !result.data) {
      console.warn('Failed to fetch session history:', result);
      return [];
    }

    // 检查返回的数据是否是数组
    if (!Array.isArray(result.data)) {
      console.warn('Session history response is not an array:', result.data);
      return [];
    }

    return result.data.map((msg: any) => ({
      id: msg.message_id || msg.id,
      role: msg.role,
      content: msg.content,
      timestamp: new Date(msg.created_at || msg.timestamp),
    }));
  } catch (error) {
    // 网络错误或其他异常，返回空数组
    console.warn('Failed to load session history:', error);
    return [];
  }
}

/**
 * 获取用户会话列表
 */
export async function getUserSessions(): Promise<any[]> {
  // TODO: 实现获取会话列表的 API
  // 目前返回空数组
  return [];
}
