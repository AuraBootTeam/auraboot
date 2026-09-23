import { createRoutesStub } from 'react-router';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { COMMUNITY_BRANDING } from '~/config/branding';
import TenantSelection from '../TenantSelection';

const rootData = vi.hoisted(() => ({
  branding: null as any,
}));

const routeData = vi.hoisted(() => ({
  loader: {} as Record<string, unknown>,
}));

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router');
  return {
    ...actual,
    useLoaderData: () => routeData.loader,
    useActionData: () => undefined,
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
    t: (_key: string, params?: Record<string, unknown>, fallback?: string) => {
      let text = fallback ?? _key;
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
        joinSteps: ['打开微信小程序并登录', '输入学校教师码加入学校', '绑定已有班级或创建新班级'],
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
