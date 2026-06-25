import { describe, expect, it } from 'vitest';

import { toNavigationResources } from '../../_shared/types';
import { RESOURCES } from '../resources';

describe('core organization team routing', () => {
  it('routes team CRUD through DSL bridge pages instead of the legacy TSX CRUD surface', () => {
    const teamList = RESOURCES.find((resource) => resource.key === 'org.teams');
    const teamNew = RESOURCES.find((resource) => resource.key === 'org.teams.new');
    const teamEdit = RESOURCES.find((resource) => resource.key === 'org.teams.edit');
    const teamDetail = RESOURCES.find((resource) => resource.key === 'org.team-detail');

    expect(teamList).toMatchObject({
      path: '/organization/teams',
      file: './plugins/core-organization/pages/organization/team-dsl-list.tsx',
    });
    expect(teamList?.dsl).toMatchObject({
      modelCode: 'ab_team',
      pageKey: 'ab_team_list',
    });

    expect(teamNew).toMatchObject({
      path: '/organization/teams/new',
      file: './plugins/core-organization/pages/organization/team-dsl-new.tsx',
    });
    expect(teamNew?.dsl).toMatchObject({
      modelCode: 'ab_team',
      pageKey: 'ab_team_form',
    });

    expect(teamEdit).toMatchObject({
      path: '/organization/teams/:teamPid/edit',
      file: './plugins/core-organization/pages/organization/team-dsl-edit.tsx',
    });
    expect(teamEdit?.dsl).toMatchObject({
      modelCode: 'ab_team',
      pageKey: 'ab_team_form',
    });

    expect(teamNew?.file).not.toEqual(teamEdit?.file);

    expect(teamDetail).toMatchObject({
      path: '/organization/teams/:teamPid',
      file: './plugins/core-organization/pages/organization/team-dsl-detail.tsx',
    });
    expect(teamDetail?.dsl).toMatchObject({
      modelCode: 'ab_team',
      pageKey: 'ab_team_detail',
    });
  });

  it('keeps DSL route metadata out of registered navigation resources', () => {
    const navTeamList = toNavigationResources(RESOURCES).find(
      (resource) => resource.key === 'org.teams',
    ) as Record<string, unknown> | undefined;

    expect(navTeamList).toBeTruthy();
    expect(navTeamList?.file).toBeUndefined();
    expect(navTeamList?.dsl).toBeUndefined();
  });
});
