/**
 * Organization Service
 *
 * API service for department tree data.
 */

import { get } from '~/shared/services/http-client';
import type { Result } from '~/utils/type';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface DepartmentTreeNode {
  pid: string;
  name: string;
  parentPid: string | null;
  sortOrder: number;
  employeeCount: number;
  children: DepartmentTreeNode[];
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

export const organizationService = {
  getDepartmentTree(): Promise<Result<DepartmentTreeNode[]>> {
    return get<DepartmentTreeNode[]>('/api/org/departments/tree');
  },
};
