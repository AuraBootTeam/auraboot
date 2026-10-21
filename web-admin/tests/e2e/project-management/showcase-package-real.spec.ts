import { test, expect, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { navigateToMenuByClick } from '../helpers';
import { loginDeliveryMember } from '../../helpers/delivery-session';

test.use({ storageState: { cookies: [], origins: [] } });
const evidence = process.env.AURA_EVIDENCE_DIR;
if (!evidence) throw Error('Bind the owned evidence round');
async function start(page: Page) {
  await navigateToMenuByClick(page, ['客户交付案例', '开始体验']);
  await expect(page.getByRole('main')).toContainText('把一次客户交付完整跑通');
}
async function shot(page: Page, id: string) {
  await mkdir(evidence!, { recursive: true });
  await page.screenshot({ path: join(evidence!, id + '.png'), fullPage: true });
}

test('PK complete business entry and secondary professional actions navigate through real DSL menus', async ({
  page,
}) => {
  await loginDeliveryMember(page);
  await start(page);
  await shot(page, 'PK01');
  const business: [string, string][] = [
    ['查看项目', '/p/tpm_project'],
    ['登记交付跟进', '/p/c/cde_delivery_workspace'],
    ['打开交付验收', '/p/tpm_acceptance'],
    ['处理我的审批', '/bpm/task-center'],
    ['交付核验', '/p/tdv_check'],
    ['查看交付经营看板', '/dashboards/view/tdo_delivery_operations'],
    ['查看超时与催办', '/bpm/sla-monitor'],
    ['打开复盘报告', '/p/c/report_management'],
  ];
  for (const [name, route] of business) {
    await page.getByRole('main').getByRole('button', { name, exact: true }).click();
    await expect(page).toHaveURL((url) => url.pathname === route);
    await expect(page.getByRole('main')).toBeVisible();
    await expect(page.getByRole('main')).not.toContainText('页面未找到');
    if (name === '查看项目') {
      await expect(page.getByRole('main')).toContainText('华辰制造 · 供应链协同平台');
      await shot(page, 'PK02');
    }
    if (name === '查看交付经营看板') {
      await expect(page.getByRole('main')).toContainText('交付行动清单');
      await expect(page.getByRole('main')).not.toContainText('加载中');
      await shot(page, 'PK04');
    }
    if (name === '打开复盘报告')
      await expect(page.getByRole('main')).toContainText('客户交付经营复盘');
    await start(page);
  }
  await page.getByRole('main').getByRole('button', { name: '专业配置与诊断', exact: true }).click();
  await page.getByRole('main').getByRole('button', { name: '返回开始体验', exact: true }).click();
  await expect(page.getByRole('main')).toContainText('把一次客户交付完整跑通');
  const professional: [string, string][] = [
    ['数据模型', '/meta/models'],
    ['页面设计', '/p/page_schema'],
    ['仪表盘设计', '/p/dashboard_management'],
    ['报告设计', '/p/c/report_management'],
    ['规则中心', '/decision-ops'],
    ['自动化', '/automations'],
    ['审批流程', '/p/bpm_process_management'],
    ['时限策略', '/p/sla_config'],
    ['插件与扩展', '/plugins'],
    ['外部集成', '/p/c/open_platform_publications'],
    ['故障定位', '/ops/troubleshooting'],
  ];
  for (const [name, route] of professional) {
    await page
      .getByRole('main')
      .getByRole('button', { name: '专业配置与诊断', exact: true })
      .click();
    await expect(page.getByRole('main')).toContainText('从业务结果追溯配置');
    if (name === '数据模型') await shot(page, 'PK03');
    await page.getByRole('main').getByRole('button', { name, exact: true }).click();
    await expect(page).toHaveURL((url) => url.pathname === route);
    await expect(page.getByRole('main')).not.toContainText('页面未找到');
    await start(page);
  }
  await page.reload();
  await expect(page.getByRole('main')).toContainText('把一次客户交付完整跑通');
});

test('PK viewer gets readable business entry without installation or professional mutation authority', async ({
  page,
}) => {
  await loginDeliveryMember(page, 'delivery-viewer@example.test');
  await start(page);
  await expect(
    page.getByRole('main').getByRole('button', { name: '查看项目', exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('main').getByRole('button', { name: '打开交付验收', exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('main').getByRole('button', { name: '专业配置与诊断', exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole('main').getByRole('button', { name: '处理我的审批', exact: true }),
  ).toHaveCount(0);
  await shot(page, 'PK05');
  const denied = await page.request.post('/api/plugins/import/import-directory-sync', {
    data: { path: 'not-authorized', conflictStrategy: 'ERROR' },
  });
  const body = await denied.json();
  expect(denied.status() === 403 || String(body.code) === '403').toBe(true);
  await page.getByRole('main').getByRole('button', { name: '查看项目', exact: true }).click();
  await expect(page.getByRole('main')).toContainText('华辰制造 · 供应链协同平台');
  await expect(page.getByTestId('toolbar-btn-create')).toHaveCount(0);
});
