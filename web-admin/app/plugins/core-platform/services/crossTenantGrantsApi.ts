/**
 * Cross-tenant grants admin API client.
 *
 * Wraps the C.2 REST endpoints exposed by CrossTenantGrantController
 * (gated by AdminRoleInterceptor + per-handler platform_admin guard):
 *   GET    /api/admin/cross-tenant-grants
 *   POST   /api/admin/cross-tenant-grants
 *   DELETE /api/admin/cross-tenant-grants/{id}
 *   GET    /api/admin/cross-tenant-grants/{id}/audit
 *
 * DTO shapes mirror ab_cross_tenant_grant + ab_cross_tenant_spawn_audit
 * column-for-column (snake_case JSON keys are emitted by the JdbcTemplate
 * row-map serialiser used in the controller; we keep them as-is rather
 * than camel-casing on the wire).
 */

import { get, post, del } from '~/shared/services/http-client';
import type { Result } from '~/shared/services/http-client';

export interface CrossTenantGrantRecord {
  id: number;
  parent_tenant_id: number;
  child_tenant_id: number;
  grant_type: string;
  granted_by: number;
  granted_at: string;
  expires_at: string | null;
  revoked_at: string | null;
  revoked_by: number | null;
  note: string | null;
}

export interface CrossTenantGrantPage {
  records: CrossTenantGrantRecord[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface CrossTenantSpawnAuditRecord {
  id: number;
  grant_id: number | null;
  parent_tenant_id: number;
  child_tenant_id: number;
  parent_run_pid: string;
  child_run_pid: string | null;
  decision: string;
  spawn_at: string;
  error_message: string | null;
}

export interface CrossTenantSpawnAuditPage {
  records: CrossTenantSpawnAuditRecord[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface CreateGrantRequest {
  parentTenantId: number;
  childTenantId: number;
  grantType?: string;
  expiresAt?: string;
  note?: string;
}

export function listGrants(
  pageNum: number,
  pageSize: number,
  activeOnly: boolean,
): Promise<Result<CrossTenantGrantPage>> {
  return get<CrossTenantGrantPage>('/api/admin/cross-tenant-grants', {
    pageNum,
    pageSize,
    activeOnly,
  });
}

export function createGrant(
  body: CreateGrantRequest,
): Promise<Result<{ id: number }>> {
  return post<{ id: number }>('/api/admin/cross-tenant-grants', body);
}

export function revokeGrant(id: number): Promise<Result<{ id: number }>> {
  return del<{ id: number }>(`/api/admin/cross-tenant-grants/${id}`);
}

export function listAudit(
  id: number,
  pageNum: number,
  pageSize: number,
): Promise<Result<CrossTenantSpawnAuditPage>> {
  return get<CrossTenantSpawnAuditPage>(
    `/api/admin/cross-tenant-grants/${id}/audit`,
    { pageNum, pageSize },
  );
}
