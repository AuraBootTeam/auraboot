import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ContextualAuthoringSurface } from '../ContextualAuthoringSurface';
import { authoringSurfaceText } from '../authoringSurfaceText';
import messages from '../authoringSurface.i18n.json';
import { loadAuthoringCapabilities, openAuthoringSession } from '../authoringService';
import { loadAuthoringRecoveryPolicy } from '../authoringRecoveryPolicy';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';
import type { AuthoringSession } from '../types';

// The locale contract under test: every rendered string must come from the
// authoringSurface catalog via useI18n().locale, never from a hardcoded literal.
const language = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: language.locale,
    t: (key: string, _params?: Record<string, unknown>, fallback?: string) => fallback ?? key,
  }),
}));

vi.mock('~/contexts/AuthContext', () => ({
  usePermission: () => true,
  useUser: () => ({ user: { id: '1' }, isAuthenticated: true }),
}));

vi.mock('../authoringService', () => ({
  openAuthoringSession: vi.fn(),
  loadAuthoringCapabilities: vi.fn(),
  loadAuthoringPermissionSnapshot: vi.fn(),
  loadAuthoringSession: vi.fn(),
  isAuthoringPermissionDeniedError: vi.fn(),
  applyAuthoringPatch: vi.fn(),
  prepareAuthoringSession: vi.fn(),
  renewAuthoringWriterLease: vi.fn(),
  submitAuthoringSession: vi.fn(),
  createAuthoringHandoff: vi.fn(),
  takeoverAuthoringWriterLease: vi.fn(),
  transitionAuthoringGovernance: vi.fn(),
}));

vi.mock('../authoringRecoveryPolicy', async () => {
  const actual = await vi.importActual<typeof import('../authoringRecoveryPolicy')>(
    '../authoringRecoveryPolicy',
  );
  return { ...actual, loadAuthoringRecoveryPolicy: vi.fn() };
});

// Authored page content is user-named data and must never be rewritten by the
// platform locale — only chrome and platform labels localize.
const schema: UnifiedSchema = {
  kind: 'list',
  version: '1.0.0',
  id: 'page-1',
  pageKey: 'orders_list',
  title: 'Orders',
  // No block title on purpose: the outline falls back to the localized
  // block-type label ("Table" / "表格").
  blocks: [{ id: 'table-1', blockType: 'table', columns: [{ field: 'name', label: 'Name' }] }],
  layout: { type: 'stack' },
};

function createAuthoringSession(overrides: Partial<AuthoringSession> = {}): AuthoringSession {
  return {
    sessionPid: 'session-1',
    changeSetPid: 'changeset-1',
    pagePid: 'page-1',
    ownerUserId: 1,
    changeSetStatus: 'DRAFT',
    workspaceMode: 'AUTHORING',
    state: 'ACTIVE',
    revision: 2,
    riskLevel: 'L0',
    route: 'INLINE',
    publishPolicy: 'DIRECT_ALLOWED',
    validationState: 'VALID',
    impactState: 'KNOWN',
    approvalState: 'NOT_REQUIRED',
    publishState: 'DRAFT',
    manifestChecksum: 'registry-1',
    snapshot: { ...schema, pid: 'page-1' },
    interactionContext: {},
    expiresAt: '2026-08-09T12:00:00Z',
    ...overrides,
  };
}

