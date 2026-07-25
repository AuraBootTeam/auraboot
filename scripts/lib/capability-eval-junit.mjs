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
  ['testAgent', 'com.auraboot.framework.agent.AgentEvalCaseImportIT'],
  ['testAgent', 'com.auraboot.framework.agent.MultiPluginEvalCaseCoexistenceIT'],
  ['testAgent', 'com.auraboot.framework.agent.DeviceAgentSeedImportIT'],

  // Real-model agent capability gates.
  ['testAgent', 'com.auraboot.framework.agent.AgentFormFillHardLiveIT'],
  ['testAgent', 'com.auraboot.framework.agent.AgentFormFillLiveIT'],
  ['testAgent', 'com.auraboot.framework.agent.AgentMultiStepConvergenceLiveIT'],
  ['testAgent', 'com.auraboot.framework.agent.CapabilityEvalLiveIT'],
  ['testAgent', 'com.auraboot.framework.agent.CapabilityScorecardLiveIT'],
  ['testAgent', 'com.auraboot.framework.agent.ChatBiToolIntentLiveIT'],
  ['testAgent', 'com.auraboot.framework.agent.ConversationFaqExtractionLiveIT'],
  ['testAgent', 'com.auraboot.framework.agent.CsComplaintEmailExtractionLiveIT'],
  ['testAgent', 'com.auraboot.framework.agent.DeviceAgentLiveEvalIT'],
  ['testAgent', 'com.auraboot.framework.agent.DeviceDiagnosticsFullTurnIT'],
  ['testAgent', 'com.auraboot.framework.agent.DeviceOperationsAgentLiveEvalIT'],
  ['testAgent', 'com.auraboot.framework.agent.LlmTurnQualityJudgeLiveIT'],
  ['testAgent', 'com.auraboot.framework.agent.NlModelingApplyV4LiveIT'],
  ['testAgent', 'com.auraboot.framework.agent.NlModelingLiveQualityIT'],
  ['testAgent', 'com.auraboot.framework.agent.PcbaQualityAgentLiveEvalIT'],
  ['testAi', 'com.auraboot.framework.aurabot.skill.builtin.DashboardGenerationLiveIT'],
]);

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

export function summarizeCapabilityEvalResults(resultsRoot, write = (line) => console.log(line)) {
  const totals = { suites: 0, tests: 0, skipped: 0, failures: 0, errors: 0 };
  const violations = [];

  for (const [task, className] of REQUIRED_SUITES) {
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
    `  suites=${totals.suites}/${REQUIRED_SUITES.length} tests=${totals.tests} ` +
    `skipped=${totals.skipped} failures=${totals.failures} errors=${totals.errors}`,
  );

  if (violations.length > 0) {
    for (const violation of violations) write(`  VIOLATION ${violation}`);
  }
  return { ok: violations.length === 0, totals, violations };
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
    const report = summarizeCapabilityEvalResults(parseResultsRoot(process.argv.slice(2)));
    if (report.ok) {
      console.log(
        `[capability-eval-junit] PASS: all ${REQUIRED_SUITES.length} required suites ran without skips or failures.`,
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
