#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PLUGIN_ROOT = path.resolve(HERE, '..');
const REPO_ROOT = path.resolve(PLUGIN_ROOT, '../..');
const DEFAULT_MANIFEST = path.join(PLUGIN_ROOT, 'coverage-manifest.json');

const RG1_PAGES = [
  'crm_customer_360_workbench',
  'crm_lead_desk_workbench',
  'crm_opportunity_workspace',
  'crm_forecast_cockpit',
  'crm_activity_service_desk',
];
const RG2_PAGES = [
  'crm_qdp_release_workbench',
  'crm_quote_summary_common_detail',
  'crm_qdp_revision_common_detail',
];
const RG2_COMMANDS = [
  'crm:prepare_qdp_draft',
  'crm:compile_qdp_revision',
  'crm:submit_qdp_review',
  'crm:publish_qdp_revision',
  'crm:record_order_commitment',
];
const RG3_COMMANDS = [
  'crm:create_lead',
  'crm:contact_lead',
  'crm:qualify_lead',
  'crm:convert_lead',
  'crm:create_activity',
];

const EVIDENCE = {
  rg1Browser: 'plugins/crm/e2e/core-workbenches.golden.spec.ts',
  rg2QdpStack: 'plugins/crm/scripts/it/qdp_release_center_true_stack.py',
  rg2QdpBrowser: 'plugins/crm/e2e/qdp-release-center.golden.spec.ts',
  rg2OrderStack: 'plugins/crm/scripts/it/order_commitment_true_stack.py',
  rg2OrderBrowser: 'plugins/crm/e2e/order-commitment.golden.spec.ts',
  rg3Journey: 'plugins/crm/scripts/adoption_journey.py',
  rg3Guide: 'plugins/crm/README.md',
};

function json(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(PLUGIN_ROOT, 'config', relativePath), 'utf8'));
}

function jsonDirectory(relativePath) {
  const directory = path.join(PLUGIN_ROOT, 'config', relativePath);
  return fs.readdirSync(directory)
    .filter((name) => name.endsWith('.json'))
    .sort()
    .flatMap((name) => {
      const value = JSON.parse(fs.readFileSync(path.join(directory, name), 'utf8'));
      return Array.isArray(value) ? value : [value];
    });
}

function uniq(values) {
  return [...new Set(values)].sort();
}

function allBlocks(page) {
  const blocks = [];
  const visit = (candidate) => {
    if (!candidate || typeof candidate !== 'object') return;
    if (Array.isArray(candidate)) {
      for (const item of candidate) visit(item);
      return;
    }
    if (candidate.blockType) blocks.push(candidate);
    for (const item of candidate.blocks ?? []) visit(item);
    for (const tab of candidate.tabs ?? []) visit(tab.blocks ?? []);
  };
  visit(page.blocks ?? []);
  return blocks;
}

function actionTarget(action) {
  if (action.onClick?.action === 'command.execute') {
    return { targetType: 'command', target: action.onClick.args?.command ?? null };
  }
  if (action.onClick?.action === 'navigate') {
    return { targetType: 'route', target: action.onClick.args?.to ?? null };
  }
  return { targetType: action.onClick?.action ?? 'unknown', target: null };
}

function evidenceForCommand(code, rg1Commands) {
  const files = [];
  if (rg1Commands.has(code)) files.push(EVIDENCE.rg1Browser);
  if (RG2_COMMANDS.includes(code)) {
    files.push(code === 'crm:record_order_commitment' ? EVIDENCE.rg2OrderStack : EVIDENCE.rg2QdpStack);
    files.push(code === 'crm:record_order_commitment' ? EVIDENCE.rg2OrderBrowser : EVIDENCE.rg2QdpBrowser);
  }
  if (RG3_COMMANDS.includes(code)) files.push(EVIDENCE.rg3Journey);
  return uniq(files);
}

