import React, { useEffect, useState } from 'react';
import { User, Users } from 'lucide-react';
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { fetchTeams } from '~/shared/services/teamService';

/**
 * OwnerCell — polymorphic record-owner display (list / detail) for the generic
 * `owner_type` + `owner_id` field pair. Renders 👤 <user name> or 👥 <team name>,
 * resolving the pid → name lazily with a module-level cache (teams fetched once;
 * users looked up by pid). Falls back to the raw id while resolving / on failure.
 */
const teamNameCache = new Map<string, string>();
const userNameCache = new Map<string, string>();
let teamsLoaded: Promise<void> | null = null;

function loadTeamsOnce(): Promise<void> {
  if (!teamsLoaded) {
    teamsLoaded = fetchTeams()
      .then((list) => {
        for (const tm of list) teamNameCache.set(tm.pid, tm.name);
      })
      .catch(() => {
        teamsLoaded = null; // allow retry on failure
      });
  }
  return teamsLoaded;
}

async function resolveUserName(pid: string): Promise<string | undefined> {
  if (userNameCache.has(pid)) return userNameCache.get(pid);
  try {
    const result = await get<Record<string, any>>(`/api/admin/users/${pid}`);
    if (ResultHelper.isSuccess(result) && result.data) {
      const d = result.data;
      const name = d.displayName || d.realName || d.username || d.email || pid;
      userNameCache.set(pid, name);
      return name;
    }
  } catch {
    // fall through to undefined → caller shows the raw id
  }
  return undefined;
}

export function OwnerCell({ ownerType, ownerId }: { ownerType?: string; ownerId?: string }) {
  const type = String(ownerType || '');
  const id = String(ownerId || '');
  const isTeam = type === 'team';
  const [name, setName] = useState<string | undefined>(
    isTeam ? teamNameCache.get(id) : userNameCache.get(id),
  );

  useEffect(() => {
    if (!id || !type) return;
    let cancelled = false;
    if (isTeam) {
      loadTeamsOnce().then(() => {
        if (!cancelled) setName(teamNameCache.get(id));
      });
    } else {
      resolveUserName(id).then((n) => {
        if (!cancelled && n) setName(n);
      });
    }
    return () => {
      cancelled = true;
    };
  }, [id, type, isTeam]);

  if (!id || !type) return <span className="text-text-3">-</span>;

  const Icon = isTeam ? Users : User;
  return (
    <span className="inline-flex items-center gap-1.5">
      <Icon className="text-text-3 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      <span className="text-text truncate">{name || id}</span>
    </span>
  );
}

export default OwnerCell;
