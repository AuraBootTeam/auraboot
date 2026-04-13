import {
  Form,
  useActionData,
  useNavigate,
  useLoaderData,
  type ActionFunctionArgs,
  type LoaderFunctionArgs,
} from 'react-router';
import { useState, useRef, useEffect } from 'react';
import { createUserSession, getTokenFromRequest } from '~/services/session';
import { useTenantForm } from '~/hooks/useTenantForm';
import TenantFormFields from '~/components/TenantFormFields';
import {
  BuildingOfficeIcon,
  UserGroupIcon,
  ArrowLeftIcon,
  ClockIcon,
  CogIcon,
} from '@heroicons/react/24/outline';
import { ResultHelper } from '~/utils/type';

interface UserSpace {
  tenantId: number;
  tenantName: string;
  tenantDisplayName: string;
  spaceType: 'platform' | 'business';
  roleCodes: string[];
  isDefault: boolean;
}

/**
 * Loader: fetch user's existing spaces to show space selection if available.
 */
export const loader = async ({ request }: LoaderFunctionArgs) => {
  const token = await getTokenFromRequest(request);
  if (!token) return { spaces: [] };

  try {
    const apiUrl = process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';
    const resp = await fetch(`${apiUrl}/api/tenant-selection/my-spaces`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!resp.ok) return { spaces: [] };
    const result = await resp.json();
    return { spaces: (result.data || []) as UserSpace[] };
  } catch {
    return { spaces: [] };
  }
};

interface TenantSelectionResponse {
  status: string;
  message: string;
  tenantId?: number;
  tenantName?: string;
  jwt?: string;
  needsApproval?: boolean;
}

export const action = async ({ request }: ActionFunctionArgs) => {
  const token = await getTokenFromRequest(request);

  if (!token) {
    return {
      success: false,
      error: '未找到认证信息，请重新登录',
    };
  }

  const formData = await request.formData();
  const action = formData.get('action');

  const requestData: any = { action };

  if (action === 'create') {
    requestData.tenantName = formData.get('tenantName');
    requestData.displayName = formData.get('displayName');
    requestData.industry = formData.get('industry');
    requestData.contactEmail = formData.get('contactEmail');
    requestData.contactPhone = formData.get('contactPhone');
    requestData.description = formData.get('description');
  } else if (action === 'join') {
    requestData.inviteCode = formData.get('inviteCode');
  } else if (action === 'select') {
    requestData.tenantId = Number(formData.get('tenantId'));
  }

  try {
    const apiUrl = process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';
    const url = `${apiUrl}/api/tenant-selection/process`;

    const response = await fetch(url, {
      method: 'post',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(requestData),
    });

    if (!response.ok) {
      return {
        success: false,
        error: `请求失败: ${response.status} ${response.statusText}`,
      };
    }

    const result = await response.json();

    if (ResultHelper.isSuccess(result) && result.data) {
      const responseData = result.data as TenantSelectionResponse;

      if (responseData.status === 'success' && responseData.jwt) {
        // 创建租户成功，更新session并重定向
        return createUserSession({
          request: request,
          token: responseData.jwt,
          remember: false,
          redirectTo: '/',
        });
      } else if (responseData.status === 'pending') {
        // 加入申请已提交，显示等待审批页面
        return { success: true, pending: true, message: responseData.message };
      }
    } else {
      return {
        success: false,
        error: result.desc || '操作失败',
      };
    }
  } catch (error) {
    return {
      success: false,
      error: error instanceof Error ? error.message : '网络请求失败',
    };
  }

  return {
    success: false,
    error: '操作失败',
  };
};

