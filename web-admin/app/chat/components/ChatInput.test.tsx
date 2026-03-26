/**
 * ChatInput 测试组件 - 用于调试
 */

import { useState } from 'react';

interface ChatInputProps {
  onSend: (message: string) => void;
  disabled?: boolean;
  placeholder?: string;
}

export function ChatInputDebug({ onSend, disabled, placeholder = '输入消息...' }: ChatInputProps) {
  const [message, setMessage] = useState('');

  console.log('ChatInputDebug render:', { message, disabled, hasOnSend: !!onSend });

  const handleSend = () => {
    console.log('handleSend called:', { message, trimmed: message.trim(), disabled });
    const trimmedMessage = message.trim();
    if (trimmedMessage && !disabled) {
      console.log('Calling onSend with:', trimmedMessage);
      onSend(trimmedMessage);
      setMessage('');
    } else {
      console.log('Not sending:', { hasContent: !!trimmedMessage, disabled });
    }
  };

  const handleKeyDown = (e: any) => {
    console.log('Key pressed:', e.key, { shift: e.shiftKey, ctrl: e.ctrlKey, meta: e.metaKey });

    if (e.key === 'Enter' && !e.shiftKey) {
      console.log('Enter pressed, preventing default and sending');
      e.preventDefault();
      handleSend();
    }
  };

  const handleChange = (e: any) => {
    const newValue = e.target.value;
    console.log('Input changed:', newValue);
    setMessage(newValue);
  };

  return (
    <div className="border-t bg-white p-4">
      <div className="flex items-end space-x-2">
        <textarea
          value={message}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          placeholder={placeholder}
          disabled={disabled}
          rows={1}
          className="flex-1 resize-none rounded-lg border border-gray-300 px-4 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:bg-gray-100"
        />
        <button
          onClick={handleSend}
          disabled={disabled || !message.trim()}
          className="rounded-lg bg-blue-500 px-6 py-2 text-white transition-colors hover:bg-blue-600 disabled:cursor-not-allowed disabled:bg-gray-300"
        >
          发送 ({message.length})
        </button>
      </div>
      <div className="mt-2 text-xs text-gray-500">按 Enter 发送，Shift + Enter 换行</div>
      <div className="mt-2 text-xs text-red-500">
        Debug: message="{message}", disabled={disabled ? 'true' : 'false'}, canSend=
        {!disabled && !!message.trim() ? 'true' : 'false'}
      </div>
    </div>
  );
}
