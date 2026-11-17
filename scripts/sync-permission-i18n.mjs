#!/usr/bin/env node
/**
 * Import missing role-editor translations without reimporting permissions or roles.
 * Preview: node scripts/sync-permission-i18n.mjs --base-url http://localhost:6619 --token-file /private/admin.token
 * Apply: append --apply. Existing tenant translations are never overwritten.
 */
import fs from "node:fs";
import { fileURLToPath } from "node:url";

const options = {};
for (let i = 2; i < process.argv.length; i++) {
  const arg = process.argv[i];
  if (arg === "--apply") options.apply = true;
  else if (["--base-url", "--token-file"].includes(arg))
    options[arg.slice(2)] = process.argv[++i];
  else throw new Error(`Unknown argument: ${arg}`);
}
if (!options["base-url"] || !options["token-file"])
  throw new Error("--base-url and --token-file are required");
const base = new URL(options["base-url"]);
if (
  !["http:", "https:"].includes(base.protocol) ||
  base.username ||
  base.password ||
  base.pathname !== "/" ||
  base.search ||
  base.hash
)
  throw new Error("Use an explicit API origin without credentials or path");
const token = fs.readFileSync(options["token-file"], "utf8").trim();
if (!token) throw new Error("Token file is empty");
const entries = JSON.parse(
  fs.readFileSync(
    fileURLToPath(
      new URL("../plugins/platform-admin/config/i18n.json", import.meta.url),
    ),
    "utf8",
  ),
);
const prefixes = [
  "roleNavigation.",
  "rbacExperience.",
  "roleChanges.",
  "roleFields.",
];
const expected = entries
  .filter((row) => prefixes.some((prefix) => row.key.startsWith(prefix)))
  .flatMap((row) =>
    ["zh-CN", "en-US"].map((lang) => {
      if (!row[lang])
        throw new Error(`Missing ${lang} translation: ${row.key}`);
      return { key: row.key, lang, value: row[lang], source: "import" };
    }),
  );
async function api(path, body) {
  const response = await fetch(new URL(path, base), {
    method: body === undefined ? "GET" : "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(20000),
  });
  const result = await response.json();
  if (!response.ok || String(result.code) !== "0")
    throw new Error(
      `i18n API rejected ${path.split("?")[0]}: HTTP ${response.status}, code ${result.code}`,
    );
  return result.data;
}
const missing = [];
for (const row of expected) {
  const found = await api(
    `/api/admin/i18n/resources/by-key?${new URLSearchParams({ key: row.key, lang: row.lang })}`,
  );
  if (!found) missing.push(row);
}
if (options.apply && missing.length)
  await api("/api/admin/i18n/resources/batch-upsert", missing);
if (options.apply) {
  for (const lang of ["zh-CN", "en-US"]) {
    const pack = await api(`/api/i18n/${lang}`);
    for (const row of expected.filter((row) => row.lang === lang)) {
      if (!(row.key in pack))
        throw new Error(
          `Translation absent from runtime pack: ${row.key}/${lang}`,
        );
    }
  }
}
console.log(
  JSON.stringify(
    {
      target: base.origin,
      mode: options.apply ? "apply" : "preview",
      expected: expected.length,
      missing: missing.length,
      inserted: options.apply ? missing.length : 0,
      existingTranslationsPreserved: expected.length - missing.length,
    },
    null,
    2,
  ),
);
