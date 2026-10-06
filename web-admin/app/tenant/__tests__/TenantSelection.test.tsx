import { createRoutesStub } from 'react-router';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { COMMUNITY_BRANDING } from '~/config/branding';
import TenantSelection from '../TenantSelection';
import TEXT from '../tenantSelectionText.i18n.json';
import { tenantSelectionText } from '../tenantSelectionText';

const rootData = vi.hoisted(() => ({
  branding: null as any,
  locale: 'zh-CN',
  translations: {} as Record<string, string>,
  actionData: undefined as any,
}));

const routeData = vi.hoisted(() => ({
  loader: {} as Record<string, unknown>,
}));

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router');
  return {
    ...actual,
    useLoaderData: () => routeData.loader,
    useActionData: () => rootData.actionData,
    useNavigate: () => vi.fn(),
    useNavigation: () => ({ state: 'idle' }),
    useRevalidator: () => ({ state: 'idle', revalidate: vi.fn() }),
  };
});

vi.mock('~/root-data', () => ({
  useRootLoaderData: () => rootData,
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: rootData.locale,
    t: (_key: string, params?: Record<string, unknown>, fallback?: string) => {
      let text = rootData.translations[_key] ?? fallback ?? _key;
      for (const [key, value] of Object.entries(params ?? {})) {
        text = text.split(`{${key}}`).join(String(value));
      }
      return text;
    },
  }),
}));

const selfServicePolicy = {
  deploymentMode: 'multi' as const,
  userRegistrationPolicy: 'closed' as const,
  tenantProvisioningPolicy: 'self_service' as const,
  partyCreationPolicy: 'approval_required' as const,
  partyInvitationEnabled: true,
  actorSwitchEnabled: true,
};

function renderSelection(loaderData: Record<string, unknown>) {
  routeData.loader = loaderData;
  const Stub = createRoutesStub([{ path: '/', Component: TenantSelection }]);
  return render(<Stub />);
}

