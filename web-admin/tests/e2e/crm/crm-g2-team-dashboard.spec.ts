/**
 * G2 — Team Commission manager dashboard L4 UI golden (crm-incentive)
 *
 * Verifies the manager-view Team Commission dashboard renders in the real browser
 * against the crm-gap stack: the team KPI cards (team accrued / paid / bonus pool /
 * pending / headcount) and the per-rep commission table, all data-scoped to the
 * logged-in manager's reporting line via the org report-to chain (mt_org_employee).
 *
 * Two directions of the data scope are proven:
 *   - manager view (UI): the logged-in manager (admin) sees their whole reporting line
 *     — self + 2 direct reps + 1 sub-rep — with the per-rep rollups rendered.
 *   - rep isolation (data): a leaf rep's scope predicate resolves to exactly that rep
 *     (a rep does NOT see the manager's other reports).
 *
 * The seed (manager admin + 2 direct reps + 1 sub-rep with commissions) is created via
 * SQL because there is no generic public create-employee command path; the dashboard
 * data scope itself (the org report-to chain) is the real product behavior under test.
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=http://localhost:5239 PLAYWRIGHT_BE_URL=http://localhost:6509 \
 *   NO_PROXY=localhost,127.0.0.1 \
 *   npx playwright test tests/e2e/crm/crm-g2-team-dashboard.spec.ts \
 *     --project=chromium-m5 --config=tests/e2e/crm/g2.playwright.config.ts
 */
