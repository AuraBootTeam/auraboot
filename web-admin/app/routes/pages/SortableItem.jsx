import React, { useState } from 'react';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

export function SortableItem({ id, width, children, onDelete }) {
  const [isHovered, setIsHovered] = useState(false);

  const { attributes, listeners, setNodeRef, transform, transition } = useSortable({ id });

  const style = {
    width: `calc(${width} - 8px)`,
    height: '100px',
    transform: CSS.Transform.toString(transform),
    transition,
    backgroundColor: '#f9f9f9',
    border: '1px solid #ddd',
    borderRadius: '4px',
    padding: '16px',
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    userSelect: 'none',
    cursor: 'grab',
    boxSizing: 'border-box',
    margin: '4px',
    position: 'relative',
  };

  // 删除按钮点击处理函数
  const handleDelete = (e) => {
    // 阻止事件冒泡，防止触发拖拽事件
    e.stopPropagation();
    // 阻止默认行为
    e.preventDefault();
    // 调用删除函数
    console.log('删除按钮被点击，组件ID:', id);
    onDelete(id);
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      {/* 内容区域，应用拖拽监听器 */}
      <div
        {...attributes}
        {...listeners}
        style={{
          width: '100%',
          height: '100%',
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
        }}
      >
        {children}
      </div>

      {/* 删除按钮，不应用拖拽监听器 */}
      {isHovered && (
        <div
          onClick={handleDelete}
          style={{
            position: 'absolute',
            top: '5px',
            right: '5px',
            backgroundColor: '#ff4d4f',
            color: 'white',
            border: 'none',
            borderRadius: '50%',
            width: '24px',
            height: '24px',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
            cursor: 'pointer',
            fontSize: '14px',
            zIndex: 100,
            pointerEvents: 'auto', // 确保点击事件能被捕获
          }}
        >
          ×
        </div>
      )}
    </div>
  );
}