describe('TenantSelection product onboarding', () => {
  beforeEach(() => {
    rootData.locale = 'zh-CN';
    rootData.translations = {};
    rootData.actionData = undefined;
    rootData.branding = {
      ...COMMUNITY_BRANDING,
      mode: 'commercial',
      productName: '蜂耘',
      tenantOnboarding: {
        entityLabel: '学校',
        industryCode: 'education',
        postCreateRedirect: '/xy/setup',
        selectionTitle: '选择你的开始方式',
        selectionLead: '创建学校，或使用学校教师码加入已有学校',
        createTitle: '创建学校',
        createDescription: '适合学校管理员，创建后自动获得学校管理权限。',
        createCta: '开始创建',
        joinTitle: '使用学校教师码加入学校',
        joinDescription: '适合班主任和任课老师，请前往微信小程序完成入校。',
        joinCta: '查看小程序入校步骤',
        joinChannel: 'wechat_mini',
        miniProgramName: '蜂耘',
        joinSteps: ['打开微信小程序并登录', '输入学校教师码加入学校', '绑定 PC 已建班级；新班级请在 PC 端创建'],
      },
    };
  });

  it('presents school creation and teacher-code onboarding as two actionable choices', async () => {
    const user = userEvent.setup();
    renderSelection({
      spaces: [],
      spacesStatus: 'loaded',
      accessPolicy: selfServicePolicy,
      accessPolicyStatus: 'loaded',
    });

    expect(await screen.findByRole('heading', { name: '选择你的开始方式' })).toBeVisible();
    expect(screen.getByRole('button', { name: /创建学校/ })).toBeVisible();
    const joinButton = screen.getByRole('button', { name: /使用学校教师码加入学校/ });
    expect(joinButton).toBeVisible();

    await user.click(joinButton);

    expect(screen.getByTestId('wechat-mini-join-guide')).toBeVisible();
    expect(screen.getByText('输入学校教师码加入学校')).toBeVisible();
    expect(screen.getByText(/学校教师码只在微信小程序内填写/)).toBeVisible();
    expect(screen.queryByRole('textbox', { name: /邀请码/ })).not.toBeInTheDocument();
  });

  it('uses school labels in the creation form without leaking interpolation placeholders', async () => {
    const user = userEvent.setup();
    renderSelection({
      spaces: [],
      spacesStatus: 'loaded',
      accessPolicy: selfServicePolicy,
      accessPolicyStatus: 'loaded',
    });

    await user.click(await screen.findByRole('button', { name: /创建学校/ }));

    expect(screen.getByRole('textbox', { name: '学校名称 *' })).toBeVisible();
    expect(screen.getByText('创建成功后，你将自动成为学校管理员。')).toBeVisible();
    expect(document.querySelector('input[name="postCreateRedirect"]')).toHaveValue('/xy/setup');
    expect(screen.queryByText(/\{entityLabel\}/)).not.toBeInTheDocument();
  });

  it('blocks an empty school name with a Chinese field-level error', async () => {
    const user = userEvent.setup();
    renderSelection({
      spaces: [],
      spacesStatus: 'loaded',
      accessPolicy: selfServicePolicy,
      accessPolicyStatus: 'loaded',
    });

    await user.click(await screen.findByRole('button', { name: /创建学校/ }));
    await user.click(screen.getByRole('button', { name: '创建学校' }));

    expect(screen.getByText('学校名称不能为空')).toBeVisible();
  });

  it('keeps an existing school primary while retaining self-service alternatives', async () => {
    renderSelection({
      spaces: [
        {
          tenantId: 1001,
          tenantName: 'fengyun-school',
          tenantDisplayName: '蜂耘产品验收学校',
          spaceType: 'business',
          roleCodes: ['tenant_admin'],
          isDefault: true,
        },
      ],
      spacesStatus: 'loaded',
      accessPolicy: selfServicePolicy,
      accessPolicyStatus: 'loaded',
    });

    expect(await screen.findByRole('heading', { name: '选择学校' })).toBeVisible();
    expect(screen.getByText('蜂耘产品验收学校')).toBeVisible();
    expect(screen.getByText('其他开始方式')).toBeVisible();
    expect(screen.getByRole('button', { name: /创建学校/ })).toBeVisible();
  });

  it('shows a recoverable managed-deployment state when self-service is explicitly disabled', async () => {
    renderSelection({
      spaces: [],
      spacesStatus: 'loaded',
      accessPolicy: {
        ...selfServicePolicy,
        deploymentMode: 'single',
        tenantProvisioningPolicy: 'disabled',
      },
      accessPolicyStatus: 'loaded',
    });

    expect(await screen.findByText('当前环境由管理员统一开通')).toBeVisible();
    expect(screen.getAllByRole('link', { name: '获取帮助' })[0]).toBeVisible();
    expect(screen.getAllByRole('link', { name: '退出当前账号' })[0]).toBeVisible();
    expect(
      screen.queryByText(/Workspace access has not been provisioned/i),
    ).not.toBeInTheDocument();
  });

  it('does not misreport a policy fetch failure as an administrator denial', async () => {
    renderSelection({
      spaces: [],
      spacesStatus: 'loaded',
      accessPolicy: selfServicePolicy,
      accessPolicyStatus: 'unavailable',
    });

    expect(await screen.findByText('暂时无法加载入校信息')).toBeVisible();
    expect(screen.getByRole('button', { name: '重新加载' })).toBeVisible();
    expect(screen.queryByText('当前环境由管理员统一开通')).not.toBeInTheDocument();
  });
});


