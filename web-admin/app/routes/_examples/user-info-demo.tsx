/**
 * 用户信息和权限使用示例
 */

import { useAuth, useUser, usePermissions } from '~/contexts/AuthContext';
import { PermissionGuard, PermissionButton } from '~/components/PermissionGuard';

export default function UserInfoDemo() {
  // 方式1：获取所有信息
  const { user, permissions, isAuthenticated, token } = useAuth();

  // 方式2：只获取用户信息
  const { user: user2 } = useUser();

  // 方式3：只获取权限信息
  const { hasPermission, hasRole, hasAnyPermission, hasAllPermissions } = usePermissions();

  if (!isAuthenticated) {
    return <div>请先登录</div>;
  }

  return (
    <div className="container mx-auto p-8">
      <h1 className="mb-6 text-2xl font-bold">用户信息和权限示例</h1>

      {/* 用户基本信息 */}
      <section className="mb-8 rounded-lg bg-white p-6 shadow">
        <h2 className="mb-4 text-xl font-semibold">用户基本信息</h2>
        <div className="space-y-2">
          <p>
            <strong>用户ID:</strong> {user?.id}
          </p>
          <p>
            <strong>用户PID:</strong> {user?.pid}
          </p>
          <p>
            <strong>用户名:</strong> {user?.name}
          </p>
          <p>
            <strong>邮箱:</strong> {user?.email}
          </p>
          <p>
            <strong>租户ID:</strong> {user?.tenantId}
          </p>
          <p>
            <strong>Token过期时间:</strong>{' '}
            {user?.exp ? new Date(user.exp * 1000).toLocaleString() : '未知'}
          </p>
        </div>
      </section>

      {/* 权限信息 */}
      <section className="mb-8 rounded-lg bg-white p-6 shadow">
        <h2 className="mb-4 text-xl font-semibold">权限信息</h2>

        <div className="mb-4">
          <h3 className="mb-2 font-semibold">角色列表:</h3>
          <div className="flex flex-wrap gap-2">
            {(permissions?.roles ?? []).map((role) => (
              <span
                key={role.id}
                className="rounded-full bg-blue-100 px-3 py-1 text-sm text-blue-800"
              >
                {role.name} ({role.code})
              </span>
            ))}
          </div>
        </div>

        <div>
          <h3 className="mb-2 font-semibold">权限列表:</h3>
          <div className="grid grid-cols-2 gap-2">
            {(permissions?.permissions ?? []).map((permission) => (
              <div key={permission.id} className="rounded bg-gray-100 px-3 py-2 text-sm">
                <div className="font-medium">{permission.name}</div>
                <div className="text-xs text-gray-600">{permission.code}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* 权限检查示例 */}
      <section className="mb-8 rounded-lg bg-white p-6 shadow">
        <h2 className="mb-4 text-xl font-semibold">权限检查示例</h2>

        <div className="space-y-4">
          <div>
            <h3 className="mb-2 font-semibold">单个权限检查:</h3>
            <p>
              hasPermission('user:create'):{' '}
              <span className={hasPermission('user:create') ? 'text-green-600' : 'text-red-600'}>
                {hasPermission('user:create') ? '✓ 有权限' : '✗ 无权限'}
              </span>
            </p>
          </div>

          <div>
            <h3 className="mb-2 font-semibold">角色检查:</h3>
            <p>
              hasRole('admin'):{' '}
              <span className={hasRole('admin') ? 'text-green-600' : 'text-red-600'}>
                {hasRole('admin') ? '✓ 是管理员' : '✗ 不是管理员'}
              </span>
            </p>
          </div>

          <div>
            <h3 className="mb-2 font-semibold">任一权限检查:</h3>
            <p>
              hasAnyPermission(['user:create', 'user:edit']):{' '}
              <span
                className={
                  hasAnyPermission(['user:create', 'user:edit']) ? 'text-green-600' : 'text-red-600'
                }
              >
                {hasAnyPermission(['user:create', 'user:edit']) ? '✓ 有权限' : '✗ 无权限'}
              </span>
            </p>
          </div>

          <div>
            <h3 className="mb-2 font-semibold">所有权限检查:</h3>
            <p>
              hasAllPermissions(['user:create', 'user:edit']):{' '}
              <span
                className={
                  hasAllPermissions(['user:create', 'user:edit'])
                    ? 'text-green-600'
                    : 'text-red-600'
                }
              >
                {hasAllPermissions(['user:create', 'user:edit']) ? '✓ 有权限' : '✗ 无权限'}
              </span>
            </p>
          </div>
        </div>
      </section>

      {/* 权限守卫组件示例 */}
      <section className="mb-8 rounded-lg bg-white p-6 shadow">
        <h2 className="mb-4 text-xl font-semibold">权限守卫组件示例</h2>

        <div className="space-y-4">
          <div>
            <h3 className="mb-2 font-semibold">单个权限守卫:</h3>
            <PermissionGuard permission="user:create">
              <button className="rounded bg-blue-600 px-4 py-2 text-white hover:bg-blue-700">
                创建用户（需要 user:create 权限）
              </button>
            </PermissionGuard>
            <PermissionGuard
              permission="user:create"
              fallback={<div className="text-red-600">无权限创建用户</div>}
            >
              <button className="rounded bg-blue-600 px-4 py-2 text-white hover:bg-blue-700">
                创建用户（带fallback）
              </button>
            </PermissionGuard>
          </div>

          <div>
            <h3 className="mb-2 font-semibold">任一权限守卫:</h3>
            <PermissionGuard anyPermission={['user:create', 'user:edit']}>
              <button className="rounded bg-green-600 px-4 py-2 text-white hover:bg-green-700">
                编辑用户（需要 user:create 或 user:edit）
              </button>
            </PermissionGuard>
          </div>

          <div>
            <h3 className="mb-2 font-semibold">所有权限守卫:</h3>
            <PermissionGuard allPermissions={['user:create', 'user:delete']}>
              <button className="rounded bg-red-600 px-4 py-2 text-white hover:bg-red-700">
                批量操作（需要 user:create 和 user:delete）
              </button>
            </PermissionGuard>
          </div>

          <div>
            <h3 className="mb-2 font-semibold">角色守卫:</h3>
            <PermissionGuard role="admin">
              <button className="rounded bg-purple-600 px-4 py-2 text-white hover:bg-purple-700">
                管理员功能（需要 admin 角色）
              </button>
            </PermissionGuard>
          </div>

          <div>
            <h3 className="mb-2 font-semibold">权限按钮（自动禁用）:</h3>
            <div className="flex gap-2">
              <PermissionButton
                permission="user:create"
                className="rounded bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-gray-400"
              >
                创建用户
              </PermissionButton>

              <PermissionButton
                permission="user:delete"
                className="rounded bg-red-600 px-4 py-2 text-white hover:bg-red-700 disabled:cursor-not-allowed disabled:bg-gray-400"
              >
                删除用户
              </PermissionButton>
            </div>
          </div>
        </div>
      </section>

      {/* Token信息 */}
      <section className="rounded-lg bg-white p-6 shadow">
        <h2 className="mb-4 text-xl font-semibold">Token信息</h2>
        <div className="overflow-x-auto rounded bg-gray-100 p-4">
          <code className="text-xs break-all">{token}</code>
        </div>
      </section>
    </div>
  );
}