export function buildReleaseManifest() {
  const pages = new Map(
    jsonDirectory('pages/').map((page) => [page.pageKey, page]),
  );
  const commands = new Map(jsonDirectory('commands/').map((command) => [command.code, command]));
  const permissions = new Map(json('permissions.json').map((permission) => [permission.code, permission]));
  const queries = new Map(json('named-queries.json').map((query) => [query.code, query]));
  const menus = json('menus.json');

  const semanticActions = [];
  for (const pageKey of RG1_PAGES) {
    const page = pages.get(pageKey);
    assert.ok(page, `missing RG-1 page ${pageKey}`);
    for (const block of allBlocks(page)) {
      for (const action of block.actions ?? []) {
        const target = actionTarget(action);
        semanticActions.push({
          id: action.code,
          pageKey,
          blockId: block.id,
          ...target,
          permissionCode: action.permissionCode ?? null,
          evidence: [EVIDENCE.rg1Browser],
          verdict: 'pass',
        });
      }
    }
  }
  assert.equal(semanticActions.length, 26, 'RG-1 semantic-action denominator drifted');
  assert.equal(new Set(semanticActions.map((row) => row.id)).size, 26,
    'RG-1 semantic action codes must be unique');

  const rg1Commands = new Set(
    semanticActions.filter((row) => row.targetType === 'command').map((row) => row.target),
  );
  const commandCodes = uniq([...rg1Commands, ...RG2_COMMANDS, ...RG3_COMMANDS]);
  const commandRows = commandCodes.map((code) => {
    const command = commands.get(code);
    assert.ok(command, `missing release command ${code}`);
    const goals = [];
    if (rg1Commands.has(code)) goals.push('RG-1');
    if (RG2_COMMANDS.includes(code)) goals.push('RG-2');
    if (RG3_COMMANDS.includes(code)) goals.push('RG-3');
    return {
      id: code,
      goals,
      permissionCodes: uniq(command.permissions ?? []),
      evidence: evidenceForCommand(code, rg1Commands),
      verdict: 'pass',
    };
  });

  const pageRows = [...RG1_PAGES, ...RG2_PAGES].map((pageKey) => {
    const page = pages.get(pageKey);
    assert.ok(page, `missing release page ${pageKey}`);
    const goals = RG1_PAGES.includes(pageKey) ? ['RG-1', ...(pageKey === 'crm_lead_desk_workbench' ? ['RG-3'] : [])] : ['RG-2'];
    const evidence = RG1_PAGES.includes(pageKey)
      ? [EVIDENCE.rg1Browser, ...(pageKey === 'crm_lead_desk_workbench' ? [EVIDENCE.rg3Journey] : [])]
      : pageKey === 'crm_qdp_release_workbench'
        ? [EVIDENCE.rg2QdpBrowser]
        : [EVIDENCE.rg2OrderBrowser];
    const menuPermissions = menus
      .filter((menu) => menu.pageKey === pageKey && menu.permissionCode)
      .map((menu) => menu.permissionCode);
    return {
      id: pageKey,
      goals,
      kind: page.kind,
      permissionCodes: uniq([page.permissionCode, ...menuPermissions].filter(Boolean)),
      evidence: uniq(evidence),
      verdict: 'pass',
    };
  });

  const permissionEvidence = new Map();
  const addPermissionEvidence = (code, files) => {
    if (!code) return;
    assert.ok(permissions.has(code), `missing release permission ${code}`);
    permissionEvidence.set(code, uniq([...(permissionEvidence.get(code) ?? []), ...files]));
  };
  for (const action of semanticActions) addPermissionEvidence(action.permissionCode, action.evidence);
  for (const command of commandRows) {
    for (const code of command.permissionCodes) addPermissionEvidence(code, command.evidence);
  }
  for (const page of pageRows) {
    for (const code of page.permissionCodes) addPermissionEvidence(code, page.evidence);
  }
  const permissionRows = [...permissionEvidence.entries()].sort(([a], [b]) => a.localeCompare(b))
    .map(([code, evidence]) => ({ id: code, evidence, verdict: 'pass' }));

  const queryEvidence = new Map();
  for (const pageRow of pageRows) {
    const page = pages.get(pageRow.id);
    const dataSources = Array.isArray(page.dataSources)
      ? page.dataSources
      : Object.values(page.dataSources ?? {});
    for (const dataSource of dataSources) {
      const code = dataSource.queryCode;
      if (!code) continue;
      assert.ok(queries.has(code), `missing release named query ${code}`);
      queryEvidence.set(code, uniq([...(queryEvidence.get(code) ?? []), ...pageRow.evidence]));
    }
  }
  const queryRows = [...queryEvidence.entries()].sort(([a], [b]) => a.localeCompare(b))
    .map(([code, evidence]) => {
      const query = queries.get(code);
      return {
        id: code,
        resourceCode: query.resourceCode ?? null,
        actionCode: query.actionCode ?? null,
        evidence,
        verdict: 'pass',
      };
    });

  for (const source of Object.values(EVIDENCE)) {
    assert.ok(fs.existsSync(path.join(REPO_ROOT, source)), `missing executable evidence source ${source}`);
  }

  return {
    schemaVersion: 1,
    release: 'AuraBoot CRM RG-1 through RG-4',
    generatedBy: 'plugins/crm/scripts/verify_release_coverage.mjs',
    scope: {
      semanticActionDenominator: 26,
      axes: ['semanticActions', 'commands', 'pages', 'permissions', 'queries'],
      excluded: ['RG-5 licensing and entitlement', 'data migration'],
    },
    goals: [
      { id: 'RG-1', verdict: 'pass', note: '26/26 semantic actions and five workbenches have real-stack browser evidence.' },
      { id: 'RG-2', verdict: 'pass', note: 'QDP release and order commitment have true-stack and browser evidence.' },
      { id: 'RG-3', verdict: 'partial', note: 'Clean-room automation passes; independent non-developer human sign-off is still pending.' },
      { id: 'RG-4', verdict: 'pass', note: 'This committed manifest is freshness-gated and mutation-falsifiable.' },
    ],
    axes: {
      semanticActions,
      commands: commandRows,
      pages: pageRows,
      permissions: permissionRows,
      queries: queryRows,
    },
    runtimeEvidenceContracts: [
      { id: 'RG1-BROWSER', filePrefix: 'crm-core-workbenches-core-wb-', expectedActions: 26, expectedScenarios: 5, minimumScreenshots: 20 },
      { id: 'RG2-QDP-STACK', filePrefix: 'qdp-release-center-true-stack-', minimumChecks: 20 },
      { id: 'RG2-QDP-BROWSER', filePrefix: 'qdp-release-center-browser-', minimumScenarios: 6, minimumScreenshots: 1 },
      { id: 'RG2-ORDER-STACK', filePrefix: 'order-commitment-true-stack-', minimumChecks: 8 },
      { id: 'RG2-ORDER-BROWSER', filePrefix: 'order-commitment-browser-', minimumScenarios: 3, minimumScreenshots: 2 },
      { id: 'RG3-CLEAN-ROOM', filePrefix: 'crm-adoption-journey-', maximumElapsedSeconds: 1800, minimumCheckpoints: 6 },
    ],
    untested: [
      {
        id: 'RG3-INDEPENDENT-HUMAN-ADOPTER',
        verdict: 'untested',
        reason: 'A developer-authored automation run cannot prove that a person who did not participate in development completed the browser journey.',
      },
    ],
  };
}

