import {
  Form,
  Link,
  useActionData,
  useNavigate,
  useLoaderData,
  useNavigation,
  useRevalidator,
  type ActionFunctionArgs,
  type LoaderFunctionArgs,
} from 'react-router';
import { useState, useRef, useEffect } from 'react';
import { createUserSession, getTokenFromRequest } from '~/shared/services/session';
import { useTenantForm } from '~/hooks/useTenantForm';
import TenantFormFields from '~/ui/TenantFormFields';
import {
  BuildingOfficeIcon,
  UserGroupIcon,
  ArrowLeftIcon,
  ArrowRightIcon,
  CheckCircleIcon,
  ClockIcon,
  CogIcon,
  DevicePhoneMobileIcon,
  ExclamationTriangleIcon,
} from '@heroicons/react/24/outline';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import {
  canSelfProvisionTenant,
  CLOSED_ACCESS_POLICY,
  fetchAccessPolicyResult,
} from '~/services/accessPolicy';
import { COMMUNITY_BRANDING } from '~/config/branding';
import { useRootLoaderData } from '~/root-data';

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
  const accessPolicyResult = await fetchAccessPolicyResult();
  if (!token) {
    return {
      spaces: [],
      spacesStatus: 'loaded' as const,
      accessPolicy: accessPolicyResult.policy,
      accessPolicyStatus: accessPolicyResult.status,
    };
  }

  try {
    const apiUrl = process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';
    const resp = await fetch(`${apiUrl}/api/tenant-selection/my-spaces`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!resp.ok) {
      return {
        spaces: [],
        spacesStatus: 'unavailable' as const,
        accessPolicy: accessPolicyResult.policy,
        accessPolicyStatus: accessPolicyResult.status,
      };
    }
    const result = await resp.json();
    return {
      spaces: (result.data || []) as UserSpace[],
      spacesStatus: 'loaded' as const,
      accessPolicy: accessPolicyResult.policy,
      accessPolicyStatus: accessPolicyResult.status,
    };
  } catch (err) {
    console.error('[TenantSelection] my-spaces fetch failed', err);
    return {
      spaces: [],
      spacesStatus: 'unavailable' as const,
      accessPolicy: accessPolicyResult.policy,
      accessPolicyStatus: accessPolicyResult.status,
    };
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
      error: 'Authentication required. Please sign in again.',
    };
  }

  const formData = await request.formData();
  const action = formData.get('action');
  const postCreateRedirect = formData.get('postCreateRedirect') === '/xy/setup' ? '/xy/setup' : '/';
  const accessPolicyResult = await fetchAccessPolicyResult();
  const accessPolicy = accessPolicyResult.policy;

  if (accessPolicyResult.status === 'unavailable') {
    return {
      success: false,
      error: '服务暂时无法确认当前开通策略，请稍后重试。',
    };
  }

  if ((action === 'create' || action === 'join') && !canSelfProvisionTenant(accessPolicy)) {
    return {
      success: false,
      error: '当前环境未开放自助创建或加入组织，请联系平台管理员。',
    };
  }

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
    // tenantId is a snowflake id that routinely exceeds 2^53 (Number.MAX_SAFE_INTEGER).
    // Number() silently loses precision (e.g. ...699456 -> ...699460), so the backend
    // receives a non-existent tenant id and rejects selection with an auth error. Keep
    // the raw string — the backend's Long tenantId field accepts a JSON string value.
    requestData.tenantId = formData.get('tenantId');
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
        error: `Request failed: ${response.status} ${response.statusText}`,
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
          redirectTo: action === 'create' ? postCreateRedirect : '/',
        });
      } else if (responseData.status === 'pending') {
        // 加入申请已提交，显示等待审批页面
        return { success: true, pending: true, message: responseData.message };
      }
    } else {
      return {
        success: false,
        error: result.message || result.desc || 'Operation failed',
      };
    }
  } catch (error) {
    return {
      success: false,
      error: error instanceof Error ? error.message : 'Network request failed',
    };
  }

  return {
    success: false,
    error: 'Operation failed',
  };
};