async function renderEnteredSurface() {
  render(
    <MemoryRouter initialEntries={['/orders']}>
      <ContextualAuthoringSurface schema={schema}>
        <div data-aura-block-id="table-1">runtime</div>
      </ContextualAuthoringSurface>
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
  await screen.findByTestId('contextual-authoring-surface');
}

describe('ContextualAuthoringSurface locale contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    const header = document.createElement('header');
    header.id = 'page-configuration-actions';
    document.body.appendChild(header);
    Element.prototype.scrollIntoView = vi.fn();
    vi.mocked(loadAuthoringRecoveryPolicy).mockResolvedValue('PERSISTENT');
    vi.mocked(openAuthoringSession).mockResolvedValue(createAuthoringSession());
    vi.mocked(loadAuthoringCapabilities).mockResolvedValue({
      checksum: 'registry-1',
      manifests: [
        {
          blockType: 'table',
          pluginCode: 'core.designer',
          pluginVersion: '0.1.0',
          manifestVersion: '1',
          checksum: 'table-1',
          properties: {
            '/title': {
              propertyPath: '/title',
              allowedOperations: ['REPLACE'],
              route: 'INLINE',
              risk: 'L1',
              effectTags: ['PRESENTATION'],
              reversibility: 'REVERSIBLE',
              protectedSemantic: false,
              rolePreviewRequired: false,
            },
            '/dataSource': {
              propertyPath: '/dataSource',
              allowedOperations: ['REPLACE'],
              route: 'HANDOFF_STUDIO',
              risk: 'L3',
              effectTags: ['DATA_BINDING'],
              reversibility: 'REVERSIBLE',
              protectedSemantic: false,
              rolePreviewRequired: false,
            },
            '/props/visible': {
              propertyPath: '/props/visible',
              allowedOperations: ['REPLACE'],
              route: 'GUIDED_INLINE',
              risk: 'L2',
              effectTags: ['PRESENTATION'],
              reversibility: 'REVERSIBLE',
              protectedSemantic: false,
              rolePreviewRequired: true,
            },
          },
        },
      ],
    });
  });

  afterEach(() => {
    document.getElementById('page-configuration-actions')?.remove();
    language.locale = 'zh-CN';
  });

  it('localizes zh-CN chrome, dock counters and inspector labels', async () => {
    language.locale = 'zh-CN';
    await renderEnteredSurface();

    expect(screen.getByText('配置模式')).toBeInTheDocument();
    expect(screen.getByText('安全编辑写入隔离 ChangeSet；不会写入业务数据')).toBeInTheDocument();
    expect(screen.getByText('选择')).toBeInTheDocument();
    expect(screen.getByText('交互预览')).toBeInTheDocument();
    expect(screen.getByText('新页面 / 菜单')).toBeInTheDocument();
    expect(screen.getByText('退出')).toBeInTheDocument();
    expect(screen.getByText('0 项未保存')).toBeInTheDocument();
    expect(screen.getByText('1 项草稿变更')).toBeInTheDocument();
    expect(screen.getByText('0 个校验错误')).toBeInTheDocument();
    expect(screen.getByText('提交评审')).toBeInTheDocument();
    expect(screen.getByText('差异')).toBeInTheDocument();
    expect(screen.getByText('保存')).toBeInTheDocument();
    expect(screen.getByText('实时预览')).toBeInTheDocument();
    expect(screen.getByTestId('authoring-outline')).toHaveTextContent('页面大纲');
    expect(screen.getByTestId('authoring-outline-table-1')).toHaveTextContent('表格');
    // Select the table block so the inspector shows its capability manifest.
    fireEvent.click(screen.getByTestId('authoring-outline-table-1'));
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('属性检查器');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('当前对象');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('表格');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('风险');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('可直发');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('可就地配置');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('标题');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('应用设计中心');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent(' · 保存后需角色复核');
    expect(screen.getByText('高级设置')).toBeInTheDocument();
    expect(screen.getByText('高级设置 ↗')).toBeInTheDocument();
  });

  it.each(['en', 'en-US', 'en-GB'])('localizes %s through the same catalog', async (locale) => {
    language.locale = locale;
    await renderEnteredSurface();

    expect(screen.getByText('Configuration mode')).toBeInTheDocument();
    expect(
      screen.getByText(
        'Safe edits write into an isolated ChangeSet; business data is never written',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('Select')).toBeInTheDocument();
    expect(screen.getByText('Interaction preview')).toBeInTheDocument();
    expect(screen.getByText('New page / menu')).toBeInTheDocument();
    expect(screen.getByText('Exit')).toBeInTheDocument();
    expect(screen.getByText('0 unsaved')).toBeInTheDocument();
    expect(screen.getByText('1 draft changes')).toBeInTheDocument();
    expect(screen.getByText('0 validation errors')).toBeInTheDocument();
    expect(screen.getByText('Submit for review')).toBeInTheDocument();
    expect(screen.getByText('Diff')).toBeInTheDocument();
    expect(screen.getByText('Save')).toBeInTheDocument();
    expect(screen.getByText('Live preview')).toBeInTheDocument();
    expect(screen.getByTestId('authoring-outline')).toHaveTextContent('Page outline');
    expect(screen.getByTestId('authoring-outline-table-1')).toHaveTextContent('Table');
    // Select the table block so the inspector shows its capability manifest.
    fireEvent.click(screen.getByTestId('authoring-outline-table-1'));
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('Property inspector');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('Current object');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('Table');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('Risk');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('Direct publish');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('Configurable in place');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent('Title');
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent(
      'Application design center',
    );
    expect(screen.getByTestId('authoring-inspector')).toHaveTextContent(
      ' · Role review required after save',
    );
    expect(screen.getByText('Advanced settings')).toBeInTheDocument();
    expect(screen.getByText('Advanced settings ↗')).toBeInTheDocument();
    // Authored data stays untouched by localization.
    expect(screen.getByTestId('authoring-outline')).toHaveTextContent('Orders');
    expect(screen.getByTestId('authoring-outline')).toHaveTextContent('Name');
  });

  it('falls back to zh-CN for an unmapped locale instead of rendering an empty label', async () => {
    language.locale = 'fr-FR';
    await renderEnteredSurface();

    expect(screen.getByText('配置模式')).toBeInTheDocument();
    expect(screen.getByText('0 项未保存')).toBeInTheDocument();
    expect(screen.queryByText('Configuration mode')).not.toBeInTheDocument();
  });

  it('switches chrome with locale on rerender while authored data and session stay stable', async () => {
    language.locale = 'zh-CN';
    const view = render(
      <MemoryRouter initialEntries={['/orders']}>
        <ContextualAuthoringSurface schema={schema}>
          <div data-aura-block-id="table-1">runtime</div>
        </ContextualAuthoringSurface>
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByTestId('contextual-authoring-enter'));
    await screen.findByTestId('contextual-authoring-surface');
    expect(screen.getByText('配置模式')).toBeInTheDocument();
    expect(openAuthoringSession).toHaveBeenCalledTimes(1);

    language.locale = 'en-US';
    view.rerender(
      <MemoryRouter initialEntries={['/orders']}>
        <ContextualAuthoringSurface schema={schema}>
          <div data-aura-block-id="table-1">runtime</div>
        </ContextualAuthoringSurface>
      </MemoryRouter>,
    );

    expect(screen.getByText('Configuration mode')).toBeInTheDocument();
    expect(screen.getByText('0 unsaved')).toBeInTheDocument();
    expect(screen.queryByText('配置模式')).not.toBeInTheDocument();
    // The authored page identity and the open session are not re-created by a locale flip.
    expect(screen.getByTestId('authoring-outline')).toHaveTextContent('Orders');
    expect(openAuthoringSession).toHaveBeenCalledTimes(1);
  });
});

