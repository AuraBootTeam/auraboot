import React from 'react';
import clsx from 'clsx';

interface LayoutConfig {
  type: 'grid' | 'flex';
  columns: number;
  mode: 'fixed' | 'custom';
  customWidths?: boolean;
}

interface ComponentLayoutConfig {
  span?: number;
  width?: string;
  customWidth?: boolean;
}

interface FormLayoutContainerProps {
  layout: LayoutConfig;
  children: React.ReactNode[];
  components: Array<{ layout?: ComponentLayoutConfig }>;
}

export function FormLayoutContainer({ layout, children, components }: FormLayoutContainerProps) {
  // 固定列布局模式
  if (layout.mode === 'fixed') {
    const gridColsClass =
      {
        1: 'grid-cols-1',
        2: 'grid-cols-2',
        3: 'grid-cols-3',
        4: 'grid-cols-4',
      }[layout.columns] || 'grid-cols-2';

    return (
      <div className={clsx('grid gap-4', gridColsClass)}>
        {children.map((child, index) => {
          const componentLayout = components[index]?.layout;
          const span = componentLayout?.span || 1;

          const spanClass =
            {
              1: 'col-span-1',
              2: 'col-span-2',
              3: 'col-span-3',
              4: 'col-span-4',
            }[span] || 'col-span-1';

          return (
            <div key={index} className={clsx(spanClass)}>
              {child}
            </div>
          );
        })}
      </div>
    );
  }

  // 自定义宽度模式
  if (layout.mode === 'custom') {
    return (
      <div className="flex flex-wrap gap-4">
        {children.map((child, index) => {
          const componentLayout = components[index]?.layout;
          const width = componentLayout?.width || '100%';

          return (
            <div
              key={index}
              style={{ width: componentLayout?.customWidth ? width : undefined }}
              className={!componentLayout?.customWidth ? 'flex-1' : ''}
            >
              {child}
            </div>
          );
        })}
      </div>
    );
  }

  // 默认布局
  return <div className="space-y-4">{children}</div>;
}