export default function TenantSelection() {
  const [selectedAction, setSelectedAction] = useState<'create' | 'join' | null>(null);
  const actionData = useActionData<typeof action>();
  const loaderData = useLoaderData<typeof loader>();
  const spaces = loaderData?.spaces ?? [];
  const spacesStatus = loaderData?.spacesStatus ?? 'unavailable';
  const accessPolicy = loaderData?.accessPolicy ?? CLOSED_ACCESS_POLICY;
  const accessPolicyStatus = loaderData?.accessPolicyStatus ?? 'unavailable';
  const navigate = useNavigate();
  const navigation = useNavigation();
  const revalidator = useRevalidator();
  const { t } = useI18n();
  const { formData, errors, setErrors, handleInputChange } = useTenantForm();
  const branding = useRootLoaderData()?.branding ?? COMMUNITY_BRANDING;
  const onboarding = branding.tenantOnboarding;
  const hasExistingSpaces = spaces.length > 0;
  const allowTenantSelfService = canSelfProvisionTenant(accessPolicy);
  const dataUnavailable = accessPolicyStatus === 'unavailable' || spacesStatus === 'unavailable';
  const isSubmitting = navigation.state === 'submitting';

  const createTitle =
    onboarding?.createTitle ?? t('tenant.select.create.title', undefined, '创建新租户');
  const createDescription =
    onboarding?.createDescription ??
    t('tenant.select.create.desc', undefined, '创建一个新的组织空间，你将成为该组织的管理员。');
  const createCta = onboarding?.createCta ?? t('tenant.select.create.cta', undefined, '开始创建');
  const joinTitle =
    onboarding?.joinTitle ?? t('tenant.select.join.title', undefined, '加入现有租户');
  const joinDescription =
    onboarding?.joinDescription ??
    t('tenant.select.join.desc', undefined, '使用管理员提供的邀请码加入已有组织。');
  const joinCta = onboarding?.joinCta ?? t('tenant.select.join.cta', undefined, '立即加入');
  const inviteCodeRef = useRef<HTMLInputElement>(null);
  const [errorAction, setErrorAction] = useState<string | null>(null);

  useEffect(() => {
    if (actionData?.error) setErrorAction(selectedAction);
  }, [actionData, selectedAction]);

  useEffect(() => {
    if (selectedAction === 'join' && onboarding?.joinChannel !== 'wechat_mini') {
      inviteCodeRef.current?.focus();
    }
  }, [onboarding?.joinChannel, selectedAction]);

  const handleSwitchAction = (nextAction: 'create' | 'join' | null) => {
    setErrorAction(null);
    setSelectedAction(nextAction);
  };
  const showError = actionData?.error && errorAction === selectedAction;

  const actionChoices = (compact = false) => (
    <div className={`grid gap-4 ${compact ? 'lg:grid-cols-2' : 'md:grid-cols-2'}`}>
      <button
        type="button"
        data-testid="tenant-action-create"
        onClick={() => handleSwitchAction('create')}
        className="group flex min-h-48 flex-col rounded-xl border border-gray-200 bg-white p-6 text-left shadow-sm transition hover:border-blue-300 hover:shadow-md focus-visible:ring-4 focus-visible:ring-blue-100 focus-visible:outline-none dark:border-gray-700 dark:bg-gray-800 dark:hover:border-blue-600"
      >
        <span className="mb-5 flex h-11 w-11 items-center justify-center rounded-lg bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-300">
          <BuildingOfficeIcon className="h-6 w-6" aria-hidden="true" />
        </span>
        <span className="text-lg font-semibold text-gray-950 dark:text-white">{createTitle}</span>
        <span className="mt-2 flex-1 text-sm leading-6 text-gray-600 dark:text-gray-300">
          {createDescription}
        </span>
        <span className="mt-5 inline-flex items-center gap-2 text-sm font-semibold text-blue-600 dark:text-blue-300">
          {createCta}
          <ArrowRightIcon
            className="h-4 w-4 transition group-hover:translate-x-0.5"
            aria-hidden="true"
          />
        </span>
      </button>

      <button
        type="button"
        data-testid="tenant-action-join"
        onClick={() => handleSwitchAction('join')}
        className="group flex min-h-48 flex-col rounded-xl border border-gray-200 bg-white p-6 text-left shadow-sm transition hover:border-emerald-300 hover:shadow-md focus-visible:ring-4 focus-visible:ring-emerald-100 focus-visible:outline-none dark:border-gray-700 dark:bg-gray-800 dark:hover:border-emerald-600"
      >
        <span className="mb-5 flex h-11 w-11 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300">
          {onboarding?.joinChannel === 'wechat_mini' ? (
            <DevicePhoneMobileIcon className="h-6 w-6" aria-hidden="true" />
          ) : (
            <UserGroupIcon className="h-6 w-6" aria-hidden="true" />
          )}
        </span>
        <span className="text-lg font-semibold text-gray-950 dark:text-white">{joinTitle}</span>
        <span className="mt-2 flex-1 text-sm leading-6 text-gray-600 dark:text-gray-300">
          {joinDescription}
        </span>
        <span className="mt-5 inline-flex items-center gap-2 text-sm font-semibold text-emerald-700 dark:text-emerald-300">
          {joinCta}
          <ArrowRightIcon
            className="h-4 w-4 transition group-hover:translate-x-0.5"
            aria-hidden="true"
          />
        </span>
      </button>
    </div>
  );

  if (actionData?.pending) {
    return (
      <main className="min-h-[calc(100vh-4rem)] bg-gray-50 px-4 py-12 dark:bg-gray-950">
        <div className="mx-auto max-w-lg rounded-xl border border-gray-200 bg-white p-8 text-center shadow-sm dark:border-gray-700 dark:bg-gray-900">
          <div className="mx-auto mb-5 flex h-12 w-12 items-center justify-center rounded-full bg-amber-50 dark:bg-amber-900/30">
            <ClockIcon className="h-6 w-6 text-amber-600 dark:text-amber-300" />
          </div>
          <h1 className="text-xl font-semibold text-gray-950 dark:text-white">
            {t('tenant.select.pending.title', undefined, '申请已提交')}
          </h1>
          <p className="mt-3 text-sm leading-6 text-gray-600 dark:text-gray-300">
            {actionData.message}
          </p>
          <button
            type="button"
            className="mt-7 w-full rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-blue-700 focus-visible:ring-4 focus-visible:ring-blue-100 focus-visible:outline-none"
            onClick={() => navigate('/login')}
          >
            {t('tenant.select.pending.backToLogin', undefined, '返回登录')}
          </button>
        </div>
      </main>
    );
  }

  return (
    <main className="min-h-[calc(100vh-4rem)] bg-gray-50 px-4 py-10 sm:px-6 lg:px-8 dark:bg-gray-950">
      <div className="mx-auto max-w-5xl">
        <header className="mb-8 text-center">
          <img
            src={branding.logoUrl}
            alt=""
            className="mx-auto mb-4 h-12 w-12 rounded-xl object-contain shadow-sm"
          />
          <p className="text-sm font-medium text-blue-600 dark:text-blue-300">
            {branding.productName}
          </p>
          <h1 className="mt-2 text-3xl font-semibold tracking-tight text-gray-950 sm:text-4xl dark:text-white">
            {selectedAction
              ? selectedAction === 'create'
                ? createTitle
                : joinTitle
              : hasExistingSpaces
                ? onboarding
                  ? t(
                      'tenant.select.existing.entityTitle',
                      { entityLabel: onboarding.entityLabel },
                      `选择${onboarding.entityLabel}`,
                    )
                  : t('tenant.select.existing.title', undefined, '选择工作空间')
                : (onboarding?.selectionTitle ??
                  t('tenant.select.choice.title', undefined, '选择你的开始方式'))}
          </h1>
          <p className="mx-auto mt-3 max-w-2xl text-sm leading-6 text-gray-600 sm:text-base dark:text-gray-300">
            {selectedAction
              ? selectedAction === 'create'
                ? createDescription
                : joinDescription
              : hasExistingSpaces
                ? allowTenantSelfService
                  ? t(
                      'tenant.select.existing.lead',
                      undefined,
                      '选择已有空间继续，或使用其他方式开始。',
                    )
                  : t('tenant.select.existing.only', undefined, '请选择一个已有空间继续。')
                : (onboarding?.selectionLead ??
                  t('tenant.select.choice.lead', undefined, '创建新组织，或加入已有组织。'))}
          </p>
        </header>

        <section className="rounded-2xl border border-gray-200 bg-white p-5 shadow-sm sm:p-8 dark:border-gray-800 dark:bg-gray-900">
          {dataUnavailable ? (
            <div
              data-testid="tenant-selection-load-error"
              className="mx-auto max-w-xl py-8 text-center"
            >
              <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-red-50 dark:bg-red-900/30">
                <ExclamationTriangleIcon className="h-6 w-6 text-red-600 dark:text-red-300" />
              </div>
              <h2 className="mt-5 text-lg font-semibold text-gray-950 dark:text-white">
                {t('tenant.select.loadError.title', undefined, '暂时无法加载入校信息')}
              </h2>
              <p className="mt-2 text-sm leading-6 text-gray-600 dark:text-gray-300">
                {t(
                  'tenant.select.loadError.desc',
                  undefined,
                  '服务连接暂时不可用，请重试。你的账号和已有学校不会受到影响。',
                )}
              </p>
              <button
                type="button"
                onClick={() => revalidator.revalidate()}
                className="mt-6 rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-blue-700 focus-visible:ring-4 focus-visible:ring-blue-100 focus-visible:outline-none disabled:opacity-50"
                disabled={revalidator.state === 'loading'}
              >
                {revalidator.state === 'loading'
                  ? t('tenant.select.retrying', undefined, '正在重试…')
                  : t('tenant.select.retry', undefined, '重新加载')}
              </button>
            </div>
          ) : !selectedAction && hasExistingSpaces ? (
            <div>
              <div className="space-y-3" data-testid="space-list">
                {spaces.map((space: UserSpace) => (
                  <Form method="post" key={space.tenantId}>
                    <input type="hidden" name="action" value="select" />
                    <input type="hidden" name="tenantId" value={space.tenantId} />
                    <button
                      type="submit"
                      data-testid={`space-${space.spaceType}-${space.tenantId}`}
                      className="flex w-full items-center gap-4 rounded-xl border border-gray-200 bg-white p-4 text-left transition hover:border-blue-300 hover:bg-blue-50/40 focus-visible:ring-4 focus-visible:ring-blue-100 focus-visible:outline-none dark:border-gray-700 dark:bg-gray-900 dark:hover:border-blue-600 dark:hover:bg-blue-950/20"
                    >
                      <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300">
                        {space.spaceType === 'platform' ? (
                          <CogIcon className="h-5 w-5" aria-hidden="true" />
                        ) : (
                          <BuildingOfficeIcon className="h-5 w-5" aria-hidden="true" />
                        )}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-base font-semibold text-gray-950 dark:text-white">
                          {space.tenantDisplayName || space.tenantName}
                        </span>
                        <span className="mt-1 block text-sm text-gray-500 dark:text-gray-400">
                          {space.spaceType === 'platform'
                            ? t('tenant.select.space.platform', undefined, '平台管理')
                            : (onboarding?.entityLabel ??
                              t('tenant.select.space.business', undefined, '业务空间'))}
                        </span>
                      </span>
                      <ArrowRightIcon className="h-5 w-5 text-gray-400" aria-hidden="true" />
                    </button>
                  </Form>
                ))}
              </div>
              {allowTenantSelfService && (
                <div className="mt-8 border-t border-gray-100 pt-7 dark:border-gray-800">
                  <h2 className="mb-4 text-sm font-semibold text-gray-900 dark:text-white">
                    {t('tenant.select.otherWays', undefined, '其他开始方式')}
                  </h2>
                  {actionChoices(true)}
                </div>
              )}
            </div>
          ) : !selectedAction && !allowTenantSelfService ? (
            <div
              data-testid="workspace-access-not-provisioned"
              className="mx-auto max-w-xl py-8 text-center"
            >
              <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-amber-50 dark:bg-amber-900/30">
                <ExclamationTriangleIcon className="h-6 w-6 text-amber-600 dark:text-amber-300" />
              </div>
              <h2 className="mt-5 text-lg font-semibold text-gray-950 dark:text-white">
                {t('tenant.select.managed.title', undefined, '当前环境由管理员统一开通')}
              </h2>
              <p className="mt-2 text-sm leading-6 text-gray-600 dark:text-gray-300">
                {t(
                  'tenant.select.managed.desc',
                  undefined,
                  '你的账号还没有可用空间，请联系平台管理员确认开通状态。',
                )}
              </p>
              <div className="mt-6 flex flex-wrap justify-center gap-3">
                <a
                  href={branding.supportUrl}
                  className="rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-blue-700 focus-visible:ring-4 focus-visible:ring-blue-100 focus-visible:outline-none"
                >
                  {t('tenant.select.getHelp', undefined, '获取帮助')}
                </a>
                <Link
                  to="/logout"
                  className="rounded-lg border border-gray-300 px-5 py-2.5 text-sm font-semibold text-gray-700 transition hover:bg-gray-50 focus-visible:ring-4 focus-visible:ring-gray-100 focus-visible:outline-none dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-800"
                >
                  {t('tenant.select.logout', undefined, '退出当前账号')}
                </Link>
              </div>
            </div>
          ) : !selectedAction ? (
            actionChoices()
          ) : (
            <div>
              <button
                type="button"
                className="mb-7 inline-flex items-center gap-2 rounded-md text-sm font-medium text-gray-600 transition hover:text-gray-950 focus-visible:ring-4 focus-visible:ring-blue-100 focus-visible:outline-none dark:text-gray-300 dark:hover:text-white"
                onClick={() => handleSwitchAction(null)}
              >
                <ArrowLeftIcon className="h-4 w-4" aria-hidden="true" />
                {t('tenant.select.back', undefined, '返回选择')}
              </button>

              {selectedAction === 'create' && (
                <Form
                  method="post"
                  noValidate
                  className="space-y-6"
                  data-testid="tenant-create-form"
                  onSubmit={(event) => {
                    if (!formData.name.trim()) {
                      event.preventDefault();
                      setErrors((current) => ({ ...current, name: `${onboarding?.entityLabel ?? '租户'}名称不能为空` }));
                    }
                  }}
                >
                  <input type="hidden" name="action" value="create" />
                  {onboarding?.entityLabel === '学校' && (
                    <input type="hidden" name="postCreateRedirect" value="/xy/setup" />
                  )}
                  <TenantFormFields
                    formData={formData}
                    errors={errors}
                    onChange={handleInputChange}
                    showLogo={false}
                    showWebsite={false}
                    variant="selection"
                    entityLabel={onboarding?.entityLabel}
                    fixedIndustry={onboarding ? 'education' : undefined}
                  />
                  {onboarding && (
                    <div className="flex items-start gap-3 rounded-lg bg-blue-50 p-4 text-sm leading-6 text-blue-900 dark:bg-blue-950/40 dark:text-blue-100">
                      <CheckCircleIcon className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
                      <span>
                        {t(
                          'tenant.select.create.creatorAdmin',
                          { entityLabel: onboarding.entityLabel },
                          `创建成功后，你将自动成为${onboarding.entityLabel}管理员。`,
                        )}
                      </span>
                    </div>
                  )}
                  {showError && (
                    <div
                      role="alert"
                      className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-950/30 dark:text-red-200"
                    >
                      {actionData.error}
                    </div>
                  )}
                  <button
                    type="submit"
                    disabled={isSubmitting}
                    className="w-full rounded-lg bg-blue-600 px-4 py-3 text-sm font-semibold text-white transition hover:bg-blue-700 focus-visible:ring-4 focus-visible:ring-blue-100 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {isSubmitting
                      ? t('tenant.select.create.submitting', undefined, '正在创建…')
                      : createTitle}
                  </button>
                </Form>
              )}

              {selectedAction === 'join' && onboarding?.joinChannel === 'wechat_mini' && (
                <div
                  data-testid="wechat-mini-join-guide"
                  className="grid gap-7 lg:grid-cols-[1fr_auto]"
                >
                  <div>
                    <ol className="space-y-4">
                      {onboarding.joinSteps?.map((step, index) => (
                        <li key={step} className="flex gap-4">
                          <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-sm font-semibold text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200">
                            {index + 1}
                          </span>
                          <span className="pt-0.5 text-sm leading-6 text-gray-700 dark:text-gray-200">
                            {step}
                          </span>
                        </li>
                      ))}
                    </ol>
                    <div className="mt-6 rounded-lg border border-emerald-200 bg-emerald-50 p-4 text-sm leading-6 text-emerald-900 dark:border-emerald-800 dark:bg-emerald-950/30 dark:text-emerald-100">
                      {t(
                        'tenant.select.mini.codeHint',
                        undefined,
                        '学校教师码只在微信小程序内填写，本页面不会收集。教师入校后可绑定已有班级，或创建新班级。',
                      )}
                    </div>
                  </div>
                  <div className="flex min-w-52 flex-col items-center justify-center rounded-xl bg-gray-50 p-6 text-center dark:bg-gray-800/70">
                    {onboarding.miniProgramQrUrl ? (
                      <img
                        src={onboarding.miniProgramQrUrl}
                        alt={`${onboarding.miniProgramName ?? branding.productName}微信小程序码`}
                        className="h-36 w-36 rounded-lg bg-white object-contain p-2"
                      />
                    ) : (
                      <DevicePhoneMobileIcon className="h-14 w-14 text-emerald-600 dark:text-emerald-300" />
                    )}
                    <p className="mt-4 text-sm font-semibold text-gray-900 dark:text-white">
                      {t('tenant.select.mini.open', undefined, '打开微信小程序')}
                    </p>
                    <p className="mt-1 text-xs leading-5 text-gray-500 dark:text-gray-400">
                      {t(
                        'tenant.select.mini.search',
                        { productName: onboarding.miniProgramName ?? branding.productName },
                        `搜索“${onboarding.miniProgramName ?? branding.productName}”并完成微信登录`,
                      )}
                    </p>
                  </div>
                </div>
              )}

              {selectedAction === 'join' && onboarding?.joinChannel !== 'wechat_mini' && (
                <Form method="post" className="space-y-6" data-testid="tenant-join-form">
                  <input type="hidden" name="action" value="join" />
                  <div className="space-y-2">
                    <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">
                      {t('tenant.select.join.inviteCodeLabel', undefined, '邀请码')} *
                    </label>
                    <input
                      ref={inviteCodeRef}
                      name="inviteCode"
                      type="text"
                      required
                      className="w-full rounded-lg border border-gray-300 bg-white px-4 py-3 text-gray-900 placeholder-gray-500 focus:border-blue-500 focus:ring-4 focus:ring-blue-100 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-white dark:placeholder-gray-400"
                      placeholder={t(
                        'tenant.select.join.inviteCodePlaceholder',
                        undefined,
                        '输入邀请码',
                      )}
                    />
                    <p className="text-sm text-gray-500 dark:text-gray-400">
                      {t(
                        'tenant.select.join.inviteCodeHint',
                        undefined,
                        '请输入组织管理员提供的邀请码。',
                      )}
                    </p>
                  </div>
                  {showError && (
                    <div
                      role="alert"
                      className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-950/30 dark:text-red-200"
                    >
                      {actionData.error}
                    </div>
                  )}
                  <button
                    type="submit"
                    disabled={isSubmitting}
                    className="w-full rounded-lg bg-emerald-600 px-4 py-3 text-sm font-semibold text-white transition hover:bg-emerald-700 focus-visible:ring-4 focus-visible:ring-emerald-100 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {isSubmitting
                      ? t('tenant.select.join.submitting', undefined, '正在提交…')
                      : joinCta}
                  </button>
                </Form>
              )}
            </div>
          )}
        </section>

        <footer className="mt-6 flex flex-wrap items-center justify-center gap-x-5 gap-y-2 text-xs text-gray-500 dark:text-gray-400">
          <a className="hover:text-gray-900 dark:hover:text-white" href={branding.supportUrl}>
            {t('tenant.select.getHelp', undefined, '获取帮助')}
          </a>
          <Link className="hover:text-gray-900 dark:hover:text-white" to="/logout">
            {t('tenant.select.logout', undefined, '退出当前账号')}
          </Link>
        </footer>
      </div>
    </main>
  );
}
