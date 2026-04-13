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

// ---------------------------------------------------------------------------
// Permission Matrix DTOs
// ---------------------------------------------------------------------------

export interface PermissionMatrixActionDTO {
  permissionId: number;
  permissionPid: string;
  code: string;
  action: string;
  label: string;
  granted: boolean;
  supported: boolean;
  scopeType?: string;       // 'all' | 'self' | 'dept' | 'dept_and_sub' | 'none' | null
  mergeStrategy?: string;   // 'MAX' | 'MIN' | null
  policySchema?: string;    // JSON string of policy schema definition, null if no policy
  policyValues?: Record<string, any>; // current policy values for this role
}

export interface PermissionMatrixResourceDTO {
  resourceCode: string;
  resourceName: string;
  actions: PermissionMatrixActionDTO[];
}

export interface PermissionMatrixModuleDTO {
  moduleCode: string;
  moduleName: string;
  resources: PermissionMatrixResourceDTO[];
}

export interface PermissionMatrixDTO {
  modules: PermissionMatrixModuleDTO[];
}

export interface PermissionGrantRequest {
  permissionId: number;
  granted: boolean;
}

// ---------------------------------------------------------------------------
// Role Member DTOs
// ---------------------------------------------------------------------------

export interface RoleMemberDTO {
  memberId: number;
  memberPid: string;
  userName: string;
  email: string;
  departmentName: string;
  positionName: string;
  assignedAt: string;
}

export interface PaginationResult<T> {
  records: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}
