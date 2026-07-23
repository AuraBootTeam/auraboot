import path from 'node:path';

/**
 * Resolve the repo the test-system gates should run against.
 *
 * Default: the gate script's own repo (OSS). Override with `--repo <path>`
 * (absolute, or relative to cwd) or the AURA_TEST_SYSTEM_REPO env var, so one
 * copy of the gates can check a sibling repo (auraboot-enterprise / aura-quote /
 * plugins). When overridden, every path the gate touches — its scripts/*.json
 * config, the committed manifest, plugin roots, spec roots, and `git` invocations
 * — resolves against THAT repo, so each product owns its own configs, baselines,
 * and committed matrix while sharing one implementation.
 *
 * @param {string[]} argv  process.argv.slice(2)
 * @param {string} defaultRoot  the gate's own repo root (path.resolve(HERE, '..'))
 */
export function resolveRepoRoot(argv, defaultRoot) {
  const i = argv.indexOf('--repo');
  if (i >= 0 && argv[i + 1]) return path.resolve(process.cwd(), argv[i + 1]);
  if (process.env.AURA_TEST_SYSTEM_REPO) {
    return path.resolve(process.cwd(), process.env.AURA_TEST_SYSTEM_REPO);
  }
  return defaultRoot;
}
