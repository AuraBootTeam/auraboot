// OSS slot stub — advanced avatar rendering lives in ent-identity plugin.
// Enterprise overlay replaces this file.

export interface AvatarDescriptor {
  id: string;
  name?: string;
  avatarUrl?: string | null;
  color?: string;
}

export function getInitials(name?: string | null): string {
  if (!name) return '?';
  const trimmed = name.trim();
  if (!trimmed) return '?';
  const parts = trimmed.split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase();
  return (parts[0]![0]! + parts[parts.length - 1]![0]!).toUpperCase();
}

export function getAvatarColor(seed: string | undefined | null): string {
  if (!seed) return '#6366f1';
  let hash = 0;
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  const palette = ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#3b82f6', '#8b5cf6', '#ec4899', '#14b8a6'];
  return palette[Math.abs(hash) % palette.length]!;
}

export function describeAvatar(user: { id: string; name?: string; avatarUrl?: string | null }): AvatarDescriptor {
  return {
    id: user.id,
    name: user.name,
    avatarUrl: user.avatarUrl ?? null,
    color: getAvatarColor(user.id),
  };
}
