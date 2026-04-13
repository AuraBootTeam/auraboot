import React, { useMemo, useState, useEffect } from 'react';
import { cn } from '~/utils/cn';
import { useConditionalRender } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { ExpressionParser } from '~/plugins/core-designer/components/studio/services/runtime/expression/expression-parser';
import type {
  LayoutProps,
  LayoutItemConfig,
  LayoutConfig,
  GridLayoutConfig,
  FlexLayoutConfig,
  StackLayoutConfig,
  AbsoluteLayoutConfig,
} from '~/plugins/core-designer/components/studio/domain/schema/smart-components';

export const Layout: React.FC<LayoutProps> = ({
  name,
  schema,
  context,
  components = {},
  className,
  style,
  onItemClick,
  onLayoutChange,
  ...props
}) => {
  const [currentLayout, setCurrentLayout] = useState<LayoutConfig>(schema.layout);

  const shouldRender = useConditionalRender({
    condition: props.visible,
    context,
  });

  // 响应式布局处理
  const responsiveLayout = useMemo(() => {
    if (!schema.responsive || !schema.breakpoints) {
      return currentLayout;
    }

    // 简单的响应式逻辑，实际项目中可能需要更复杂的媒体查询处理
    const width = window.innerWidth;
    let breakpoint: keyof typeof schema.breakpoints = 'xl';

    if (width < 576) breakpoint = 'xs';
    else if (width < 768) breakpoint = 'sm';
    else if (width < 992) breakpoint = 'md';
    else if (width < 1200) breakpoint = 'lg';

    return schema.breakpoints[breakpoint] || currentLayout;
  }, [currentLayout, schema.responsive, schema.breakpoints]);

  // 处理布局变化
  // 渲染网格布局
  const renderGridLayout = (config: GridLayoutConfig, items: LayoutItemConfig[]) => {
    const { columns, gap = 16, responsive } = config;

    const gridStyle = {
      display: 'grid',
      gridTemplateColumns: `repeat(${columns}, 1fr)`,
      gap: `${gap}px`,
      ...style,
    };

    const responsiveClasses = responsive
      ? {
          [`grid-cols-${responsive.xs || 1}`]: true,
          [`sm:grid-cols-${responsive.sm || columns}`]: true,
          [`md:grid-cols-${responsive.md || columns}`]: true,
          [`lg:grid-cols-${responsive.lg || columns}`]: true,
          [`xl:grid-cols-${responsive.xl || columns}`]: true,
        }
      : {};

    return (
      <div
        className={cn('grid', schema.responsive && responsiveClasses, className)}
        style={gridStyle}
      >
        {items.map((item, index) => renderLayoutItem(item, index))}
      </div>
    );
  };

  // 渲染弹性布局
  const renderFlexLayout = (config: FlexLayoutConfig, items: LayoutItemConfig[]) => {
    const {
      direction = 'row',
      wrap = 'nowrap',
      justify = 'start',
      align = 'stretch',
      gap = 0,
    } = config;

    const flexClasses = {
      'flex-row': direction === 'row',
      'flex-col': direction === 'column',
      'flex-row-reverse': direction === 'row-reverse',
      'flex-col-reverse': direction === 'column-reverse',
      'flex-nowrap': wrap === 'nowrap',
      'flex-wrap': wrap === 'wrap',
      'flex-wrap-reverse': wrap === 'wrap-reverse',
      'justify-start': justify === 'start',
      'justify-end': justify === 'end',
      'justify-center': justify === 'center',
      'justify-between': justify === 'between',
      'justify-around': justify === 'around',
      'justify-evenly': justify === 'evenly',
      'items-start': align === 'start',
      'items-end': align === 'end',
      'items-center': align === 'center',
      'items-baseline': align === 'baseline',
      'items-stretch': align === 'stretch',
    };

    return (
      <div className={cn('flex', flexClasses, className)} style={{ gap: `${gap}px`, ...style }}>
        {items.map((item, index) => renderLayoutItem(item, index))}
      </div>
    );
  };

  // 渲染堆叠布局
  const renderStackLayout = (config: StackLayoutConfig, items: LayoutItemConfig[]) => {
    const { direction = 'vertical', spacing = 16, divider = false } = config;

    const stackClasses = {
      'flex-col': direction === 'vertical',
      'flex-row': direction === 'horizontal',
      'space-y-4': direction === 'vertical' && spacing > 0,
      'space-x-4': direction === 'horizontal' && spacing > 0,
      'divide-y': direction === 'vertical' && divider,
      'divide-x': direction === 'horizontal' && divider,
      'divide-gray-200': divider,
    };

    return (
      <div className={cn('flex', stackClasses, className)} style={style}>
        {items.map((item, index) => renderLayoutItem(item, index))}
      </div>
    );
  };

  // 渲染绝对定位布局
  const renderAbsoluteLayout = (config: AbsoluteLayoutConfig, items: LayoutItemConfig[]) => {
    const { width, height } = config;

    return (
      <div
        className={cn('relative', className)}
        style={{
          width: typeof width === 'number' ? `${width}px` : width,
          height: typeof height === 'number' ? `${height}px` : height,
          ...style,
        }}
      >
        {items.map((item, index) => renderLayoutItem(item, index, true))}
      </div>
    );
  };

  // 渲染布局项
  const renderLayoutItem = (item: LayoutItemConfig, index: number, absolute = false) => {
    // 条件可见性检查
    const isVisible = item.visible
      ? ExpressionParser.evaluate(item.visible, { ...context, $item: item, $index: index })
      : true;

    if (!isVisible) return null;

    // 构建项目样式
    const itemStyle = {
      ...item.style,
      ...(item.span && !absolute ? { gridColumn: `span ${item.span}` } : {}),
      ...(item.offset && !absolute ? { gridColumnStart: item.offset + 1 } : {}),
      ...(item.order ? { order: item.order } : {}),
      ...(item.flex ? { flex: item.flex } : {}),
      ...(absolute ? { position: 'absolute' as const } : {}),
    };

    // 处理点击事件
    const handleItemClick = () => {
      onItemClick?.(item, index);
    };

    // 渲染组件内容
    const renderItemContent = () => {
      if (item.component && components[item.component]) {
        const Component = components[item.component];
        return <Component {...item.props} />;
      }

      if (item.children && item.children.length > 0) {
        // 递归渲染子布局
        return (
          <Layout
            name={`${name}-${item.key}`}
            schema={{
              layout: schema.layout,
              items: item.children,
            }}
            context={context}
            components={components}
            onItemClick={onItemClick}
            onLayoutChange={onLayoutChange}
          />
        );
      }

      // 默认渲染文本内容
      return (
        <div className="rounded border border-gray-200 bg-gray-100 p-4">
          <span className="text-sm text-gray-600">Layout Item: {item.key}</span>
        </div>
      );
    };

    return (
      <div
        key={item.key}
        className={cn(
          'layout-item',
          item.className,
          onItemClick && 'cursor-pointer hover:bg-gray-50',
        )}
        style={itemStyle}
        onClick={handleItemClick}
      >
        {renderItemContent()}
      </div>
    );
  };

  // 渲染主布局
  const renderLayout = () => {
    const layout = responsiveLayout;
    const items = schema.items;

    switch (layout.type) {
      case 'grid':
        return renderGridLayout(layout, items);
      case 'flex':
        return renderFlexLayout(layout, items);
      case 'stack':
        return renderStackLayout(layout, items);
      case 'absolute':
        return renderAbsoluteLayout(layout, items);
      default:
        return renderFlexLayout({ type: 'flex' }, items);
    }
  };

  // 响应式监听
  useEffect(() => {
    if (!schema.responsive) return;

    const handleResize = () => {
      // 触发重新计算响应式布局
      setCurrentLayout({ ...currentLayout });
    };

    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [schema.responsive, currentLayout]);

  if (!shouldRender) return null;

  // 构建容器样式
  const containerStyle = {
    padding: typeof schema.padding === 'number' ? `${schema.padding}px` : schema.padding,
    margin: typeof schema.margin === 'number' ? `${schema.margin}px` : schema.margin,
    backgroundColor: schema.background,
    borderRadius: schema.borderRadius ? `${schema.borderRadius}px` : undefined,
    ...style,
  };

  const containerClasses = {
    'border border-gray-200': schema.border,
    'shadow-md': schema.shadow,
  };

  return (
    <div
      className={cn('smart-layout', containerClasses, schema.className, className)}
      style={containerStyle}
      {...props}
    >
      {renderLayout()}
    </div>
  );
};

export default Layout;
