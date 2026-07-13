#!/usr/bin/env node
/**
 * check-no-secret-echo — refuse shell scripts that print a secret to stdout/stderr.
 *
 * Why this gate exists (2026-07-13): a golden runner reported whether an API key was
 * configured with
 *
 *     echo "DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY:-UNSET}"
 *
 * `${VAR:-default}` means "use VAR **if it has a value**, else the default" — so the moment
 * the key was configured, the banner printed the key itself, into the terminal and into any
 * captured log. The rule ("report presence, never the value") was already written down and I
 * believed I was following it; what defeated me was the shell expansion semantics. That class
 * of mistake is not fixable by remembering harder, so it is a gate.
 *
 * Say this instead:
 *
 *     [ -n "${DASHSCOPE_API_KEY:-}" ] && echo "DASHSCOPE_API_KEY: SET" || echo "... UNSET"
 *
 * Handing a secret *to a program* is fine and not flagged — `curl -H "Bearer $TOKEN"`,
 * `echo "$JSON" | jq ...` (captured), `X=$(... $TOKEN ...)`. The gate objects only when the
 * secret lands in an output stream a human or a CI log will collect.
 *
 * Exemptions live in check-no-secret-echo.allow.json, with a reason, never in a comment.
 */
import { readFileSync, existsSync } from 'node:fs';
import { execSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ALLOW_FILE = join(HERE, 'check-no-secret-echo.allow.json');

// A variable is secret-shaped if its name carries one of these words...
const SECRET_WORD = /(API_?KEY|SECRET_?KEY|ACCESS_?KEY|PRIVATE_?KEY|SECRET|TOKEN|PASSWORD|PASSWD|CREDENTIALS?)/;
// ...unless the name is plainly *about* the secret rather than being it.
const NOT_THE_SECRET = /(_STATE|_SET|_STATUS|_PRESENT|_MISSING|_FILE|_PATH|_NAME|_VAR|_ENV|_HINT|_LABEL|_RESPONSE|_JSON)$/;

const isSecretName = (n) => SECRET_WORD.test(n) && !NOT_THE_SECRET.test(n);

/**
 * The arguments of every echo/printf whose output actually reaches a stream.
 *
 * Skipped, because the bytes never surface:
 *   - `X=$(echo "$TOKEN" | jq ...)`  — captured by command substitution
 *   - `echo "$TOKEN" | python3 ...`  — piped into a consumer (except `tee`, which does print)
 *   - `... || echo "000"`            — the secret on such a line belongs to the *other* command
 *                                      (e.g. curl's -H), not to the echo; we only read the
 *                                      text that follows the echo itself.
 */
function printedSegments(line) {
  const segments = [];
  const re = /\b(echo|printf)\b([^|;&)]*)/g;
  let m;
  while ((m = re.exec(line)) !== null) {
    const args = m[2];
    const rest = line.slice(re.lastIndex);
    const pipedAway = /^\s*\|(?!\s*tee\b)/.test(rest); // piped into a consumer
    const captured = /\$\(\s*[^)]*$/.test(line.slice(0, m.index)); // inside $( ... )
    if (pipedAway || captured) continue;
    segments.push(args);
  }
  return segments;
}

function varsIn(text) {
  return [...text.matchAll(/\$\{?([A-Za-z_][A-Za-z0-9_]*)/g)].map((m) => m[1]);
}

function scan(file) {
  const findings = [];
  readFileSync(file, 'utf8')
    .split('\n')
    .forEach((raw, i) => {
      const line = raw.replace(/#.*$/, '');
      for (const args of printedSegments(line)) {
        for (const name of varsIn(args)) {
          if (!isSecretName(name)) continue;
          findings.push({
            file,
            line: i + 1,
            name,
            text: raw.trim(),
            // The exact trap that bit us: ${VAR:-x} / ${VAR:0:20} both print the value.
            expansionTrap: new RegExp(`\\$\\{${name}[:\\-=?]`).test(args),
          });
        }
      }
    });
  return findings;
}

const allow = existsSync(ALLOW_FILE) ? JSON.parse(readFileSync(ALLOW_FILE, 'utf8')) : { exemptions: [] };
const exempt = new Map(allow.exemptions.map((e) => [e.file, e.reason]));

const files = execSync("git ls-files -- '*.sh' '*.bash'", { encoding: 'utf8' }).split('\n').filter(Boolean);
const findings = files.flatMap(scan).filter((f) => !exempt.has(f.file));

if (findings.length === 0) {
  const n = exempt.size;
  console.log(
    `check-no-secret-echo: PASSED (${files.length} shell scripts, 0 secrets printed` +
      (n ? `, ${n} file(s) exempted by check-no-secret-echo.allow.json)` : ')')
  );
  process.exit(0);
}

console.error('check-no-secret-echo: FAILED — a secret reaches the output stream\n');
for (const f of findings) {
  console.error(`  ${f.file}:${f.line}`);
  console.error(`    ${f.text}`);
  console.error(
    f.expansionTrap
      ? `    ^ \${${f.name}:...} prints the VALUE whenever the variable is set — this is the leak.`
      : `    ^ $${f.name} is expanded into output.`
  );
  console.error(`    fix: [ -n "\${${f.name}:-}" ] && echo "${f.name}: SET" || echo "${f.name}: UNSET"\n`);
}
console.error(`${findings.length} finding(s). Report presence (SET/UNSET), never the value.`);
console.error('A deliberate exception goes in check-no-secret-echo.allow.json with a reason.');
process.exit(1);
