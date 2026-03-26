/**
 * 会话管理服务
 */

import axios from 'axios';
import type { ChatMessage } from '~/chat/types';

const API_BASE_URL = '/chat';

/**
 * 会话信息
 */
export interface SessionInfo {
  session_id: string;
  summary?: string;
  last_message?: string;
  last_updated: string;
  message_count: number;
  created_at: string;
}

/**
 * 获取用户会话列表
 */
export async function getSessionList(params?: {
  limit?: number;
  offset?: number;
}): Promise<{ sessions: SessionInfo[]; total: number }> {
  const response = await axios.get<{ sessions: SessionInfo[]; total: number }>(
    `${API_BASE_URL}/sessions`,
    { params },
  );
  return response.data;
}

/**
 * 获取会话历史消息
 */
export async function getSessionHistory(
  sessionId: string,
  params?: {
    limit?: number;
    offset?: number;
  },
): Promise<ChatMessage[]> {
  const response = await axios.get<{ messages: any[] }>(
    `${API_BASE_URL}/sessions/${sessionId}/history`,
    { params },
  );

  // 转换为前端格式
  return response.data.messages.map((msg: any) => ({
    id: msg.message_id || `msg-${Date.now()}`,
    role: msg.role,
    content: msg.content,
    thinkingProcess: msg.thinking_process,
    timestamp: new Date(msg.created_at),
    citations: msg.citations,
  }));
}

/**
 * 删除会话
 */
export async function deleteSession(sessionId: string): Promise<void> {
  await axios.delete(`${API_BASE_URL}/sessions/${sessionId}`);
}

/**
 * 格式化时间显示
 */
export function formatTimeAgo(date: string | Date): string {
  const now = new Date();
  const past = new Date(date);
  const diffMs = now.getTime() - past.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return '刚刚';
  if (diffMins < 60) return `${diffMins} 分钟前`;
  if (diffHours < 24) return `${diffHours} 小时前`;
  if (diffDays < 7) return `${diffDays} 天前`;

  return past.toLocaleDateString('zh-CN');
}