describe('TenantSelection local translation defaults', () => {
  beforeEach(() => {
    rootData.locale = 'en-US';
    rootData.translations = {};
    rootData.actionData = undefined;
    rootData.branding = { ...COMMUNITY_BRANDING };
  });

  const loaded = {
    spaces: [], spacesStatus: 'loaded',
    accessPolicy: selfServicePolicy, accessPolicyStatus: 'loaded',
  };

  it('renders English choices while the remote catalog is empty', async () => {
    renderSelection(loaded);
    expect(await screen.findByRole('heading', { name: 'Choose how to get started' })).toBeVisible();
    expect(screen.getByRole('button', { name: /Create an organization/ })).toBeVisible();
    expect(screen.getByRole('button', { name: /Join an organization/ })).toBeVisible();
  });

  it('keeps a catalog translation ahead of the local default', async () => {
    rootData.translations['tenant.select.choice.title'] = 'Choose your team workspace';
    renderSelection(loaded);
    expect(await screen.findByRole('heading', { name: 'Choose your team workspace' })).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Choose how to get started' })).not.toBeInTheDocument();
  });

  it('localizes empty-name validation after the create action', async () => {
    const user = userEvent.setup();
    renderSelection(loaded);
    await user.click(await screen.findByRole('button', { name: /Create an organization/ }));
    await user.click(screen.getByRole('button', { name: 'Create an organization' }));
    expect(screen.getByText('Enter a name for the organization.')).toBeVisible();
    expect(screen.queryByText(/\{entityLabel\}/)).not.toBeInTheDocument();
  });

  it('distinguishes an unavailable policy from managed access in English', async () => {
    renderSelection({ ...loaded, accessPolicyStatus: 'unavailable' });
    expect(await screen.findByText('Unable to load onboarding information')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Reload' })).toBeVisible();
    expect(screen.queryByText('An administrator manages access to this environment')).not.toBeInTheDocument();
  });

  it('localizes managed access without offering self-service actions', async () => {
    renderSelection({ ...loaded, accessPolicy: { ...selfServicePolicy, tenantProvisioningPolicy: 'disabled' } });
    expect(await screen.findByText('An administrator manages access to this environment')).toBeVisible();
    expect(screen.getAllByRole('link', { name: 'Get help' })[0]).toBeVisible();
    expect(screen.queryByTestId('tenant-action-create')).not.toBeInTheDocument();
  });

  it.each(['tenant.select.policy.unavailable', 'tenant.select.policy.managed',
    'tenant.select.error.authRequired', 'tenant.select.error.requestFailed',
    'tenant.select.error.operationFailed', 'tenant.select.error.networkFailed'] as const)(
    'localizes the action error %s with the active locale', async (key) => {
      const user = userEvent.setup();
      rootData.actionData = { success: false, errorKey: key, error: TEXT[key]['zh-CN'], errorParams: { status: 503 } };
      renderSelection(loaded);
      await user.click(await screen.findByRole('button', { name: /Create an organization/ }));
      expect(await screen.findByRole('alert')).toHaveTextContent(tenantSelectionText(key, 'en-US', { status: 503 }));
      expect(screen.getByRole('alert')).not.toHaveTextContent(TEXT[key]['zh-CN']);
    },
  );

  it('interpolates the configured entity and mini-program names into English defaults', async () => {
    const user = userEvent.setup();
    rootData.branding.tenantOnboarding = {
      entityLabel: 'school', joinChannel: 'wechat_mini', miniProgramName: 'Example School',
      miniProgramQrUrl: '/example-school.png',
    };
    renderSelection({ ...loaded, spaces: [{ tenantId: 1, tenantName: 'School', tenantDisplayName: 'School', spaceType: 'business' }] });
    expect(await screen.findByRole('heading', { name: 'Choose a school' })).toBeVisible();
    await user.click(screen.getByRole('button', { name: /Join an organization/ }));
    expect(screen.getByRole('img', { name: 'Example School WeChat mini program QR code' })).toBeVisible();
    expect(screen.getByText('Search for “Example School” and sign in with WeChat')).toBeVisible();
    expect(screen.queryByText(/\{productName\}/)).not.toBeInTheDocument();
  });
});
