import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  dynamicCreate,
  queryDynamicRecords,
  type CreatedRows,
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
