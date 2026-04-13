import React, { useState, useMemo } from 'react';
import {
  ChevronDown,
  ChevronRight,
  Home,
  Menu,
  X,
  Plus,
  MoreHorizontal,
  ChevronLeft,
  ChevronUp,
} from 'lucide-react';
import { cn } from '~/utils/cn';
import {
  useSmartComponentState,
  useValidation,
  useConditionalRender,
} from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { ExpressionParser } from '~/plugins/core-designer/components/studio/services/runtime/expression/expression-parser';
import type {
  NavigationProps,
  NavigationItem,
  MenuConfig,
  BreadcrumbConfig,
  TabConfig,
} from '~/plugins/core-designer/components/studio/domain/schema/smart-components';

export const Navigation: React.FC<NavigationProps> = ({
  name,
  schema,
  context,
  className,
  style,
  onSelect,
  onOpenChange,
  onTabEdit,
  ...props
}) => {
  const [activeKey, setActiveKey] = useState<string>(
    schema.activeKey || schema.defaultActiveKey || '',
  );
  const [openKeys, setOpenKeys] = useState<string[]>(
    schema.openKeys || schema.defaultOpenKeys || [],
  );
  const [collapsed, setCollapsed] = useState<boolean>(false);

  const { value: _value, handleChange } = useSmartComponentState({
    name: name ?? 'smart-navigation',
    value: activeKey,
  });

  const { errors: _errors, isValid: _isValid } = useValidation({
    value: activeKey,
    rules: [],
    context,
  });

  const shouldRender = useConditionalRender(props.visible, context);

  // 过滤可见的导航项
  const visibleItems = useMemo(() => {
    const filterItems = (items: NavigationItem[]): NavigationItem[] => {
      return items.filter((item) => {
        const isVisible = item.visible
          ? ExpressionParser.evaluate(item.visible, { ...context, $item: item })
          : true;

        if (!isVisible) return false;

        // 递归过滤子项
        if (item.children) {
          item.children = filterItems(item.children);
        }

        return true;
      });
    };

    return filterItems(schema.items);
  }, [schema.items, context]);

  // 处理项目选择
  const handleSelect = (key: string, item: NavigationItem) => {
    setActiveKey(key);
    handleChange(key);
    onSelect?.(key, item);

    // 执行点击表达式
    if (item.onClick) {
      try {
        ExpressionParser.evaluate(item.onClick, {
          ...context,
          $item: item,
          $key: key,
        });
      } catch (error) {
        console.error('Navigation onClick expression error:', error);
      }
    }
  };

  // 处理展开/收起
  const handleOpenChange = (keys: string[]) => {
    setOpenKeys(keys);
    onOpenChange?.(keys);
  };

  // 切换子菜单展开状态
  const toggleSubmenu = (key: string) => {
    const newOpenKeys = openKeys.includes(key)
      ? openKeys.filter((k) => k !== key)
      : [...openKeys, key];
    handleOpenChange(newOpenKeys);
  };

  // 渲染菜单图标
  const renderIcon = (iconName?: string) => {
    if (!iconName) return null;

    const iconMap: Record<string, React.ComponentType<any>> = {
      home: Home,
      menu: Menu,
      close: X,
      plus: Plus,
      more: MoreHorizontal,
      'chevron-down': ChevronDown,
      'chevron-right': ChevronRight,
      'chevron-left': ChevronLeft,
      'chevron-up': ChevronUp,
    };

    const IconComponent = iconMap[iconName.toLowerCase()];
    return IconComponent ? <IconComponent className="h-4 w-4" /> : null;
  };

  // 渲染徽章
  const renderBadge = (badge?: NavigationItem['badge']) => {
    if (!badge) return null;

    const count =
      typeof badge.count === 'string'
        ? ExpressionParser.evaluate(badge.count, context)
        : badge.count;

    if (badge.dot) {
      return (
        <span
          className={cn(
            'h-2 w-2 rounded-full',
            badge.color ? `bg-${badge.color}-500` : 'bg-red-500',
          )}
        />
      );
    }

    if (count && count > 0) {
      return (
        <span
          className={cn(
            'min-w-[16px] rounded-full px-1.5 py-0.5 text-center text-xs text-white',
            badge.color ? `bg-${badge.color}-500` : 'bg-red-500',
          )}
        >
          {count > 99 ? '99+' : count}
        </span>
      );
    }

    return null;
  };

  // 渲染菜单项
  const renderMenuItem = (item: NavigationItem, level = 0) => {
    const isActive = activeKey === item.key;
    const isOpen = openKeys.includes(item.key);
    const hasChildren = item.children && item.children.length > 0;
    const isDisabled = item.disabled
      ? ExpressionParser.evaluate(item.disabled, { ...context, $item: item })
      : false;

    const menuConfig = schema.config as MenuConfig;
    const isInline = menuConfig?.mode === 'inline';
    const isVertical = menuConfig?.mode === 'vertical' || isInline;

    const itemClasses = cn(
      'flex items-center gap-2 px-3 py-2 rounded-md transition-colors cursor-pointer',
      {
        'bg-blue-100 text-blue-700': isActive && !isDisabled,
        'text-gray-400 cursor-not-allowed': isDisabled,
        'hover:bg-gray-100': !isActive && !isDisabled,
        'text-white hover:bg-gray-700': menuConfig?.theme === 'dark' && !isActive && !isDisabled,
        'bg-gray-800 text-white': menuConfig?.theme === 'dark' && isActive && !isDisabled,
      },
      level > 0 && isVertical && `ml-${level * (menuConfig?.inlineIndent || 24)}px`,
    );

    return (
      <div key={item.key}>
        <div
          className={itemClasses}
          onClick={() => {
            if (isDisabled) return;

            if (hasChildren) {
              toggleSubmenu(item.key);
            } else {
              handleSelect(item.key, item);
            }
          }}
        >
          {renderIcon(item.icon)}
          {(!collapsed || level > 0) && (
            <>
              <span className="flex-1">{item.label}</span>
              {renderBadge(item.badge)}
              {hasChildren && (
                <span className="ml-auto">
                  {isOpen ? (
                    <ChevronDown className="h-4 w-4" />
                  ) : (
                    <ChevronRight className="h-4 w-4" />
                  )}
                </span>
              )}
            </>
          )}
        </div>

        {/* 子菜单 */}
        {hasChildren && (isOpen || !isVertical) && (
          <div className={cn('ml-4', isVertical && 'mt-1 border-l border-gray-200 pl-4')}>
            {item.children!.map((child) => renderMenuItem(child, level + 1))}
          </div>
        )}
      </div>
    );
  };

  // 渲染菜单
  const renderMenu = () => {
    const menuConfig = schema.config as MenuConfig;
    const isHorizontal = menuConfig?.mode === 'horizontal';
    const isDark = menuConfig?.theme === 'dark';

    const menuClasses = cn(
      'navigation-menu',
      {
        'flex flex-row space-x-1': isHorizontal,
        'flex flex-col space-y-1': !isHorizontal,
        'bg-gray-800 text-white': isDark,
        'bg-white border border-gray-200': !isDark,
      },
      className,
    );

    const menuStyle = {
      width: !isHorizontal && menuConfig?.width ? `${menuConfig.width}px` : undefined,
      ...style,
    };

    return (
      <nav className={menuClasses} style={menuStyle}>
        {menuConfig?.collapsible && (
          <div className="flex items-center justify-between border-b border-gray-200 p-2">
            <span className={cn('font-semibold', collapsed && 'hidden')}>导航菜单</span>
            <button
              onClick={() => setCollapsed(!collapsed)}
              className="rounded p-1 hover:bg-gray-100"
            >
              <Menu className="h-4 w-4" />
            </button>
          </div>
        )}

        <div className="p-2">{visibleItems.map((item) => renderMenuItem(item))}</div>
      </nav>
    );
  };

  // 渲染面包屑
  const renderBreadcrumb = () => {
    const breadcrumbConfig = schema.config as BreadcrumbConfig;
    const separator = breadcrumbConfig?.separator || '/';
    const maxItems = breadcrumbConfig?.maxItems || 0;
    const showHome = breadcrumbConfig?.showHome !== false;

    let items = [...visibleItems];

    // 添加首页
    if (showHome) {
      items.unshift({
        key: 'home',
        label: breadcrumbConfig?.homeText || '首页',
        path: breadcrumbConfig?.homePath || '/',
      });
    }

    // 限制显示数量
    if (maxItems > 0 && items.length > maxItems) {
      const start = items.slice(0, 1);
      const end = items.slice(-maxItems + 1);
      items = [...start, { key: 'ellipsis', label: '...', path: '' }, ...end];
    }

    return (
      <nav className={cn('breadcrumb flex items-center space-x-2', className)} style={style}>
        {items.map((item, index) => (
          <React.Fragment key={item.key}>
            {index > 0 && <span className="text-gray-400 select-none">{separator}</span>}
            <span
              className={cn('breadcrumb-item', {
                'cursor-pointer text-blue-600 hover:text-blue-800':
                  item.path && item.key !== 'ellipsis',
                'text-gray-500': !item.path || item.key === 'ellipsis',
                'font-medium text-gray-900': index === items.length - 1,
              })}
              onClick={() => {
                if (item.path && item.key !== 'ellipsis') {
                  handleSelect(item.key, item);
                }
              }}
            >
              {renderIcon(item.icon)}
              {item.label}
            </span>
          </React.Fragment>
        ))}
      </nav>
    );
  };

  // 渲染标签页
  const renderTabs = () => {
    const tabConfig = schema.config as TabConfig;
    const isVertical = tabConfig?.position === 'left' || tabConfig?.position === 'right';
    const isBottom = tabConfig?.position === 'bottom';

    const tabsClasses = cn(
      'tabs',
      {
        'flex flex-col': isVertical,
        'flex flex-row': !isVertical,
        'border-b border-gray-200': !isBottom && !isVertical,
        'border-t border-gray-200': isBottom,
        'border-r border-gray-200': tabConfig?.position === 'left',
        'border-l border-gray-200': tabConfig?.position === 'right',
      },
      className,
    );

    const tabClasses = (item: NavigationItem) =>
      cn('tab-item flex items-center gap-2 px-4 py-2 border-b-2 transition-colors cursor-pointer', {
        'border-blue-500 text-blue-600': activeKey === item.key,
        'border-transparent text-gray-600 hover:text-gray-900': activeKey !== item.key,
        'text-sm': tabConfig?.size === 'small',
        'text-lg': tabConfig?.size === 'large',
        'bg-white border border-gray-200 rounded-t': tabConfig?.type === 'card',
        'bg-gray-100': tabConfig?.type === 'card' && activeKey !== item.key,
      });

    return (
      <div className={tabsClasses} style={style}>
        <div className="tab-list flex">
          {visibleItems.map((item) => (
            <div
              key={item.key}
              className={tabClasses(item)}
              onClick={() => handleSelect(item.key, item)}
            >
              {renderIcon(item.icon)}
              <span>{item.label}</span>
              {renderBadge(item.badge)}

              {tabConfig?.closable && (
                <button
                  className="ml-2 rounded p-0.5 hover:bg-gray-200"
                  onClick={(e) => {
                    e.stopPropagation();
                    onTabEdit?.(item.key, 'remove');
                  }}
                >
                  <X className="h-3 w-3" />
                </button>
              )}
            </div>
          ))}

          {tabConfig?.addable && (
            <button
              className="tab-add flex items-center justify-center border-b-2 border-transparent px-3 py-2 text-gray-400 hover:text-gray-600"
              onClick={() => onTabEdit?.('', 'add')}
            >
              <Plus className="h-4 w-4" />
            </button>
          )}
        </div>
      </div>
    );
  };

  if (!shouldRender) return null;

  // 根据类型渲染不同的导航组件
  switch (schema.type) {
    case 'menu':
      return renderMenu();
    case 'breadcrumb':
      return renderBreadcrumb();
    case 'tabs':
      return renderTabs();
    default:
      return renderMenu();
  }
};

export default Navigation;