describe('authoringSurface catalog contract', () => {
  it('pairs every catalog entry with both zh-CN and en text', () => {
    const entries = Object.entries(messages as Record<string, Record<string, string>>);
    expect(entries.length).toBeGreaterThan(100);
    for (const [key, entry] of entries) {
      expect(entry['zh-CN'], `zh-CN missing for ${key}`).toBeTruthy();
      expect(entry['en'], `en missing for ${key}`).toBeTruthy();
    }
  });

  it('resolves regional variants to en and unknown locales to zh-CN', () => {
    expect(authoringSurfaceText('configurePage', 'zh-CN')).toBe('配置此页');
    expect(authoringSurfaceText('configurePage', 'en')).toBe('Configure page');
    expect(authoringSurfaceText('configurePage', 'en-US')).toBe('Configure page');
    expect(authoringSurfaceText('configurePage', 'en-GB')).toBe('Configure page');
    expect(authoringSurfaceText('configurePage', 'fr-FR')).toBe('配置此页');
  });

  it('interpolates params and keeps unknown tokens verbatim', () => {
    expect(authoringSurfaceText('unsavedCount', 'zh-CN', { count: 3 })).toBe('3 项未保存');
    expect(authoringSurfaceText('unsavedCount', 'en-US', { count: 3 })).toBe('3 unsaved');
    expect(authoringSurfaceText('manifestMissing', 'en-US', { blockType: 'table' })).toBe(
      'No capability manifest found for table; this change cannot be saved',
    );
    expect(authoringSurfaceText('unsavedCount', 'en-US')).toBe('{count} unsaved');
  });

  // Counter-evidence: deleting a catalog entry (or ignoring the locale argument)
  // turns these fixed-key assertions red — '' from a missing entry never equals
  // the expected text, and a locale-blind resolver cannot return both sides.
  it('fails if an entry is removed or the locale is ignored', () => {
    expect(authoringSurfaceText('save', 'zh-CN')).toBe('保存');
    expect(authoringSurfaceText('save', 'en-US')).toBe('Save');
    expect(authoringSurfaceText('save', 'en-US')).not.toBe(authoringSurfaceText('save', 'zh-CN'));
    expect(
      authoringSurfaceText('conflictDetail', 'en-US', {
        baseRevision: 1,
        latestRevision: 2,
        pendingCount: 1,
      }),
    ).toBe(
      'Base r1 / Latest r2; 1 Mine change(s) kept. In-place configuration never overwrites Latest by refreshing directly.',
    );
  });
});
