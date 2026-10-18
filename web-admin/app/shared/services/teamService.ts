import {
  internalRequest,
  type InternalRequestInput,
  type InternalResponseData,
} from '~/shared/services/http-client/internal-api';
import type { Result } from '~/shared/services/http-client/types';
import { ResultHelper } from '~/utils/type';

/** Picker identity is a UI projection of the public team PID. */
export interface TeamOption {
  id: string;
  name: string;
}

export type Team = InternalResponseData<'get', '/api/org/teams/{pid}'>;
type TeamMemberWire = InternalResponseData<'post', '/api/org/teams/{teamPid}/members'>;
/** Legacy numeric identity is used only for matching existing picker selections. */
export type TeamMember = Omit<TeamMemberWire, 'userId'> & { userId: string };
export type TeamCreateRequest = InternalRequestInput<'post', '/api/org/teams'>['body'];
export type TeamUpdateRequest = InternalRequestInput<'put', '/api/org/teams/{pid}'>['body'];
export type TeamMemberAddRequest = InternalRequestInput<
  'post',
  '/api/org/teams/{teamPid}/members'
>['body'];

function data<T>(result: Result<T>, message: string): T {
  if (!ResultHelper.isSuccess(result)) throw new Error(result.desc || message);
  if (result.data == null) throw new Error(message);
  return result.data;
}

function success(result: Result<unknown>, message: string): void {
  if (!ResultHelper.isSuccess(result)) throw new Error(result.desc || message);
}

function memberForView(value: TeamMemberWire): TeamMember {
  if (
    typeof value.pid !== 'string' ||
    !value.pid ||
    !Number.isSafeInteger(value.userId) ||
    value.userId <= 0
  ) {
    throw new Error('Invalid team membership identity');
  }
  return { ...value, userId: String(value.userId) };
}

export async function fetchTeams(request?: Request): Promise<Team[]> {
  return data(await internalRequest('get', '/api/org/teams', { request }), 'Failed to fetch teams');
}

export async function fetchTeam(pid: string, request?: Request): Promise<Team> {
  return data(
    await internalRequest('get', '/api/org/teams/{pid}', { path: { pid }, request }),
    'Failed to fetch team',
  );
}

export async function createTeam(body: TeamCreateRequest, request?: Request): Promise<Team> {
  return data(
    await internalRequest('post', '/api/org/teams', { body, request }),
    'Failed to create team',
  );
}

export async function updateTeam(
  pid: string,
  body: TeamUpdateRequest,
  request?: Request,
): Promise<Team> {
  return data(
    await internalRequest('put', '/api/org/teams/{pid}', { path: { pid }, body, request }),
    'Failed to update team',
  );
}

export async function deleteTeam(pid: string, request?: Request): Promise<void> {
  success(
    await internalRequest('delete', '/api/org/teams/{pid}', { path: { pid }, request }),
    'Failed to delete team',
  );
}

export async function fetchTeamMembers(teamPid: string, request?: Request): Promise<TeamMember[]> {
  return data(
    await internalRequest('get', '/api/org/teams/{teamPid}/members', {
      path: { teamPid },
      request,
    }),
    'Failed to fetch team members',
  ).map(memberForView);
}

export async function addTeamMember(
  teamPid: string,
  body: TeamMemberAddRequest,
  request?: Request,
): Promise<TeamMember> {
  return memberForView(
    data(
      await internalRequest('post', '/api/org/teams/{teamPid}/members', {
        path: { teamPid },
        body,
        request,
      }),
      'Failed to add team member',
    ),
  );
}

/** memberPid is the team-membership row PID returned by this team's members API. */
export async function removeTeamMember(
  teamPid: string,
  memberPid: string,
  request?: Request,
): Promise<void> {
  success(
    await internalRequest('delete', '/api/org/teams/{teamPid}/members/{memberPid}', {
      path: { teamPid, memberPid },
      request,
    }),
    'Failed to remove team member',
  );
}

export async function fetchCurrentUserTeams(request?: Request): Promise<TeamOption[]> {
  const teams = data(
    await internalRequest('get', '/api/org/teams/current-user', { request }),
    'Failed to fetch current user teams',
  );
  if (!Array.isArray(teams)) throw new Error('Failed to fetch current user teams');
  return teams.map((team) => ({ id: team.pid, name: team.name }));
}
