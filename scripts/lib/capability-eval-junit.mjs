#!/usr/bin/env node
/**
 * Authoritative JUnit receipt for the digital-employee capability gate.
 *
 * Gradle exits 0 when every selected test is skipped, and a broad TEST-*.xml
 * glob can accidentally accept stale or newly omitted suites. Keep the required
 * suite inventory explicit and fail closed on missing, zero-test, skipped,
 * failed, errored, or malformed XML.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const REQUIRED_SUITES = Object.freeze([
  // Deterministic plugin -> DB -> eval-engine seams.
  ['platform-app/test', 'com.auraboot.framework.agent.AgentEvalCaseImportIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.MultiPluginEvalCaseCoexistenceIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.DeviceAgentSeedImportIT'],

  // Real-model agent capability gates.
  ['platform-app/test', 'com.auraboot.framework.agent.AgentFormFillHardLiveIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.AgentFormFillLiveIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.AgentMultiStepConvergenceLiveIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.CapabilityEvalLiveIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.CapabilityScorecardLiveIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.ChatBiToolIntentLiveIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.ConversationFaqExtractionLiveIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.CsComplaintEmailExtractionLiveIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.DeviceAgentLiveEvalIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.DeviceDiagnosticsFullTurnIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.DeviceOperationsAgentLiveEvalIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.LlmTurnQualityJudgeLiveIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.NlModelingApplyV4LiveIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.NlModelingLiveQualityIT'],
  ['platform-app/test', 'com.auraboot.framework.agent.PcbaQualityAgentLiveEvalIT'],
  ['platform-app/test', 'com.auraboot.framework.aurabot.skill.builtin.DashboardGenerationLiveIT'],
  ['platform-agent/test', 'com.auraboot.framework.aurabot.service.FormDraftExtractionLiveTest'],
]);

export const EVAL_PROFILES = Object.freeze({
  full: REQUIRED_SUITES,
  focused: REQUIRED_SUITES.filter(([, name]) => ['CapabilityEvalLiveIT', 'CapabilityScorecardLiveIT', 'AgentMultiStepConvergenceLiveIT'].some(suffix => name.endsWith('.' + suffix))),
  deterministic: REQUIRED_SUITES.slice(0, 3),
});

export function profileSuites(profile = 'full') {
  if (!Object.hasOwn(EVAL_PROFILES, profile)) throw new Error(`Unknown capability profile: ${profile}`);
  return EVAL_PROFILES[profile];
}

function integerAttribute(attributes, name) {
  const match = attributes.match(new RegExp(`\\b${name}=["'](\\d+)["']`));
  return match ? Number.parseInt(match[1], 10) : null;
}

function readSuiteStats(xml) {
  const suite = xml.match(/<testsuite\b([^>]*)>/s);
  if (!suite) return null;
  const attributes = suite[1];
  const stats = {
    tests: integerAttribute(attributes, 'tests'),
    skipped: integerAttribute(attributes, 'skipped'),
    failures: integerAttribute(attributes, 'failures'),
    errors: integerAttribute(attributes, 'errors'),
  };
  return Object.values(stats).some((value) => value === null) ? null : stats;
}

export function summarizeCapabilityEvalResults(resultsRoot, write = (line) => console.log(line), profile = 'full') {
  const required = profileSuites(profile);
  const totals = { suites: 0, tests: 0, skipped: 0, failures: 0, errors: 0 };
  const violations = [];

  for (const [task, className] of required) {
    const xmlPath = path.join(resultsRoot, task, `TEST-${className}.xml`);
    if (!fs.existsSync(xmlPath)) {
      violations.push(`MISSING ${task}/${className}`);
      write(`  MISSING   ${task}/${className}`);
      continue;
    }

    const stats = readSuiteStats(fs.readFileSync(xmlPath, 'utf8'));
    if (!stats) {
      violations.push(`MALFORMED ${task}/${className}`);
      write(`  MALFORMED ${task}/${className}`);
      continue;
    }

    totals.suites += 1;
    totals.tests += stats.tests;
    totals.skipped += stats.skipped;
    totals.failures += stats.failures;
    totals.errors += stats.errors;
    write(
      `  ${className.padEnd(76)} tests=${String(stats.tests).padEnd(3)} ` +
      `skip=${String(stats.skipped).padEnd(3)} fail=${String(stats.failures).padEnd(3)} err=${stats.errors}`,
    );

    if (stats.tests === 0) violations.push(`ZERO_TESTS ${task}/${className}`);
    if (stats.skipped > 0) violations.push(`SKIPPED ${task}/${className} (${stats.skipped})`);
    if (stats.failures > 0) violations.push(`FAILED ${task}/${className} (${stats.failures})`);
    if (stats.errors > 0) violations.push(`ERRORED ${task}/${className} (${stats.errors})`);
  }

  write('  ----');
  write(
    `  suites=${totals.suites}/${required.length} tests=${totals.tests} ` +
    `skipped=${totals.skipped} failures=${totals.failures} errors=${totals.errors}`,
  );

  if (violations.length > 0) {
    for (const violation of violations) write(`  VIOLATION ${violation}`);
  }
  return { ok: violations.length === 0, profile, requiredSuites: required.length, totals, violations };
}

function parseResultsRoot(argv) {
  const index = argv.indexOf('--results-root');
  if (index < 0 || !argv[index + 1]) {
    throw new Error('usage: capability-eval-junit.mjs --results-root <platform/build/test-results>');
  }
  return path.resolve(argv[index + 1]);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  try {
    const profileIndex = process.argv.indexOf('--profile');
    const profile = profileIndex < 0 ? 'full' : process.argv[profileIndex + 1];
    const report = summarizeCapabilityEvalResults(parseResultsRoot(process.argv.slice(2)), undefined, profile);
    const jsonIndex = process.argv.indexOf('--json-out');
    if (jsonIndex >= 0) fs.writeFileSync(process.argv[jsonIndex + 1], JSON.stringify(report, null, 2) + '\n');
    if (report.ok) {
      console.log(
        `[capability-eval-junit] PASS: all ${report.requiredSuites} required ${profile} suites ran without skips or failures.`,
      );
    } else {
      console.error(
        `[capability-eval-junit] FAIL: ${report.violations.length} required-suite violation(s).`,
      );
      process.exitCode = 1;
    }
  } catch (error) {
    console.error(`[capability-eval-junit] FAIL: ${error.message}`);
    process.exitCode = 2;
  }
}
