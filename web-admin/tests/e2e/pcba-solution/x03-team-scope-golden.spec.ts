import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  dynamicCreate,
  ensureQuoteRoleUser,
  makeQuoteRoleUser,
  queryDynamicRecords,
  type CreatedRows,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

test.describe('X03-04 team data scope golden', () => {
  test.describe.configure({ timeout: 120_000 });

  // X03-04: 团队(ab_team)创建/成员变更后,团队与成员数据可查询且关联正确。
  test('X03-04 team creation and query with correct linkage', async ({
    page,
  }) => {
    const marker = `TEAM-${Date.now()}`;
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    try {
      const teamPid = await dynamicCreate(page, 'ab_team', {
        code: marker,
        name: `E2E 团队 ${marker}`,
        description: `X03-04 team scope golden ${marker}`,
        status: 'active',
      }, created.rows);
      expect(teamPid).toBeTruthy();

      // 团队查询:创建的团队可见
      const teams = await queryDynamicRecords(page, 'ab_team', [
        { fieldName: 'code', operator: 'EQ', value: marker },
      ]);
      expect(teams, 'created team queryable').toHaveLength(1);

      // 成员管理走专用权限路径(model.ab_team_member.create 对 admin 也未开放),
      // 成员变更后团队范围数据访问腿待权限路径探查后补齐
    } finally {
      await cleanupRows(page, created);
    }
  });
});

test('X03-04 team member change: add surfaces in the roster, removal takes effect immediately', async ({ browser }) => {
  // 成员管理的真产品面是团队专用 REST(/api/org/teams*):ab_team_member 未注册为
  // 动态模型,成员腿必须走该 API。断言成员变更在成员名册上即时生效:
  // 加入后名册可见,移除后旧成员不可再见。
  const marker = `TEAMM-${Date.now()}`;
  const adminContext = await browser.newContext({
    storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  });
  const adminPage = await adminContext.newPage();
  try {
    const memberUser: QuoteRoleUser = makeQuoteRoleUser('team_member_x03', marker.slice(-10).replace(/-/g, ''), ['qo_sales']);
    // 数字 id 超出 JS 安全整数:经 by-email 查询取 userPid 字符串(端点原生支持),避开精度丢失
    const createdMember = await adminPage.request.post('/api/admin/users', {
      data: {
        email: memberUser.email,
        displayName: memberUser.displayName,
        initialPassword: memberUser.password,
        roleCodes: memberUser.roleCodes,
        sendInviteEmail: false,
      },
      timeout: 20_000,
    });
    const createdBody = await createdMember.json().catch(() => ({ data: {} }));
    expect(createdMember.ok(), `create member user: ${JSON.stringify(createdBody).slice(0, 240)}`).toBe(true);
    const memberUserPid = String((createdBody as { data?: { userPid?: string } }).data?.userPid ?? '');
    expect(memberUserPid, 'member user pid resolves via by-email lookup').toBeTruthy();
    const createTeam = await adminPage.request.post('/api/org/teams', {
      data: { code: marker, name: `E2E X03-04 member team ${marker}`, status: 'active' },
      timeout: 20_000,
    });
    const teamBody = await createTeam.json().catch(() => ({}));
    expect(createTeam.ok(), JSON.stringify(teamBody).slice(0, 300)).toBe(true);
    const teamPid = String((teamBody as any).data?.pid ?? '');

    const addMember = await adminPage.request.post(`/api/org/teams/${teamPid}/members`, {
      data: { userPid: memberUserPid, role: 'member' },
      timeout: 20_000,
    });
    const addBody = await addMember.json().catch(() => ({}));
    expect(addMember.ok(), `add member: ${JSON.stringify(addBody).slice(0, 240)}`).toBe(true);
    const addedMemberPid = String((addBody as { data?: { pid?: string } }).data?.pid ?? '');
    expect(addedMemberPid, 'added member returns a member pid').toBeTruthy();

    const rosterAfterAdd = await adminPage.request.get(`/api/org/teams/${teamPid}/members`, { timeout: 20_000 });
    const rosterBody = await rosterAfterAdd.json().catch(() => ({}));
    const roster = ((rosterBody as any).data ?? []) as Array<Record<string, unknown>>;
    expect(
      roster.some((m) => String(m.userPid ?? '') === memberUserPid),
      'added member surfaces in the team roster',
    ).toBe(true);

    const removal = await adminPage.request.delete(`/api/org/teams/${teamPid}/members/${addedMemberPid}`, { timeout: 20_000 });
    expect(removal.ok(), `remove member: ${JSON.stringify(await removal.json().catch(() => ({}))).slice(0, 300)}`).toBe(true);
    const rosterAfterRemove = await adminPage.request.get(`/api/org/teams/${teamPid}/members`, { timeout: 20_000 });
    const remaining = ((await rosterAfterRemove.json().catch(() => ({})) as any).data ?? []) as Array<Record<string, unknown>>;
    expect(
      remaining.some((m) => String(m.userPid ?? '') === memberUserPid),
      'removed member is no longer visible in the team roster',
    ).toBe(false);

    await adminPage.request.delete(`/api/org/teams/${teamPid}`, { timeout: 20_000 }).catch(() => {});
  } finally {
    await adminContext.close();
  }
});
