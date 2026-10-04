import { existsSync, lstatSync, readlinkSync } from 'node:fs';
import { join } from 'node:path';

export function goldenStackState(state, name) {
  if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(name)) throw new Error('Invalid stack runtime name');
  const current = join(state, 'runtimes', name, 'oss-stack');
  const legacy = join(state, 'golden', name);
  let currentInfo;
  try { currentInfo = lstatSync(current); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (currentInfo && (!currentInfo.isDirectory() || currentInfo.isSymbolicLink())) throw new Error('Invalid stable stack state');
  let old;
  try { old = lstatSync(legacy); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (old?.isSymbolicLink()) {
    if (readlinkSync(legacy) !== current) throw new Error('Legacy stack alias belongs to another location');
    return current;
  }
  if (old) {
    if (!old.isDirectory() || existsSync(current)) throw new Error('Ambiguous legacy and current stack state');
    return legacy;
  }
  return current;
}
