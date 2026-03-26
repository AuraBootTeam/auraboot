export interface Permission {
  id: number;
  pid: string;
  code: string;
  name: string;
  description: string;
  module: string;
  type: string;
  resource: string;
  action: string;
  parentId?: number;
  path: string;
  sortOrder: number;
  status: string;
  isSystem: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Role {
  id: number;
  pid: string;
  code: string;
  name: string;
  description: string;
  type: string;
  status: string;
  isSystem: boolean;
  tenantId: number;
  createdAt: string;
  updatedAt: string;
}

export interface PermissionTreeNode {
  id: string | number;
  pid: string;
  code: string;
  name: string;
  type: string;
  module?: string;
  status?: string;
  children?: PermissionTreeNode[];
}
