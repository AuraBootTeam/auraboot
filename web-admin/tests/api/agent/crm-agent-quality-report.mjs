#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DEFAULT_EVIDENCE_DIR = 'test-results/agent-evidence';
const DEFAULT_REPORT_NAME = 'crm-agent-routing-quality-report.json';

export function buildRoutingQualityReport(evidenceDir = DEFAULT_EVIDENCE_DIR, options = {}) {
  const records = loadEvidenceRecords(evidenceDir);
  return summarizeEvidenceRecords(records, options);
}

export function loadEvidenceRecords(evidenceDir = DEFAULT_EVIDENCE_DIR) {
  if (!fs.existsSync(evidenceDir)) {
    return [];
  }

  return fs
    .readdirSync(evidenceDir)
    .filter((fileName) => /^agent-evidence-.+\.json$/.test(fileName))
    .sort()
    .map((fileName) => {
      const filePath = path.join(evidenceDir, fileName);
      return JSON.parse(fs.readFileSync(filePath, 'utf8'));
    });
}

export function summarizeEvidenceRecords(records, options = {}) {
  const scenarios = records
    .map((record) => summarizeScenario(record))
    .sort((left, right) => String(left.scenarioId).localeCompare(String(right.scenarioId)));

  const summary = scenarios.reduce(
    (acc, scenario) => {
      acc.scenarioCount += 1;
      acc.toolCallCount += scenario.toolCallCount;
      acc.unavailableToolCount += scenario.unavailableToolCount;
      acc.sqlFallbackCount += scenario.sqlFallbackCount;
      acc.confirmationCount += scenario.confirmationCount;
      acc.retryCount += scenario.retryCount;
      return acc;
    },
    {
      scenarioCount: 0,
      toolCallCount: 0,
      unavailableToolCount: 0,
      sqlFallbackCount: 0,
      confirmationCount: 0,
      retryCount: 0,
    },
  );

  return {
    generatedAt: options.generatedAt || new Date().toISOString(),
    summary,
    scenarios,
  };
}

function summarizeScenario(record) {
  const toolCalls = Array.isArray(record?.toolCalls) ? record.toolCalls : [];
  const confirmations = Array.isArray(record?.confirmations) ? record.confirmations : [];
  const unavailableTools = toolCalls.filter((tool) => isUnavailableToolCall(tool));
  const sqlFallbacks = toolCalls.filter((tool) => tool?.toolName === 'platform_execute_sql');

  return {
    scenarioId: record?.scenarioId || 'unknown',
    coverageLevel: record?.coverageLevel || null,
    toolCallCount: toolCalls.length,
    unavailableToolCount: unavailableTools.length,
    sqlFallbackCount: sqlFallbacks.length,
    confirmationCount: confirmations.length,
    retryCount: Number(record?.transientRetryCount || 0),
    toolNames: toolCalls.map((tool) => tool?.toolName).filter(Boolean),
    unavailableToolNames: unavailableTools.map((tool) => tool?.toolName).filter(Boolean),
    error: record?.error || null,
  };
}

function isUnavailableToolCall(tool) {
  const success = tool?.success;
  const errorText = [tool?.error, tool?.errorMessage, tool?.message].filter(Boolean).join(' ');
  return (
    success === false &&
    /unavailable|not available|not found|unknown tool|missing tool/i.test(errorText)
  );
}

function writeReport(evidenceDir, outputPath) {
  const report = buildRoutingQualityReport(evidenceDir);
  if (report.summary.scenarioCount === 0) {
    throw new Error(`No agent evidence JSON files found in ${evidenceDir}`);
  }
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, JSON.stringify(report, null, 2));
  return report;
}

function isMainModule() {
  return process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
}

if (isMainModule()) {
  const evidenceDir = process.argv[2] || DEFAULT_EVIDENCE_DIR;
  const outputPath = process.argv[3] || path.join(evidenceDir, DEFAULT_REPORT_NAME);
  try {
    const report = writeReport(evidenceDir, outputPath);
    const summary = report.summary;
    console.log(
      `[crm-agent-quality] scenarios=${summary.scenarioCount} ` +
        `toolCalls=${summary.toolCallCount} unavailable=${summary.unavailableToolCount} ` +
        `sqlFallbacks=${summary.sqlFallbackCount} confirmations=${summary.confirmationCount} ` +
        `retries=${summary.retryCount} output=${outputPath}`,
    );
  } catch (error) {
    console.error(`[crm-agent-quality] ${error.message}`);
    process.exit(1);
  }
}