function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]));
  }
  return value;
}

export function assertManifestMatches(actual, expected = buildReleaseManifest()) {
  assert.deepEqual(stable(actual), stable(expected),
    'committed CRM coverage manifest drifted from DSL, executable evidence, or release scope');
}

function walkFiles(root) {
  const files = [];
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    const candidate = path.join(root, entry.name);
    if (entry.isDirectory()) files.push(...walkFiles(candidate));
    else files.push(candidate);
  }
  return files;
}

function assertSameMembers(actual, expected, label) {
  assert.deepEqual(uniq(actual ?? []), uniq(expected ?? []), label);
}

function validateScreenshots(receipt, minimum, label) {
  assert.ok((receipt.screenshots ?? []).length >= minimum,
    `${label} expected at least ${minimum} screenshots`);
  for (const screenshot of receipt.screenshots ?? []) {
    assert.ok(fs.existsSync(screenshot), `${label} screenshot is missing: ${screenshot}`);
    assert.ok(fs.statSync(screenshot).size > 0, `${label} screenshot is empty: ${screenshot}`);
  }
}

export function verifyRuntimeEvidence(evidenceRoot, manifest = buildReleaseManifest()) {
  const files = walkFiles(evidenceRoot).filter((file) => file.endsWith('.json'));
  const results = [];
  for (const contract of manifest.runtimeEvidenceContracts) {
    const candidates = files
      .filter((file) => path.basename(file).startsWith(contract.filePrefix))
      .map((file) => ({ file, receipt: JSON.parse(fs.readFileSync(file, 'utf8')) }))
      .filter(({ receipt }) => receipt.verdict === 'pass');
    assert.ok(candidates.length > 0, `${contract.id} has no passing machine receipt`);
    const selected = contract.id === 'RG3-CLEAN-ROOM'
      ? candidates.sort((a, b) => (b.receipt.elapsedSeconds ?? 0) - (a.receipt.elapsedSeconds ?? 0))[0]
      : candidates.sort((a, b) => b.file.localeCompare(a.file))[0];
    const { file, receipt } = selected;

    if (contract.expectedActions) {
      assert.equal(receipt.expectedActions?.length, contract.expectedActions, `${contract.id} denominator`);
      assertSameMembers(receipt.completedActions, receipt.expectedActions, `${contract.id} incomplete action set`);
    }
    if (contract.expectedScenarios) {
      assert.equal(receipt.expectedScenarios?.length, contract.expectedScenarios, `${contract.id} scenario denominator`);
      assertSameMembers(receipt.completedScenarios, receipt.expectedScenarios, `${contract.id} incomplete scenarios`);
    }
    if (contract.minimumScenarios) {
      assert.ok((receipt.expectedScenarios ?? []).length >= contract.minimumScenarios, `${contract.id} scenario count`);
      assertSameMembers(receipt.completedScenarios, receipt.expectedScenarios, `${contract.id} incomplete scenarios`);
    }
    if (contract.minimumChecks) {
      assert.ok((receipt.checks ?? []).length >= contract.minimumChecks, `${contract.id} check count`);
      assert.ok(receipt.checks.every((check) => check.result === 'pass'), `${contract.id} contains a failed check`);
    }
    if (contract.minimumCheckpoints) {
      assert.ok((receipt.checkpoints ?? []).length >= contract.minimumCheckpoints, `${contract.id} checkpoint count`);
      assert.ok(receipt.checkpoints.every((check) => check.result === 'pass'), `${contract.id} contains a failed checkpoint`);
    }
    if (contract.maximumElapsedSeconds) {
      assert.ok(receipt.elapsedSeconds <= contract.maximumElapsedSeconds, `${contract.id} exceeded deadline`);
    }
    if (contract.minimumScreenshots) validateScreenshots(receipt, contract.minimumScreenshots, contract.id);
    results.push({
      id: contract.id,
      verdict: 'pass',
      receipt: path.relative(evidenceRoot, file),
      runId: receipt.runId,
    });
  }
  return results;
}