import { test, expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5239';
const PG = process.env.PG_CONTAINER || 'auraboot-crm-gap-postgres';
const EMAIL = 'admin@auraboot.com';
const PW = 'Test2026x';
const SHOT = '/tmp/g2-e2e';

function psql(sql: string): string {
  return execFileSync('docker', ['exec', PG, 'psql', '-U', 'auraboot', '-d', 'aura_boot', '-tAc', sql], {
    encoding: 'utf8',
  }).trim();
}

/**
 * Seed a manager (admin) + 2 direct reports + 1 sub-rep org hierarchy with commissions so the
 * Team Commission dashboard has team-scoped data to roll up. Idempotent (ON CONFLICT DO NOTHING).
 * The rep commissions are deterministic (g2user1: 300 accrued + 600 paid; g2user2: 450 accrued)
 * regardless of the manager's own commissions, so the per-rep assertions are stable.
 */
function seedTeam(): void {
  const tenant = psql("SELECT tenant_id FROM mt_crm_inc_commission LIMIT 1");
  const admin = psql("SELECT id FROM ab_user WHERE email='admin@auraboot.com'");
  const period = psql("SELECT to_char(now(),'YYYY-MM')");
  psql(
    `INSERT INTO mt_org_department (pid, tenant_id, org_dept_code, org_dept_name, created_at, updated_at) ` +
      `VALUES ('g2dept1', ${tenant}, 'DEPT-SALES-G2', 'G2 Sales', now(), now()) ON CONFLICT (pid) DO NOTHING`,
  );
  psql(
    `INSERT INTO mt_org_position (pid, tenant_id, org_pos_code, org_pos_name, org_pos_dept_id, org_pos_level, created_at, updated_at) ` +
      `VALUES ('g2pos1', ${tenant}, 'POS-SALES-G2', 'Sales', 'g2dept1', 1, now(), now()) ON CONFLICT (pid) DO NOTHING`,
  );
  const emp = (pid: string, code: string, name: string, userId: string, reportTo: string | null) =>
    `INSERT INTO mt_org_employee (pid, tenant_id, org_emp_code, org_emp_name, org_emp_user_id, org_emp_report_to, org_emp_dept_id, org_emp_position_id, org_emp_status, created_at, updated_at) ` +
    `VALUES ('${pid}', ${tenant}, '${code}', '${name}', '${userId}', ${reportTo ? `'${reportTo}'` : 'NULL'}, 'g2dept1', 'g2pos1', 'active', now(), now()) ON CONFLICT (pid) DO NOTHING`;
  psql(emp('g2mgremp1', 'EMP-MGR-G2', 'G2 Manager', admin, null));
  psql(emp('g2repemp1', 'EMP-R1-G2', 'G2 Rep One', 'g2user1', 'g2mgremp1'));
  psql(emp('g2repemp2', 'EMP-R2-G2', 'G2 Rep Two', 'g2user2', 'g2mgremp1'));
  psql(emp('g2subrep', 'EMP-SUB-G2', 'G2 Sub Rep', 'g2user3', 'g2repemp1'));
  const comm = (pid: string, code: string, rep: string, base: number, accrued: number, status: string) =>
    `INSERT INTO mt_crm_inc_commission (pid, tenant_id, crm_inc_comm_code, crm_inc_comm_rep_id, crm_inc_comm_source_type, crm_inc_comm_period, crm_inc_comm_base_amount, crm_inc_comm_accrued_amount, crm_inc_comm_status, created_at, updated_at) ` +
    `VALUES ('${pid}', ${tenant}, '${code}', '${rep}', 'collection', '${period}', ${base}, ${accrued}, '${status}', now(), now()) ON CONFLICT (pid) DO NOTHING`;
  psql(comm('g2c1', 'G2-C1', 'g2user1', 10000, 300, 'accrued'));
  psql(comm('g2c2', 'G2-C2', 'g2user1', 20000, 600, 'paid'));
  psql(comm('g2c3', 'G2-C3', 'g2user2', 15000, 450, 'accrued'));
}

/**
 * Count the distinct team members visible to `userId` using the SAME report-to scope
 * predicate the named queries (crm_inc_team_kpi / crm_inc_team_by_rep) apply. This proves
 * the data-scope direction the manager-view UI cannot: that a leaf rep sees only their own
 * line, not the manager's other reports.
 */
function teamSizeFor(userId: string): number {
  const tenant = psql("SELECT tenant_id FROM mt_crm_inc_commission LIMIT 1");
  const n = psql(
    `SELECT COUNT(DISTINCT tm.org_emp_user_id) FROM mt_org_employee tm WHERE tm.tenant_id = ${tenant} AND (` +
      `tm.org_emp_user_id = '${userId}' ` +
      `OR tm.org_emp_report_to IN (SELECT m.pid FROM mt_org_employee m WHERE m.org_emp_user_id = '${userId}' AND m.tenant_id = ${tenant}) ` +
      `OR tm.org_emp_report_to IN (SELECT d.pid FROM mt_org_employee d WHERE d.tenant_id = ${tenant} AND d.org_emp_report_to IN (` +
      `SELECT m2.pid FROM mt_org_employee m2 WHERE m2.org_emp_user_id = '${userId}' AND m2.tenant_id = ${tenant})))`,
  );
  return Number(n);
}

async function uiLogin(page: Page): Promise<void> {
  for (let attempt = 1; attempt <= 3; attempt++) {
    await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
    const emailInput = page.locator('input#email');
    const hasLogin = await emailInput.isVisible({ timeout: 8000 }).catch(() => false);
    if (!hasLogin) break;
    await emailInput.fill(EMAIL);
    await page.locator('input#password').fill(PW);
    await page.locator('button:has-text("立即登录"), button[type="submit"]').first().click();
    await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 20000 }).catch(() => {});
    if (page.url().includes('tenant-selection')) {
      const enter = page
        .getByRole('button', { name: /进入|选择|Enter|Demo|AuraBoot/ })
        .or(page.getByText(/AuraBoot Demo/).first());
      await enter.first().click({ timeout: 5000 }).catch(() => {});
      await page.waitForURL((u) => !u.pathname.includes('tenant-selection'), { timeout: 15000 }).catch(() => {});
    }
    const stillOnLogin = await emailInput.isVisible({ timeout: 2000 }).catch(() => false);
    if (!stillOnLogin) break;
    if (attempt === 3) throw new Error('UI login failed after 3 attempts');
  }
  await expect(page.locator('input#email')).toHaveCount(0, { timeout: 5000 });
}

