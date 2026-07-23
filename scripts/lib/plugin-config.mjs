/**
 * Read a plugin's config regardless of how it is laid out on disk.
 *
 * Two layouts are in use and they are not interchangeable:
 *   - OSS plugins:  config/commands.json   — one file, an array (or {commands:[]})
 *   - aura-quote:   config/commands/*.json  — one file per command, each a single
 *                                             object
 * fields.json / fields/, pages.json / pages/, bindings.json / bindings/ vary the
 * same way. A reader that knows only one layout silently returns nothing for the
 * other — the failure mode that made check-command-reachability report 19 false
 * positives against workflow-demo (directory pages) on its first run.
 *
 * So collect from both, always: `<base>.json` if present, plus every `.json`
 * under `<base>/` if that directory exists. A doc may itself be an array, a
 * single object, or `{ <key>: [...] }`; all three are flattened.
 */
import fs from 'node:fs';
import path from 'node:path';

function readJsonSafe(abs) {
  try { return JSON.parse(fs.readFileSync(abs, 'utf8')); }
  catch { return null; }
}

function collectFiles(dir, base) {
  const files = [];
  const single = path.join(dir, `${base}.json`);
  if (fs.existsSync(single)) files.push(single);
  const asDir = path.join(dir, base);
  if (fs.existsSync(asDir) && fs.statSync(asDir).isDirectory()) {
    const walk = (d) => {
      for (const name of fs.readdirSync(d)) {
        const abs = path.join(d, name);
        if (fs.statSync(abs).isDirectory()) walk(abs);
        else if (abs.endsWith('.json')) files.push(abs);
      }
    };
    walk(asDir);
  }
  return files;
}

/** Flatten one loaded doc into a list of entries. `key` is the wrapper name for
 *  the `{ commands: [...] }` form; a bare object (a shard) becomes `[obj]`. */
function entriesOf(doc, key) {
  if (Array.isArray(doc)) return doc;
  if (doc && typeof doc === 'object') {
    if (Array.isArray(doc[key])) return doc[key];
    return [doc]; // a single-object shard, e.g. one command per file
  }
  return [];
}

/** All entries of `<base>` for a plugin, across single-file and sharded layouts. */
export function loadConfigList(pluginDir, base, key = base) {
  const cfg = path.join(pluginDir, 'config');
  const out = [];
  for (const file of collectFiles(cfg, base)) {
    const doc = readJsonSafe(file);
    if (doc == null) continue;
    for (const e of entriesOf(doc, key)) out.push(e);
  }
  return out;
}

/** Raw text of every `<base>` file — for substring scans (e.g. which command
 *  codes a page references) that must not care about structure. */
export function loadConfigText(pluginDir, ...bases) {
  const cfg = path.join(pluginDir, 'config');
  const chunks = [];
  for (const base of bases) {
    for (const file of collectFiles(cfg, base)) {
      try { chunks.push(fs.readFileSync(file, 'utf8')); } catch { /* skip */ }
    }
  }
  return chunks.join('\n');
}