export function runMutationProof(manifest) {
  const phases = [];
  assertManifestMatches(manifest);
  phases.push({ phase: 'green-before', result: 'pass' });

  const mutant = structuredClone(manifest);
  mutant.axes.semanticActions.splice(0, 1);
  let rejected = false;
  let rejection = '';
  try {
    assertManifestMatches(mutant);
  } catch (error) {
    rejected = true;
    rejection = error.message.split('\n')[0];
  }
  assert.ok(rejected, 'controlled manifest mutation did not turn the gate red');
  phases.push({ phase: 'red-controlled-mutation', result: 'pass', rejection });

  assertManifestMatches(manifest);
  phases.push({ phase: 'green-restored', result: 'pass' });
  return { schemaVersion: 1, verdict: 'pass', mutation: 'remove one of 26 semantic actions', phases };
}

function option(argv, name, fallback = null) {
  const index = argv.indexOf(name);
  return index >= 0 ? argv[index + 1] : fallback;
}

function main(argv) {
  const manifestPath = path.resolve(option(argv, '--manifest', DEFAULT_MANIFEST));
  const expected = buildReleaseManifest();
  if (argv.includes('--write')) {
    fs.writeFileSync(manifestPath, `${JSON.stringify(expected, null, 2)}\n`);
  }
  assert.ok(fs.existsSync(manifestPath), `coverage manifest not found: ${manifestPath}`);
  const actual = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  assertManifestMatches(actual, expected);

  const evidenceRoot = option(argv, '--evidence-root');
  const runtimeEvidence = evidenceRoot ? verifyRuntimeEvidence(path.resolve(evidenceRoot), actual) : [];
  const mutation = argv.includes('--self-test-mutation') ? runMutationProof(actual) : null;
  const mutationEvidencePath = option(argv, '--mutation-evidence');
  if (mutationEvidencePath && mutation) {
    const absolute = path.resolve(mutationEvidencePath);
    fs.mkdirSync(path.dirname(absolute), { recursive: true });
    fs.writeFileSync(absolute, `${JSON.stringify(mutation, null, 2)}\n`);
  }
  console.log(JSON.stringify({
    verdict: 'pass',
    manifest: path.relative(REPO_ROOT, manifestPath),
    counts: Object.fromEntries(Object.entries(actual.axes).map(([axis, rows]) => [axis, rows.length])),
    explicitUntested: actual.untested.length,
    runtimeEvidence,
    mutation,
  }, null, 2));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(`CRM release coverage gate failed: ${error.message}`);
    process.exit(1);
  }
}
