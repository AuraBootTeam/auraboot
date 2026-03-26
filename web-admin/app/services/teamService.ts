import { get, post, put, del } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';

export interface TeamOption {
  id: string;
  name: string;
}

export interface Team {
  pid: string;
  code: string;
  name: string;
  description: string | null;
  leaderId: string | null;
  leaderName: string | null;
  status: string;
  memberCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface TeamMember {
  pid: string;
  userId: number;
  userName: string;
  userEmail: string;
  role: string;
  joinedAt: string;
}

export interface TeamCreateRequest {
  code: string;
  name: string;
  description?: string;
  leaderId?: string;
}

export interface TeamUpdateRequest {
  name?: string;
  description?: string;
  leaderId?: string;
  status?: string;
}

export interface TeamMemberAddRequest {
  userId: number;
  role?: string;
}

export async function fetchTeams(request?: Request): Promise<Team[]> {
  const result = await get<Team[]>('/api/org/teams', undefined, undefined, request);
  if (!ResultHelper.isSuccess(result) || !result.data) {
    throw new Error(result.desc || 'Failed to fetch teams');
  }
  return result.data;
}

export async function fetchTeam(pid: string, request?: Request): Promise<Team> {
  const result = await get<Team>(`/api/org/teams/${pid}`, undefined, undefined, request);
  if (!ResultHelper.isSuccess(result) || !result.data) {
    throw new Error(result.desc || 'Failed to fetch team');
  }
  return result.data;
}

export async function createTeam(data: TeamCreateRequest, request?: Request): Promise<Team> {
  const result = await post<Team>('/api/org/teams', data, undefined, request);
  if (!ResultHelper.isSuccess(result) || !result.data) {
    throw new Error(result.desc || 'Failed to create team');
  }
  return result.data;
}

export async function updateTeam(
  pid: string,
  data: TeamUpdateRequest,
  request?: Request,
): Promise<Team> {
  const result = await put<Team>(`/api/org/teams/${pid}`, data, undefined, request);
  if (!ResultHelper.isSuccess(result) || !result.data) {
    throw new Error(result.desc || 'Failed to update team');
  }
  return result.data;
}

export async function deleteTeam(pid: string, request?: Request): Promise<void> {
  const result = await del<boolean>(`/api/org/teams/${pid}`, undefined, undefined, request);
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.desc || 'Failed to delete team');
  }
}

export async function fetchTeamMembers(teamPid: string, request?: Request): Promise<TeamMember[]> {
  const result = await get<TeamMember[]>(
    `/api/org/teams/${teamPid}/members`,
    undefined,
    undefined,
    request,
  );
  if (!ResultHelper.isSuccess(result) || !result.data) {
    throw new Error(result.desc || 'Failed to fetch team members');
  }
  return result.data;
}

export async function addTeamMember(
  teamPid: string,
  data: TeamMemberAddRequest,
  request?: Request,
): Promise<TeamMember> {
  const result = await post<TeamMember>(
    `/api/org/teams/${teamPid}/members`,
    data,
    undefined,
    request,
  );
  if (!ResultHelper.isSuccess(result) || !result.data) {
    throw new Error(result.desc || 'Failed to add team member');
  }
  return result.data;
}

export async function removeTeamMember(
  teamPid: string,
  memberPid: string,
  request?: Request,
): Promise<void> {
  const result = await del<boolean>(
    `/api/org/teams/${teamPid}/members/${memberPid}`,
    undefined,
    undefined,
    request,
  );
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.desc || 'Failed to remove team member');
  }
}

export async function fetchCurrentUserTeams(request?: Request): Promise<TeamOption[]> {
  const result = await get<Team[]>('/api/org/teams/current-user', undefined, undefined, request);
  if (!ResultHelper.isSuccess(result) || !Array.isArray(result.data)) {
    throw new Error(result.desc || 'Failed to fetch current user teams');
  }
  return result.data.map((team) => ({ id: team.pid, name: team.name }));
}
