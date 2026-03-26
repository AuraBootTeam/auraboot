import React, { useRef } from 'react';
import HorizontalDndLayout from '~/routes/pages/HorizontalDndLayout';

const DndDemo = () => {
  // 创建一个引用，用于调用 HorizontalDndLayout 的方法
  const layoutRef = useRef(null);

  // 不再需要初始组件
  const initialComponents: any[] = [];

  // 侧边栏组件列表
  const sidebarComponents = [
    { id: 'sidebar-1', content: '图表组件' },
    { id: 'sidebar-2', content: '表格组件' },
    { id: 'sidebar-3', content: '文本组件' },
    { id: 'sidebar-4', content: '按钮组件' },
  ];

  return (
    <div style={{ padding: '20px', display: 'flex' }}>
      {/* 侧边栏 */}
      <div
        style={{
          width: '200px',
          minHeight: '500px',
          backgroundColor: '#f0f0f0',
          borderRadius: '4px',
          padding: '16px',
          marginRight: '20px',
        }}
      >
        <h3>组件库</h3>
        <p>拖动下面的组件到右侧区域</p>

        <div style={{ marginTop: '16px' }}>
          {sidebarComponents.map((component) => (
            <div
              key={component.id}
              draggable
              onDragStart={(e) => {
                e.dataTransfer.setData('text/plain', component.content);
              }}
              style={{
                padding: '12px',
                margin: '8px 0',
                backgroundColor: 'white',
                border: '1px solid #ddd',
                borderRadius: '4px',
                cursor: 'grab',
                textAlign: 'center',
              }}
            >
              {component.content}
            </div>
          ))}
        </div>
      </div>

      {/* 主区域 */}
      <div style={{ flex: 1 }}>
        <h1>拖放布局演示</h1>
        <p>从左侧拖动组件到此区域，然后可以进行水平或垂直拖放排列</p>
        <HorizontalDndLayout ref={layoutRef} initialComponents={initialComponents} />
      </div>
    </div>
  );
};

export default DndDemo;
