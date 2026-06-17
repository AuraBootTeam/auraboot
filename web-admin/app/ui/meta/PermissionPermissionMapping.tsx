/**
 * 权限点权限映射组件
 *
 * 管理模型权限点与角色的绑定关系
 *
 * 功能特性:
 * - 显示模型的所有权限点
 * - 角色-权限点绑定管理
 * - 权限映射查看
 * - 引用情况追踪
 *
 * 需求: 17.1-17.8
 */

import React, { useState, useCallback, useEffect } from 'react';
import type { Permission, PermissionReference } from '~/types/model';
import { useToastContext } from '~/contexts/ToastContext';

/**
 * 权限点权限映射Props
 */
interface PermissionPermissionMappingProps {
  /** 是否显示 */
  visible: boolean;
  /** 模型编码 */
  modelCode: string | null;
  /** 关闭回调 */
  onClose: () => void;
}

/**
 * 角色信息
 */
interface RoleInfo {
  id: string;
  code: string;
  name: string;
  description?: string;
}

/**
 * 权限点权限映射组件
 */
export function PermissionPermissionMapping({
  visible,
  modelCode,
  onClose,
}: PermissionPermissionMappingProps) {
  const { showSuccessToast, showErrorToast } = useToastContext();

  // 权限点列表
  const [permissions, setPermissions] = useState<Permission[]>([]);

  // 角色列表
  const [roles, setRoles] = useState<RoleInfo[]>([]);

  // 选中的权限点
  const [selectedPermission, setSelectedPermission] = useState<Permission | null>(null);

  // 权限点的引用情况
  const [references, setReferences] = useState<PermissionReference[]>([]);

  // 是否显示引用详情
  const [showReferences, setShowReferences] = useState(false);

  // 加载状态
  const [loading, setLoading] = useState(false);

  // 绑定对话框
  const [bindDialog, setBindDialog] = useState<{
    show: boolean;
    permission: Permission | null;
    selectedRoles: string[];
  }>({
    show: false,
    permission: null,
    selectedRoles: [],
  });

  /**
   * 加载权限点列表
   */
  useEffect(() => {
    if (visible && modelCode) {
      loadPermissions();
      loadRoles();
    }
  }, [visible, modelCode]);

  /**
   * 加载权限点列表
   */
  const loadPermissions = useCallback(async () => {
    if (!modelCode) return;

    setLoading(true);
    try {
      // TODO: 调用API获取权限点列表
      // const caps = await permissionService.getModelPermissions(modelCode);
      // setPermissions(caps);

      // 模拟数据
      const mockPermissions: Permission[] = [
        {
          id: '1',
          type: 'model',
          refCode: modelCode,
          action: 'read',
          displayName: '查看模型',
          description: '查看模型的详细信息',
          createdAt: new Date().toISOString(),
        },
        {
          id: '2',
          type: 'model',
          refCode: modelCode,
          action: 'create',
          displayName: '创建模型',
          description: '创建新的模型实例',
          createdAt: new Date().toISOString(),
        },
        {
          id: '3',
          type: 'model',
          refCode: modelCode,
          action: 'update',
          displayName: '更新模型',
          description: '更新模型的信息',
          createdAt: new Date().toISOString(),
        },
        {
          id: '4',
          type: 'model',
          refCode: modelCode,
          action: 'delete',
          displayName: '删除模型',
          description: '删除模型实例',
          createdAt: new Date().toISOString(),
        },
        {
          id: '5',
          type: 'model',
          refCode: modelCode,
          action: 'export',
          displayName: '导出模型',
          description: '导出模型数据',
          createdAt: new Date().toISOString(),
        },
      ];
      setPermissions(mockPermissions);
    } catch (error) {
      console.error('Failed to load permissions:', error);
      showErrorToast('加载权限点失败');
    } finally {
      setLoading(false);
    }
  }, [modelCode]);

  /**
   * 加载角色列表
   */
  const loadRoles = useCallback(async () => {
    setLoading(true);
    try {
      // TODO: 调用API获取角色列表
      // const roleList = await roleService.getAllRoles();
      // setRoles(roleList);

      // 模拟数据
      const mockRoles: RoleInfo[] = [
        { id: '1', code: 'admin', name: '管理员', description: '系统管理员' },
        { id: '2', code: 'developer', name: '开发者', description: '系统开发者' },
        { id: '3', code: 'operator', name: '运维人员', description: '系统运维人员' },
        { id: '4', code: 'viewer', name: '查看者', description: '只读用户' },
      ];
      setRoles(mockRoles);
    } catch (error) {
      console.error('Failed to load roles:', error);
    } finally {
      setLoading(false);
    }
  }, []);

  /**
   * 查看权限点引用
   */
  const handleViewReferences = useCallback(async (permission: Permission) => {
    setSelectedPermission(permission);
    setLoading(true);

    try {
      // TODO: 调用API获取引用情况
      // const refs = await permissionService.getPermissionReferences(permission.id);
      // setReferences(refs);

      // 模拟数据
      const mockReferences: PermissionReference[] = [
        {
          id: '1',
          permissionId: permission.id,
          referenceType: 'role',
          referenceId: '1',
          referenceName: '管理员',
        },
        {
          id: '2',
          permissionId: permission.id,
          referenceType: 'role',
          referenceId: '2',
          referenceName: '开发者',
        },
      ];
      setReferences(mockReferences);
      setShowReferences(true);
    } catch (error) {
      console.error('Failed to load references:', error);
      showErrorToast('加载引用情况失败');
    } finally {
      setLoading(false);
    }
  }, []);

  /**
   * 打开绑定对话框
   */
  const handleOpenBindDialog = useCallback(async (permission: Permission) => {
    setLoading(true);

    try {
      // TODO: 获取已绑定的角色
      // const boundRoles = await permissionService.getBoundRoles(permission.id);
      // const roleIds = boundRoles.map(r => r.id);

      // 模拟数据
      const roleIds = ['1', '2'];

      setBindDialog({
        show: true,
        permission,
        selectedRoles: roleIds,
      });
    } catch (error) {
      console.error('Failed to load bound roles:', error);
      showErrorToast('加载绑定信息失败');
    } finally {
      setLoading(false);
    }
  }, []);

  /**
   * 切换角色选择
   */
  const toggleRoleSelection = useCallback((roleId: string) => {
    setBindDialog((prev) => ({
      ...prev,
      selectedRoles: prev.selectedRoles.includes(roleId)
        ? prev.selectedRoles.filter((id) => id !== roleId)
        : [...prev.selectedRoles, roleId],
    }));
  }, []);

  /**
   * 保存绑定
   */
  const handleSaveBinding = useCallback(async () => {
    if (!bindDialog.permission) return;

    setLoading(true);
    try {
      // TODO: 调用API保存绑定
      // await permissionService.updateRoleBindings(
      //   bindDialog.permission.id,
      //   bindDialog.selectedRoles
      // );

      showSuccessToast('绑定保存成功');
      setBindDialog({ show: false, permission: null, selectedRoles: [] });

      // 重新加载权限点列表
      await loadPermissions();
    } catch (error) {
      console.error('Failed to save binding:', error);
      showErrorToast('保存绑定失败');
    } finally {
      setLoading(false);
    }
  }, [bindDialog, loadPermissions]);

  /**
   * 获取操作图标
   */
  const getActionIcon = useCallback((action: string) => {
    const iconMap: Record<string, React.ReactElement> = {
      read: (
        <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
          />
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
          />
        </svg>
      ),
      create: (
        <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
        </svg>
      ),
      update: (
        <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
          />
        </svg>
      ),
      delete: (
        <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
          />
        </svg>
      ),
      export: (
        <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
          />
        </svg>
      ),
    };
    return iconMap[action] || iconMap['read'];
  }, []);

  /**
   * 获取操作颜色
   */
  const getActionColor = useCallback((action: string): string => {
    const colorMap: Record<string, string> = {
      read: 'text-accent bg-accent-weak',
      create: 'text-status-green bg-status-green-bg',
      update: 'text-status-amber bg-status-amber-bg',
      delete: 'text-status-red bg-status-red-bg',
      export: 'text-purple-600 bg-purple-50',
    };
    return colorMap[action] || 'text-text-2 bg-subtle';
  }, []);

  if (!visible || !modelCode) return null;

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto">
      {/* 遮罩层 */}
      <div className="bg-opacity-50 fixed inset-0 bg-black" onClick={onClose} />

      {/* 对话框 */}
      <div className="flex min-h-screen items-center justify-center p-4">
        <div className="rounded-card bg-panel relative flex max-h-[90vh] w-full max-w-4xl flex-col shadow-xl">
          {/* 标题栏 */}
          <div className="border-border border-b px-6 py-4">
            <h2 className="text-text text-lg font-semibold">权限点权限映射</h2>
            <p className="text-text-2 mt-1 text-sm">
              管理模型 <span className="text-accent font-mono">{modelCode}</span> 的权限点与角色绑定
            </p>
          </div>

          {/* 内容区域 */}
          <div className="flex-1 overflow-y-auto px-6 py-4">
            {loading && permissions.length === 0 ? (
              <div className="flex items-center justify-center py-12">
                <div className="rounded-pill border-accent h-8 w-8 animate-spin border-b-2"></div>
                <span className="text-text-2 ml-3">加载中...</span>
              </div>
            ) : permissions.length === 0 ? (
              <div className="text-text-2 py-12 text-center">该模型还没有权限点</div>
            ) : (
              <div className="space-y-3">
                {permissions.map((permission) => (
                  <div
                    key={permission.id}
                    className="rounded-card border-border hover:border-border-strong border p-4 transition-colors"
                  >
                    <div className="flex items-start justify-between">
                      <div className="flex flex-1 items-start gap-3">
                        {/* 操作图标 */}
                        <div
                          className={`rounded-card flex h-10 w-10 items-center justify-center ${getActionColor(permission.action)}`}
                        >
                          {getActionIcon(permission.action)}
                        </div>

                        {/* 权限点信息 */}
                        <div className="flex-1">
                          <h4 className="text-text text-base font-medium">
                            {permission.displayName}
                          </h4>
                          {permission.description && (
                            <p className="text-text-2 mt-1 text-sm">{permission.description}</p>
                          )}
                          <div className="text-text-2 mt-2 flex items-center gap-4 text-xs">
                            <span>类型: {permission.type}</span>
                            <span>操作: {permission.action}</span>
                          </div>
                        </div>
                      </div>

                      {/* 操作按钮 */}
                      <div className="flex items-center gap-2">
                        <button
                          onClick={() => handleViewReferences(permission)}
                          disabled={loading}
                          className="rounded-control text-text-2 hover:bg-subtle hover:text-text-2 px-3 py-1 text-sm disabled:opacity-50"
                        >
                          查看引用
                        </button>
                        <button
                          onClick={() => handleOpenBindDialog(permission)}
                          disabled={loading}
                          className="rounded-control text-accent hover:bg-accent-weak hover:text-accent px-3 py-1 text-sm disabled:opacity-50"
                        >
                          绑定角色
                        </button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* 底部按钮 */}
          <div className="border-border flex justify-end border-t px-6 py-4">
            <button
              onClick={onClose}
              className="rounded-control border-border-strong text-text-2 hover:bg-subtle border px-4 py-2"
            >
              关闭
            </button>
          </div>
        </div>
      </div>

      {/* 引用详情对话框 */}
      {showReferences && selectedPermission && (
        <div className="fixed inset-0 z-[60] overflow-y-auto">
          <div
            className="bg-opacity-50 fixed inset-0 bg-black"
            onClick={() => setShowReferences(false)}
          />
          <div className="flex min-h-screen items-center justify-center p-4">
            <div className="rounded-card bg-panel relative w-full max-w-2xl p-6 shadow-xl">
              <h3 className="text-text mb-4 text-lg font-semibold">权限点引用情况</h3>
              <p className="text-text-2 mb-4 text-sm">
                权限点 <span className="font-medium">{selectedPermission.displayName}</span>{' '}
                的引用情况
              </p>

              {references.length === 0 ? (
                <div className="text-text-2 py-8 text-center">暂无引用</div>
              ) : (
                <div className="space-y-2">
                  {references.map((ref) => (
                    <div
                      key={ref.id}
                      className="rounded-card bg-subtle flex items-center justify-between p-3"
                    >
                      <div>
                        <span className="text-text text-sm font-medium">{ref.referenceName}</span>
                        <span className="text-text-2 ml-2 text-xs">({ref.referenceType})</span>
                      </div>
                    </div>
                  ))}
                </div>
              )}

              <div className="mt-6 flex justify-end">
                <button
                  onClick={() => setShowReferences(false)}
                  className="rounded-control border-border-strong text-text-2 hover:bg-subtle border px-4 py-2"
                >
                  关闭
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 绑定角色对话框 */}
      {bindDialog.show && bindDialog.permission && (
        <div className="fixed inset-0 z-[60] overflow-y-auto">
          <div
            className="bg-opacity-50 fixed inset-0 bg-black"
            onClick={() => setBindDialog({ show: false, permission: null, selectedRoles: [] })}
          />
          <div className="flex min-h-screen items-center justify-center p-4">
            <div className="rounded-card bg-panel relative w-full max-w-2xl p-6 shadow-xl">
              <h3 className="text-text mb-4 text-lg font-semibold">绑定角色</h3>
              <p className="text-text-2 mb-4 text-sm">
                为权限点 <span className="font-medium">{bindDialog.permission.displayName}</span>{' '}
                选择角色
              </p>

              <div className="max-h-96 space-y-2 overflow-y-auto">
                {roles.map((role) => (
                  <div
                    key={role.id}
                    onClick={() => toggleRoleSelection(role.id)}
                    className={`rounded-card cursor-pointer border p-3 transition-colors ${
                      bindDialog.selectedRoles.includes(role.id)
                        ? 'bg-accent-weak border-accent'
                        : 'border-border hover:border-border-strong'
                    }`}
                  >
                    <div className="flex items-center gap-3">
                      <input
                        type="checkbox"
                        checked={bindDialog.selectedRoles.includes(role.id)}
                        onChange={() => {}}
                        className="border-border-strong text-accent focus-visible:shadow-focus h-4 w-4 rounded focus:outline-none"
                      />
                      <div className="flex-1">
                        <div className="text-text text-sm font-medium">{role.name}</div>
                        {role.description && (
                          <div className="text-text-2 text-xs">{role.description}</div>
                        )}
                      </div>
                    </div>
                  </div>
                ))}
              </div>

              <div className="mt-6 flex justify-end gap-3">
                <button
                  onClick={() =>
                    setBindDialog({ show: false, permission: null, selectedRoles: [] })
                  }
                  disabled={loading}
                  className="rounded-control border-border-strong text-text-2 hover:bg-subtle border px-4 py-2 disabled:opacity-50"
                >
                  取消
                </button>
                <button
                  onClick={handleSaveBinding}
                  disabled={loading}
                  className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-white disabled:opacity-50"
                >
                  {loading ? '保存中...' : '保存'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
