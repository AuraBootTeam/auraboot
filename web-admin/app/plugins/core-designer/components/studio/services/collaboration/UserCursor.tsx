/**
 * 用户光标组件
 *
 * 显示其他用户的光标位置和选择状态
 */

import React, { useEffect, useState } from 'react';
import type {
  CursorPosition,
  CollaborationUser,
} from '~/plugins/core-designer/components/studio/services/collaboration/CollaborationManager';

/**
 * 用户光标属性
 */
export interface UserCursorProps {
  cursor: CursorPosition;
  user: CollaborationUser;
  containerRef: React.RefObject<HTMLElement>;
}

/**
 * 光标样式
 */
interface CursorStyle {
  left: number;
  top: number;
  visible: boolean;
}

/**
 * 用户光标组件
 */
export const UserCursor: React.FC<UserCursorProps> = ({ cursor, user, containerRef }) => {
  const [cursorStyle, setCursorStyle] = useState<CursorStyle>({
    left: 0,
    top: 0,
    visible: false,
  });

  useEffect(() => {
    if (!containerRef.current || !cursor.componentId) {
      setCursorStyle((prev) => ({ ...prev, visible: false }));
      return;
    }

    // 查找目标组件元素
    const targetElement = containerRef.current.querySelector(
      `[data-component-id="${cursor.componentId}"]`,
    );

    if (!targetElement) {
      setCursorStyle((prev) => ({ ...prev, visible: false }));
      return;
    }

    // 计算光标位置
    const containerRect = containerRef.current.getBoundingClientRect();
    const targetRect = targetElement.getBoundingClientRect();

    let left = targetRect.left - containerRect.left;
    let top = targetRect.top - containerRect.top;

    // 如果有属性路径，尝试定位到具体的输入框
    if (cursor.propertyPath) {
      const propertyElement = targetElement.querySelector(
        `[data-property="${cursor.propertyPath}"]`,
      );

      if (propertyElement) {
        const propertyRect = propertyElement.getBoundingClientRect();
        left = propertyRect.left - containerRect.left;
        top = propertyRect.top - containerRect.top;

        // 如果有选择范围，计算具体位置
        if (cursor.selection && propertyElement instanceof HTMLInputElement) {
          const textWidth = getTextWidth(
            propertyElement.value.substring(0, cursor.selection.start),
            getComputedStyle(propertyElement),
          );
          left += textWidth;
        }
      }
    }

    setCursorStyle({
      left,
      top,
      visible: true,
    });
  }, [cursor, containerRef]);

  if (!cursorStyle.visible) {
    return null;
  }

  return (
    <div
      className="pointer-events-none absolute z-50"
      style={{
        left: cursorStyle.left,
        top: cursorStyle.top,
        transform: 'translateX(-1px)',
      }}
    >
      {/* 光标线 */}
      <div
        className="h-5 w-0.5 animate-pulse"
        style={{
          backgroundColor: user.color,
          animation: 'blink 1s infinite',
        }}
      />

      {/* 用户标签 */}
      <div
        className="absolute -top-6 left-0 rounded px-2 py-1 text-xs whitespace-nowrap text-white"
        style={{
          backgroundColor: user.color,
          fontSize: '11px',
          lineHeight: '1.2',
        }}
      >
        {user.name}
      </div>

      {/* 选择范围高亮 */}
      {cursor.selection && cursor.selection.start !== cursor.selection.end && (
        <div
          className="bg-opacity-30 absolute h-5"
          style={{
            backgroundColor: user.color,
            left: 0,
            top: 0,
            width: getSelectionWidth(cursor.selection, cursor.propertyPath),
          }}
        />
      )}

      <style>{`
        @keyframes blink {
          0%, 50% { opacity: 1; }
          51%, 100% { opacity: 0; }
        }
      `}</style>
    </div>
  );
};

/**
 * 多用户光标容器
 */
export interface UserCursorsProps {
  cursors: Map<string, CursorPosition>;
  users: Map<string, CollaborationUser>;
  containerRef: React.RefObject<HTMLElement>;
  currentUserId: string;
}

/**
 * 多用户光标容器组件
 */
export const UserCursors: React.FC<UserCursorsProps> = ({
  cursors,
  users,
  containerRef,
  currentUserId,
}) => {
  return (
    <>
      {Array.from(cursors.entries()).map(([userId, cursor]) => {
        // 不显示当前用户的光标
        if (userId === currentUserId) {
          return null;
        }

        const user = users.get(userId);
        if (!user || !user.isOnline) {
          return null;
        }

        return <UserCursor key={userId} cursor={cursor} user={user} containerRef={containerRef} />;
      })}
    </>
  );
};