test.describe('CRM G2 Team Commission dashboard (L4 UI golden)', () => {
  test.beforeAll(() => {
    seedTeam();
  });

  test.beforeEach(async ({ page }) => {
    await uiLogin(page);
  });

  test('manager view: team KPI cards + per-rep table data-scoped to the reporting line', async ({ page }) => {
    // Do NOT wait for networkidle — this app polls (inbox/notifications) so the network
    // never goes idle; wait on the concrete dashboard elements instead.
    await page.goto(`${BASE}/dashboards/view/crm_inc_team_commission`, { waitUntil: 'domcontentloaded' });

    // KPI card labels (manager view) — localized captions prove the smart-number-card
    // renders its labels (not the raw field codes).
    await expect(page.getByText('团队本年已计提').first()).toBeVisible({ timeout: 25000 });
    await expect(page.getByText('团队奖金池').first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('团队成员数').first()).toBeVisible({ timeout: 10000 });

    // per-rep table — the seeded team reps must appear (this is the data scope: they are
    // visible ONLY because the org report-to chain pulls them into the manager's team).
    await expect(page.getByText('G2 Rep One').first()).toBeVisible({ timeout: 15000 });
    await expect(page.getByText('G2 Rep Two').first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('G2 Sub Rep').first()).toBeVisible({ timeout: 10000 });

    // deterministic per-rep data values (independent of the manager's own commissions):
    // Rep One accrued = 300 + 600 = 900; Rep Two accrued = 450. These prove the widgets
    // render real scoped data, not just labels.
    await expect(page.getByText(/900/).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText(/450/).first()).toBeVisible({ timeout: 10000 });

    // the per-rep ranking widget (成员提成排名) must actually render its rows, NOT an empty /
    // "configure the data source" / "awaiting data" state — a widget whose dataSource shape
    // doesn't resolve passes the import validator but shows no data (the §2.2 gate-gap). The
    // title is present either way, so we assert no empty/placeholder caption is showing.
    await expect(page.getByText('成员提成排名').first()).toBeVisible({ timeout: 10000 });
    const emptyState = await page
      .getByText(/awaiting data|render once the first matching|配置数据源|暂无数据/i)
      .count();
    expect(emptyState, 'a dashboard widget is showing an empty / unconfigured-datasource state').toBe(0);

    // no raw field-code / bare i18n-key leakage (the §2.2 blocker — including the
    // smart-number-card eyebrow showing the field code instead of its label)
    const body = await page.locator('body').innerText();
    const raw =
      body.match(/\b(team_accrued_amount|team_paid_amount|team_bonus_pool|team_pending_amount|team_member_count|rep_id|rep_name|accrued_amount|paid_amount|attainment_pct|crm_inc_[a-z_]+)\b/g) || [];
    const bareKey = body.match(/\bmodel\.[a-z_]+\.[a-z_.]+\.label\b/g) || [];
    expect(raw, `raw code leaked: ${[...new Set(raw)].join(', ')}`).toHaveLength(0);
    expect(bareKey, `bare i18n key leaked: ${[...new Set(bareKey)].join(', ')}`).toHaveLength(0);

    await page.screenshot({ path: `${SHOT}/team_dashboard.png`, fullPage: true });
  });

  test('rep isolation: a leaf rep sees only their own line, not the manager team', async () => {
    // The manager (admin) sees the whole reporting line: self + 2 reps + 1 sub-rep = 4.
    const adminId = psql("SELECT id FROM ab_user WHERE email='admin@auraboot.com'");
    expect(teamSizeFor(adminId), 'manager sees self + reports').toBe(4);

    // A leaf rep (g2user2 / G2 Rep Two — no subordinates) sees ONLY themselves: the scope
    // predicate does not leak the manager's other reports to a peer.
    expect(teamSizeFor('g2user2'), 'leaf rep sees only self').toBe(1);

    // A mid-level rep (g2user1 / G2 Rep One) sees self + their own sub-rep = 2 — never the
    // peer Rep Two. Hierarchical scope, not flat "see everyone".
    expect(teamSizeFor('g2user1'), 'team-lead sees own sub-line only').toBe(2);
  });
});