export default function TenantSelection() {
  const [selectedAction, setSelectedAction] = useState<'create' | 'join' | null>(null);
  const actionData = useActionData<typeof action>();
  const { spaces } = useLoaderData<typeof loader>() ?? { spaces: [] };
  const navigate = useNavigate();
  const { formData, errors, handleInputChange } = useTenantForm();
  const hasExistingSpaces = spaces && spaces.length > 0;

  const inviteCodeRef = useRef<HTMLInputElement>(null);
  // Track which action produced the error so stale errors don't leak across views
  const [errorAction, setErrorAction] = useState<string | null>(null);

  useEffect(() => {
    if (actionData?.error) {
      setErrorAction(selectedAction);
    }
  }, [actionData]);

  const handleSwitchAction = (action: 'create' | 'join' | null) => {
    setErrorAction(null);
    setSelectedAction(action);
  };

  const showError = actionData?.error && errorAction === selectedAction;

  if (actionData?.pending) {
    return (
      <div className="flex min-h-screen items-center justify-center p-4">
        <div className="w-full max-w-md">
          <div className="rounded-2xl border border-gray-200 bg-white p-8 shadow-xl dark:border-gray-700 dark:bg-gray-800">
            <div className="text-center">
              <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-full bg-yellow-100 dark:bg-yellow-900/20">
                <ClockIcon className="h-8 w-8 text-yellow-600 dark:text-yellow-400" />
              </div>
              <h2 className="mb-4 text-2xl font-bold text-gray-900 dark:text-white">申请已提交</h2>
              <p className="mb-8 text-gray-600 dark:text-gray-400">{actionData.message}</p>
              <button
                className="w-full rounded-lg bg-blue-600 px-4 py-3 font-medium text-white transition-colors hover:bg-blue-700"
                onClick={() => navigate('/login')}
              >
                返回登录
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen items-center justify-center p-4">
      <div className="w-full max-w-6xl">
        <div className="overflow-hidden rounded-2xl border border-gray-200 bg-white shadow-xl dark:border-gray-700 dark:bg-gray-800">
          <div className="p-8">
            <div className="mb-12 text-center">
              <h1 className="mb-4 text-4xl font-bold text-gray-900 dark:text-white">
                {hasExistingSpaces ? 'Select Your Workspace' : 'Choose Your Workspace'}
              </h1>
              <p className="text-lg text-gray-600 dark:text-gray-400">
                {hasExistingSpaces
                  ? 'Select a space to continue, or create a new one'
                  : 'Create a new organization or join an existing team'}
              </p>
            </div>

            {/* Existing spaces — shown when user has memberships */}
            {hasExistingSpaces && !selectedAction && (
              <div className="mb-8 space-y-3" data-testid="space-list">
                {spaces.map((space: UserSpace) => (
                  <Form method="post" key={space.tenantId}>
                    <input type="hidden" name="action" value="select" />
                    <input type="hidden" name="tenantId" value={space.tenantId} />
                    <button
                      type="submit"
                      data-testid={`space-${space.spaceType}-${space.tenantId}`}
                      className={`flex w-full items-center gap-4 rounded-xl border-2 p-5 text-left transition-all hover:shadow-lg ${
                        space.spaceType === 'platform'
                          ? 'border-purple-200 bg-gradient-to-r from-purple-50 to-indigo-50 hover:border-purple-400 dark:border-purple-700 dark:from-purple-900/20 dark:to-indigo-900/20'
                          : 'border-blue-200 bg-gradient-to-r from-blue-50 to-cyan-50 hover:border-blue-400 dark:border-blue-700 dark:from-blue-900/20 dark:to-cyan-900/20'
                      }`}
                    >
                      <div
                        className={`flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-xl ${
                          space.spaceType === 'platform'
                            ? 'bg-purple-100 dark:bg-purple-900/40'
                            : 'bg-blue-100 dark:bg-blue-900/40'
                        }`}
                      >
                        {space.spaceType === 'platform' ? (
                          <CogIcon className="h-6 w-6 text-purple-600 dark:text-purple-400" />
                        ) : (
                          <BuildingOfficeIcon className="h-6 w-6 text-blue-600 dark:text-blue-400" />
                        )}
                      </div>
                      <div className="min-w-0 flex-1">
                        <h3 className="text-lg font-semibold text-gray-900 dark:text-white">
                          {space.tenantDisplayName || space.tenantName}
                        </h3>
                        <p className="text-sm text-gray-500 dark:text-gray-400">
                          {space.spaceType === 'platform'
                            ? 'Platform Management'
                            : 'Business Workspace'}
                        </p>
                      </div>
                      <span className="text-sm text-gray-400">&rarr;</span>
                    </button>
                  </Form>
                ))}

                <div className="pt-4 text-center">
                  <p className="text-sm text-gray-400 dark:text-gray-500">
                    Or{' '}
                    <button
                      onClick={() => handleSwitchAction('create')}
                      className="text-blue-600 hover:underline dark:text-blue-400"
                    >
                      create a new organization
                    </button>
                    {' / '}
                    <button
                      onClick={() => handleSwitchAction('join')}
                      className="text-green-600 hover:underline dark:text-green-400"
                    >
                      join with invite code
                    </button>
                  </p>
                </div>
              </div>
            )}

            {!selectedAction && !hasExistingSpaces ? (
              <div className="grid gap-8 md:grid-cols-2">
                {/* 创建新租户选项 */}
                <div
                  className="group relative cursor-pointer rounded-2xl border-2 border-blue-200 bg-gradient-to-br from-blue-50 to-indigo-50 p-8 transition-all duration-200 hover:border-blue-300 hover:shadow-lg dark:border-blue-700 dark:from-blue-900/20 dark:to-indigo-900/20 dark:hover:border-blue-600"
                  onClick={() => handleSwitchAction('create')}
                >
                  <div className="text-center">
                    <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-full bg-blue-100 transition-transform group-hover:scale-110 dark:bg-blue-900/40">
                      <BuildingOfficeIcon className="h-8 w-8 text-blue-600 dark:text-blue-400" />
                    </div>
                    <h2 className="mb-4 text-2xl font-bold text-gray-900 dark:text-white">
                      创建新租户
                    </h2>
                    <p className="mb-6 text-gray-600 dark:text-gray-400">
                      创建一个新的组织空间，您将成为该组织的管理员
                    </p>
                    <div className="inline-flex items-center rounded-lg bg-blue-600 px-4 py-2 font-medium text-white transition-colors group-hover:bg-blue-700">
                      开始创建
                    </div>
                  </div>
                </div>

                {/* 加入现有租户选项 */}
                <div
                  className="group relative cursor-pointer rounded-2xl border-2 border-green-200 bg-gradient-to-br from-green-50 to-emerald-50 p-8 transition-all duration-200 hover:border-green-300 hover:shadow-lg dark:border-green-700 dark:from-green-900/20 dark:to-emerald-900/20 dark:hover:border-green-600"
                  onClick={() => handleSwitchAction('join')}
                >
                  <div className="text-center">
                    <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-full bg-green-100 transition-transform group-hover:scale-110 dark:bg-green-900/40">
                      <UserGroupIcon className="h-8 w-8 text-green-600 dark:text-green-400" />
                    </div>
                    <h2 className="mb-4 text-2xl font-bold text-gray-900 dark:text-white">
                      加入现有租户
                    </h2>
                    <p className="mb-6 text-gray-600 dark:text-gray-400">
                      使用邀请码加入已有的组织，需要管理员审批
                    </p>
                    <div className="inline-flex items-center rounded-lg bg-green-600 px-4 py-2 font-medium text-white transition-colors group-hover:bg-green-700">
                      立即加入
                    </div>
                  </div>
                </div>
              </div>
            ) : (
              <div>
                <div className="mb-8 flex items-center">
                  <button
                    className="flex items-center text-gray-600 transition-colors hover:text-gray-900 dark:text-gray-400 dark:hover:text-white"
                    onClick={() => handleSwitchAction(null)}
                  >
                    <ArrowLeftIcon className="mr-2 h-5 w-5" />
                    返回选择
                  </button>
                </div>

                {selectedAction === 'create' && (
                  <Form method="post" className="space-y-6">
                    <input type="hidden" name="action" value="create" />

                    <div className="mb-8">
                      <h2 className="mb-2 text-3xl font-bold text-gray-900 dark:text-white">
                        创建新租户
                      </h2>
                      <p className="text-gray-600 dark:text-gray-400">填写以下信息来创建您的组织</p>
                    </div>

                    <TenantFormFields
                      formData={formData}
                      errors={errors}
                      onChange={handleInputChange}
                      showLogo={false}
                      showWebsite={false}
                      variant="selection"
                    />

                    {showError && (
                      <div className="rounded-lg border border-red-200 bg-red-50 p-4 dark:border-red-800 dark:bg-red-900/20">
                        <p className="text-red-600 dark:text-red-400">{actionData.error}</p>
                      </div>
                    )}

                    <button
                      type="submit"
                      className="w-full rounded-lg bg-blue-600 px-4 py-3 font-medium text-white transition-colors hover:bg-blue-700"
                    >
                      创建租户
                    </button>
                  </Form>
                )}

                {selectedAction === 'join' && (
                  <Form method="post" className="space-y-6">
                    <input type="hidden" name="action" value="join" />

                    <div className="mb-8">
                      <h2 className="mb-2 text-3xl font-bold text-gray-900 dark:text-white">
                        加入现有租户
                      </h2>
                      <p className="text-gray-600 dark:text-gray-400">输入管理员提供的邀请码</p>
                    </div>

                    <div className="space-y-2">
                      <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">
                        邀请码 *
                      </label>
                      <input
                        ref={inviteCodeRef}
                        name="inviteCode"
                        type="text"
                        required
                        className="w-full rounded-lg border border-gray-300 bg-white px-4 py-3 text-gray-900 placeholder-gray-500 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white dark:placeholder-gray-400"
                        placeholder="输入邀请码"
                      />
                      <p className="text-sm text-gray-500 dark:text-gray-400">
                        请输入租户管理员提供的邀请码
                      </p>
                    </div>

                    {showError && (
                      <div className="rounded-lg border border-red-200 bg-red-50 p-4 dark:border-red-800 dark:bg-red-900/20">
                        <p className="text-red-600 dark:text-red-400">{actionData.error}</p>
                      </div>
                    )}

                    <button
                      type="submit"
                      className="w-full rounded-lg bg-green-600 px-4 py-3 font-medium text-white transition-colors hover:bg-green-700"
                    >
                      提交申请
                    </button>
                  </Form>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