/**
 * 用户头像组件
 */
export interface UserAvatarProps {
  user: CollaborationUser;
  size?: 'sm' | 'md' | 'lg';
  showStatus?: boolean;
  onClick?: () => void;
}

/**
 * 用户头像组件
 */
export const UserAvatar: React.FC<UserAvatarProps> = ({
  user,
  size = 'md',
  showStatus = true,
  onClick,
}) => {
  const sizeClasses = {
    sm: 'w-6 h-6 text-xs',
    md: 'w-8 h-8 text-sm',
    lg: 'w-10 h-10 text-base',
  };

  const statusSize = {
    sm: 'w-2 h-2',
    md: 'w-2.5 h-2.5',
    lg: 'w-3 h-3',
  };

  return (
    <div
      className={`relative inline-block ${onClick ? 'cursor-pointer' : ''}`}
      onClick={onClick}
      title={`${user.name} - ${user.isOnline ? '在线' : '离线'}`}
    >
      {/* 头像 */}
      <div
        className={`${sizeClasses[size]} flex items-center justify-center rounded-full font-medium text-white`}
        style={{ backgroundColor: user.color }}
      >
        {user.avatar ? (
          <img
            src={user.avatar}
            alt={user.name}
            className="h-full w-full rounded-full object-cover"
          />
        ) : (
          <span>{user.name.charAt(0).toUpperCase()}</span>
        )}
      </div>

      {/* 在线状态指示器 */}
      {showStatus && (
        <div
          className={`absolute -right-0.5 -bottom-0.5 ${statusSize[size]} rounded-full border-2 border-white ${
            user.isOnline ? 'bg-green-500' : 'bg-gray-400'
          }`}
        />
      )}
    </div>
  );
};

/**
 * 在线用户列表
 */
export interface OnlineUsersProps {
  users: CollaborationUser[];
  currentUserId: string;
  maxVisible?: number;
  onUserClick?: (user: CollaborationUser) => void;
}

/**
 * 在线用户列表组件
 */
export const OnlineUsers: React.FC<OnlineUsersProps> = ({
  users,
  currentUserId,
  maxVisible = 5,
  onUserClick,
}) => {
  const onlineUsers = users.filter((user) => user.isOnline && user.id !== currentUserId);
  const visibleUsers = onlineUsers.slice(0, maxVisible);
  const hiddenCount = onlineUsers.length - maxVisible;

  return (
    <div className="flex items-center space-x-1">
      {visibleUsers.map((user) => (
        <UserAvatar key={user.id} user={user} size="sm" onClick={() => onUserClick?.(user)} />
      ))}

      {hiddenCount > 0 && (
        <div className="flex h-6 w-6 items-center justify-center rounded-full bg-gray-200 text-xs font-medium text-gray-600">
          +{hiddenCount}
        </div>
      )}

      {onlineUsers.length === 0 && <span className="text-sm text-gray-500">只有你在编辑</span>}
    </div>
  );
};

/**
 * 协作状态指示器
 */
export interface CollaborationStatusProps {
  isConnected: boolean;
  userCount: number;
  hasConflicts: boolean;
}

/**
 * 协作状态指示器组件
 */
export const CollaborationStatus: React.FC<CollaborationStatusProps> = ({
  isConnected,
  userCount,
  hasConflicts,
}) => {
  const getStatusColor = () => {
    if (!isConnected) return 'text-red-500';
    if (hasConflicts) return 'text-yellow-500';
    return 'text-green-500';
  };

  const getStatusText = () => {
    if (!isConnected) return '连接断开';
    if (hasConflicts) return '存在冲突';
    if (userCount > 1) return `${userCount} 人在线`;
    return '已连接';
  };

  return (
    <div className="flex items-center space-x-2 text-sm">
      <div className={`h-2 w-2 rounded-full ${getStatusColor().replace('text-', 'bg-')}`} />
      <span className={getStatusColor()}>{getStatusText()}</span>
    </div>
  );
};

// 辅助函数

/**
 * 计算文本宽度
 */
function getTextWidth(text: string, style: CSSStyleDeclaration): number {
  const canvas = document.createElement('canvas');
  const context = canvas.getContext('2d');

  if (!context) return 0;

  context.font = `${style.fontSize} ${style.fontFamily}`;
  return context.measureText(text).width;
}

/**
 * 计算选择范围宽度
 */
function getSelectionWidth(
  selection: { start: number; end: number },
  propertyPath?: string,
): number {
  // 简化实现，实际应该根据具体的输入框计算
  const charWidth = 8; // 平均字符宽度
  return (selection.end - selection.start) * charWidth;
}
