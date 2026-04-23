import { ResultHelper } from '~/utils/type';
import { getTokenFromRequest } from '~/shared/services/session';

export interface MenuItem {
  id: number;
  tenantId?: number;
  pid?: string;
  createdAt?: string;
  updatedAt?: string;
  parentId?: number | null;
  code?: string;
  name: string;
  path: string;
  component?: string | null;
  icon?: string;
  type: number; // 0=目录, 1=菜单, 2=按钮
  permissionCode?: string;
  visible: boolean;
  orderNo: number;
  i18nKey?: string | null;
  redirect?: string | null;
  extension?: any;
  status?: string;
  deletedFlag?: boolean;
  createdBy?: number;
  updatedBy?: number;
  children?: MenuItem[] | null;
  permission?: any;
}

export interface ButtonPermission {
  code: string;
  name: string;
}

// 菜单处理函数：将后端数据转换为前端需要的格式
export function processMenuData(menuData: MenuItem[]): MenuItem[] {
  if (!menuData || !Array.isArray(menuData)) {
    return [];
  }

  return menuData
    .filter((item) => item.visible && item.status === 'active' && !item.deletedFlag)
    .sort((a, b) => a.orderNo - b.orderNo)
    .map((item) => ({
      ...item,
      children: item.children ? processMenuData(item.children) : null,
    }));
}

// 获取用户菜单
// 将后端菜单数据转换为前端组件期望的格式
export function transformMenuForUI(menuData: MenuItem[]): any[] {
  return menuData.map((item) => ({
    id: item.pid, //use pid ,long id is truncated
    name: item.name,
    nameKey: item.i18nKey || (item.code ? `menu.${item.code}` : undefined),
    path: item.path,
    icon: item.icon,
    type: item.type,
    permissionCode: item.permissionCode,
    visible: item.visible,
    orderNo: item.orderNo,
    // 将 children 转换为 submenu todo 不要转换
    submenu:
      item.children && item.children.length > 0 ? transformMenuForUI(item.children) : undefined,
  }));
}

// 更新 getUserMenus 函数
export async function getUserMenus(request: Request): Promise<any[]> {
  const token = await getTokenFromRequest(request);

  if (!token) {
    return getDefaultUIMenus();
  }

  try {
    const apiUrl = process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';
    const url = `${apiUrl}/api/menu/user`;

    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
    });

    if (!response.ok) {
      console.error('获取用户菜单失败:', response.statusText);
      return getDefaultUIMenus();
    }

    const result = await response.json();

    if (ResultHelper.isSuccess(result)) {
      const processedMenus = processMenuData(result.data || []);
      // 转换为前端组件期望的格式
      return transformMenuForUI(processedMenus);
    } else {
      console.error('获取用户菜单失败:', result.desc);
      return getDefaultUIMenus();
    }
  } catch (error) {
    console.error('获取用户菜单时发生错误:', error);
    return getDefaultUIMenus();
  }
}

// 更新默认菜单格式
function getDefaultUIMenus(): any[] {
  return [
    {
      id: 1,
      name: '节目制作',
      path: '/content',
      icon: 'content-icon',
      submenu: [
        {
          id: 2,
          name: 'AI生图',
          path: '/content/ai-image',
          icon: 'ai-icon',
        },
        {
          id: 3,
          name: 'Web编辑器',
          path: '/content/editor',
          icon: 'editor-icon',
        },
      ],
    },
    {
      id: 4,
      name: '费用中心',
      path: '/billing',
      icon: 'billing-icon',
      submenu: [
        {
          id: 5,
          name: '我的产品',
          path: '/billing/products',
          icon: 'product-icon',
        },
        {
          id: 6,
          name: '订单列表',
          path: '/billing/orders',
          icon: 'order-icon',
        },
      ],
    },
  ];
}

// 检查菜单项是否为目录（有子菜单且type为0）
export function isMenuDirectory(menuItem: MenuItem): boolean {
  return menuItem.type === 0 && !!menuItem.children && menuItem.children.length > 0;
}

// 检查菜单项是否为可点击的菜单（type为1且有路径）
export function isClickableMenu(menuItem: MenuItem): boolean {
  return menuItem.type === 1 && !!menuItem.path;
}

// 获取菜单的完整路径（用于路由跳转）
export function getMenuFullPath(menuItem: MenuItem): string {
  return menuItem.path || '#';
}

// 根据权限代码查找菜单项
export function findMenuByPermissionCode(
  menus: MenuItem[],
  permissionCode: string,
): MenuItem | null {
  for (const menu of menus) {
    if (menu.permissionCode === permissionCode) {
      return menu;
    }
    if (menu.children) {
      const found = findMenuByPermissionCode(menu.children, permissionCode);
      if (found) {
        return found;
      }
    }
  }
  return null;
}

// 获取所有可点击的菜单项（扁平化）
export function getFlatMenuItems(menus: MenuItem[]): MenuItem[] {
  const flatItems: MenuItem[] = [];

  function traverse(items: MenuItem[]) {
    for (const item of items) {
      if (isClickableMenu(item)) {
        flatItems.push(item);
      }
      if (item.children) {
        traverse(item.children);
      }
    }
  }

  traverse(menus);
  return flatItems;
}

// 默认菜单配置（作为后备方案）
function _getDefaultMenus(): MenuItem[] {
  return [
    {
      id: 1,
      name: '节目制作',
      path: '/content',
      icon: 'content-icon',
      type: 0,
      permissionCode: 'content_creation',
      visible: true,
      orderNo: 1,
      parentId: null,
      children: [
        {
          id: 2,
          name: 'AI生图',
          path: '/content/ai-image',
          component: 'AiImagePage',
          icon: 'ai-icon',
          type: 1,
          permissionCode: 'ai_image_generation',
          visible: true,
          orderNo: 1,
          parentId: 1,
          children: null,
        },
        {
          id: 3,
          name: 'Web编辑器',
          path: '/content/editor',
          component: 'WebEditorPage',
          icon: 'editor-icon',
          type: 1,
          permissionCode: 'web_editor',
          visible: true,
          orderNo: 2,
          parentId: 1,
          children: null,
        },
      ],
    },
    {
      id: 4,
      name: '费用中心',
      path: '/billing',
      icon: 'billing-icon',
      type: 0,
      permissionCode: 'billing_center',
      visible: true,
      orderNo: 3,
      parentId: null,
      children: [
        {
          id: 5,
          name: '我的产品',
          path: '/billing/products',
          component: 'ProductsPage',
          icon: 'product-icon',
          type: 1,
          permissionCode: 'my_products',
          visible: true,
          orderNo: 1,
          parentId: 4,
          children: null,
        },
        {
          id: 6,
          name: '订单列表',
          path: '/billing/orders',
          component: 'OrdersPage',
          icon: 'order-icon',
          type: 1,
          permissionCode: 'order_list',
          visible: true,
          orderNo: 2,
          parentId: 4,
          children: null,
        },
      ],
    },
  ];
}
