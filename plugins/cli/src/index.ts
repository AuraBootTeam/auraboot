#!/usr/bin/env node

import { Command } from 'commander';

// ── Existing commands ───────────────────────────────────────────────────────
import { validateCommand } from './commands/validate.js';
import { pluginImportCommand } from './commands/plugin-import.js';
import { initCommand } from './commands/init.js';
import { publishCommand } from './commands/publish.js';
import { buildCommand } from './commands/build.js';
import { diffCommand } from './commands/diff.js';
import { listCommand } from './commands/dsl/list.js';
import { inspectCommand_ } from './commands/dsl/inspect.js';
import { statusCommand } from './commands/dsl/status.js';
import { depsCommand } from './commands/dsl/deps.js';
import { scaffoldCommand } from './commands/dsl/scaffold.js';
import { syncI18nCommand } from './commands/dsl/sync-i18n.js';
import { diagnoseCommand } from './commands/dsl/diagnose.js';

// ── New commands ────────────────────────────────────────────────────────────
import { loginCommand } from './commands/login.js';
import { askCommand } from './commands/ask.js';
import { planCommand } from './commands/plan.js';
import { runCommand, runShowCommand } from './commands/run.js';
import { agentListCommand, agentShowCommand } from './commands/ops/agents.js';
import { runsListCommand, runsShowCommand } from './commands/ops/runs.js';
import { auditListCommand, auditShowCommand } from './commands/ops/audit.js';
import { toolListCommand, toolTestCommand } from './commands/ops/tools.js';
import { approvalListCommand, approvalApproveCommand, approvalRejectCommand } from './commands/ops/approvals.js';

// ── Business domain commands ────────────────────────────────────────────────
import { crmLeadsCommand } from './commands/crm/leads.js';
import { crmOpportunitiesCommand } from './commands/crm/opportunities.js';
import { crmAccountsCommand } from './commands/crm/accounts.js';
import { crmDashboardCommand } from './commands/crm/dashboard.js';
import { projectListCommand } from './commands/project/list.js';
import { projectTasksCommand } from './commands/project/tasks.js';
import { projectDashboardCommand } from './commands/project/dashboard.js';
import { hrEmployeesCommand } from './commands/hr/employees.js';
import { hrDepartmentsCommand } from './commands/hr/departments.js';
import { hrPositionsCommand } from './commands/hr/positions.js';
import { financeAccountsCommand } from './commands/finance/accounts.js';
import { financeInvoicesCommand } from './commands/finance/invoices.js';
import { financePaymentsCommand } from './commands/finance/payments.js';
import { financeGlEntriesCommand } from './commands/finance/gl-entries.js';
import { financeDashboardCommand } from './commands/finance/dashboard.js';
import { inventoryWarehousesCommand } from './commands/inventory/warehouses.js';
import { inventoryStockCommand, inventoryLowStockCommand } from './commands/inventory/stock.js';
import { inventoryInboundCommand } from './commands/inventory/inbound.js';
import { inventoryOutboundCommand } from './commands/inventory/outbound.js';
import { inventoryDashboardCommand } from './commands/inventory/dashboard.js';

// ── Phase 3 commands ────────────────────────────────────────────────────────
import { startMcpServer } from './mcp/server.js';
import { shellCommand } from './commands/shell.js';
import { statusCommand as platformStatusCommand } from './commands/status.js';
import { queryCommand } from './commands/pipeline/query.js';
import { analyzeCommand } from './commands/pipeline/analyze.js';
import { createCommand } from './commands/pipeline/create.js';
import { execCommand } from './commands/pipeline/exec.js';

// ── Pipe workflow commands ────────────────────────────────────────────────
import { pipeRunCommand } from './commands/pipe/run.js';
import { pipeListCommand } from './commands/pipe/list.js';
import { pipeValidateCommand } from './commands/pipe/validate.js';
import { pipeCreateCommand } from './commands/pipe/create.js';

// ── MCP client commands ────────────────────────────────────────────────────
import {
  mcpListCommand,
  mcpAddCommand,
  mcpRemoveCommand,
  mcpTestCommand,
  mcpToolsCommand,
} from './commands/mcp.js';

const program = new Command();

program
  .name('aura')
  .description('AuraBoot CLI — AI-powered Company OS console')
  .version('2.0.0');

// ── Global options ──────────────────────────────────────────────────────────

program
  .option('--token <jwt>', 'JWT token for authentication')
  .option('--env <name>', 'Target environment (local, staging, production)')
  .option('--format <type>', 'Output format: table, json, compact', 'table')
  .option('--agent-mode', 'Agent-optimized output (compact JSON, no decoration)');

// ── Natural language entry point ────────────────────────────────────────────
// `aura "some question"` routes to ask

program
  .argument('[message]', 'Natural language query (shortcut for: aura ask "...")')
  .action(async (message: string | undefined, options: any) => {
    if (message && !program.args.includes(message)) return; // subcommand handled
    if (message) {
      await askCommand(message, options);
    }
  });

// ── login ───────────────────────────────────────────────────────────────────

program
  .command('login')
  .description('Authenticate with AuraBoot platform')
  .option('-u, --user <email>', 'Login email')
  .option('-p, --password <password>', 'Login password')
  .option('-t, --tenant <name>', 'Select tenant/space by name (for multi-tenant users)')
  .action(async (cmdOpts: any) => {
    const opts = { ...program.opts(), ...cmdOpts };
    await loginCommand(opts);
  });

// ── ask ─────────────────────────────────────────────────────────────────────

program
  .command('ask <message>')
  .description('Ask AuraBot a question (streaming AI response)')
  .action(async (message: string) => {
    await askCommand(message, program.opts());
  });

// ── plan ────────────────────────────────────────────────────────────────────

program
  .command('plan <message>')
  .description('Generate a structured execution plan from natural language')
  .action(async (message: string) => {
    await planCommand(message, program.opts());
  });

// ── run ─────────────────────────────────────────────────────────────────────

const run = program
  .command('run')
  .description('Dispatch and monitor agent execution');

run
  .command('dispatch <target>')
  .description('Dispatch a task to an agent')
  .action(async (target: string) => {
    await runCommand(target, program.opts());
  });

run
  .command('show <runPid>')
  .description('View run detail and plan steps')
  .action(async (runPid: string) => {
    await runShowCommand(runPid, program.opts());
  });

// ── ops ─────────────────────────────────────────────────────────────────────

const ops = program
  .command('ops')
  .description('Operations: agents, runs, audit, tools');

// ops agents
const opsAgents = ops
  .command('agents')
  .description('Digital worker management');

opsAgents
  .command('list')
  .description('List all agents with status')
  .action(async () => {
    await agentListCommand(program.opts());
  });

opsAgents
  .command('show <code>')
  .description('Show agent detail (role, tools, soul profile)')
  .action(async (code: string) => {
    await agentShowCommand(code, program.opts());
  });

// ops runs
const opsRuns = ops
  .command('runs')
  .description('Execution history');

opsRuns
  .command('list')
  .description('List recent agent runs')
  .action(async () => {
    await runsListCommand(program.opts());
  });

opsRuns
  .command('show <runPid>')
  .description('View run detail')
  .action(async (runPid: string) => {
    await runsShowCommand(runPid, program.opts());
  });

// ops audit
const opsAudit = ops
  .command('audit')
  .description('Governance and compliance');

opsAudit
  .command('list')
  .description('List recent audit traces')
  .action(async () => {
    await auditListCommand(program.opts());
  });

opsAudit
  .command('show <traceId>')
  .description('View audit trace detail')
  .action(async (traceId: string) => {
    await auditShowCommand(traceId, program.opts());
  });

// ops tools
const opsTools = ops
  .command('tools')
  .description('Agent capability management');

opsTools
  .command('list')
  .description('List available agent tools')
  .action(async () => {
    await toolListCommand(program.opts());
  });

opsTools
  .command('test <code>')
  .description('Dry-run tool validation')
  .action(async (code: string) => {
    await toolTestCommand(code, program.opts());
  });

// ops approvals
const opsApprovals = ops
  .command('approvals')
  .description('Human-in-the-loop approval management');

opsApprovals
  .command('list')
  .description('List pending agent approvals')
  .action(async () => {
    await approvalListCommand(program.opts());
  });

opsApprovals
  .command('approve <pid>')
  .description('Approve a pending agent action')
  .action(async (pid: string) => {
    await approvalApproveCommand(pid, program.opts());
  });

opsApprovals
  .command('reject <pid>')
  .description('Reject a pending agent action')
  .requiredOption('-r, --reason <text>', 'Reason for rejection')
  .action(async (pid: string, cmdOpts: any) => {
    await approvalRejectCommand(pid, cmdOpts.reason, { ...program.opts(), ...cmdOpts });
  });

// ── crm ─────────────────────────────────────────────────────────────────────

const crm = program
  .command('crm')
  .description('CRM: leads, opportunities, accounts, dashboard');

crm
  .command('leads')
  .description('List and filter CRM leads')
  .option('--status <status>', 'Filter by status (NEW, CONTACTED, QUALIFIED, CONVERTED, LOST)')
  .option('--source <source>', 'Filter by source (WEBSITE, REFERRAL, EXHIBITION, ...)')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await crmLeadsCommand({ ...program.opts(), ...cmdOpts });
  });

crm
  .command('opportunities')
  .alias('opps')
  .description('List and filter sales opportunities')
  .option('--stage <stage>', 'Filter by stage (DISCOVERY, QUALIFICATION, PROPOSAL, NEGOTIATION, CLOSED_WON, CLOSED_LOST)')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await crmOpportunitiesCommand({ ...program.opts(), ...cmdOpts });
  });

crm
  .command('accounts')
  .description('List and filter customer accounts')
  .option('--status <status>', 'Filter by status')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await crmAccountsCommand({ ...program.opts(), ...cmdOpts });
  });

crm
  .command('dashboard')
  .description('CRM KPI summary and pipeline overview')
  .action(async () => {
    await crmDashboardCommand(program.opts());
  });

// ── project ─────────────────────────────────────────────────────────────────

const project = program
  .command('project')
  .alias('pm')
  .description('Project management: projects, tasks, dashboard');

project
  .command('list')
  .description('List projects')
  .option('--status <status>', 'Filter by status (ACTIVE, COMPLETED, ON_HOLD, ARCHIVED)')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await projectListCommand({ ...program.opts(), ...cmdOpts });
  });

project
  .command('tasks')
  .description('List project tasks')
  .option('--status <status>', 'Filter by status (TODO, IN_PROGRESS, DONE, BLOCKED)')
  .option('--assignee <name>', 'Filter by assignee')
  .option('--mine', 'Show only my tasks')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await projectTasksCommand({ ...program.opts(), ...cmdOpts });
  });

project
  .command('dashboard')
  .description('Project KPI summary and status distribution')
  .action(async () => {
    await projectDashboardCommand(program.opts());
  });

// ── hr ────────────────────────────────────────────────────────────────────

const hr = program
  .command('hr')
  .description('HR & Org: employees, departments, positions');

hr
  .command('employees')
  .alias('emp')
  .description('List and filter employees')
  .option('--status <status>', 'Filter by status (ACTIVE, PROBATION, RESIGNED)')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await hrEmployeesCommand({ ...program.opts(), ...cmdOpts });
  });

hr
  .command('departments')
  .alias('dept')
  .description('List and filter departments')
  .option('--status <status>', 'Filter by status (ACTIVE, INACTIVE)')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await hrDepartmentsCommand({ ...program.opts(), ...cmdOpts });
  });

hr
  .command('positions')
  .alias('pos')
  .description('List and filter positions')
  .option('--status <status>', 'Filter by status (ACTIVE, INACTIVE)')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await hrPositionsCommand({ ...program.opts(), ...cmdOpts });
  });

// ── finance ───────────────────────────────────────────────────────────────

const finance = program
  .command('finance')
  .alias('fin')
  .description('Finance: accounts, invoices, payments, journal entries, dashboard');

finance
  .command('accounts')
  .description('List chart of accounts')
  .option('--type <type>', 'Filter by type (ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE)')
  .option('--status <status>', 'Filter by status (ACTIVE, INACTIVE)')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await financeAccountsCommand({ ...program.opts(), ...cmdOpts });
  });

finance
  .command('invoices')
  .description('List AR/AP invoices')
  .option('-d, --direction <dir>', 'AR or AP (default: ar)', 'ar')
  .option('--status <status>', 'Filter by status (OPEN, PARTIAL, PAID, OVERDUE, VOID)')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await financeInvoicesCommand({ ...program.opts(), ...cmdOpts });
  });

finance
  .command('payments')
  .description('List payment/receipt records')
  .option('--type <type>', 'Filter by type (RECEIPT, PAYMENT)')
  .option('--status <status>', 'Filter by status (DRAFT, CONFIRMED, VOID)')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await financePaymentsCommand({ ...program.opts(), ...cmdOpts });
  });

finance
  .command('gl-entries')
  .alias('journal')
  .description('List general ledger journal entries')
  .option('--status <status>', 'Filter by status (DRAFT, POSTED, VOID)')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await financeGlEntriesCommand({ ...program.opts(), ...cmdOpts });
  });

finance
  .command('dashboard')
  .description('Finance KPI summary: AR/AP balances, revenue, aging')
  .action(async () => {
    await financeDashboardCommand(program.opts());
  });

// ── inventory ─────────────────────────────────────────────────────────────

const inventory = program
  .command('inventory')
  .alias('inv')
  .description('Inventory: warehouses, stock, inbound, outbound, dashboard');

inventory
  .command('warehouses')
  .alias('wh')
  .description('List and filter warehouses')
  .option('--status <status>', 'Filter by status (ENABLED, DISABLED)')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await inventoryWarehousesCommand({ ...program.opts(), ...cmdOpts });
  });

inventory
  .command('stock')
  .description('Current stock levels (inventory balance)')
  .option('--warehouse <id>', 'Filter by warehouse PID')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await inventoryStockCommand({ ...program.opts(), ...cmdOpts });
  });

inventory
  .command('low-stock')
  .description('Low-stock alerts (items below safety stock)')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await inventoryLowStockCommand({ ...program.opts(), ...cmdOpts });
  });

inventory
  .command('inbound')
  .alias('in')
  .description('List inbound receipts (warehouse receipts)')
  .option('--status <status>', 'Filter by status (DRAFT, CONFIRMED, CANCELLED)')
  .option('--type <type>', 'Filter by type')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await inventoryInboundCommand({ ...program.opts(), ...cmdOpts });
  });

inventory
  .command('outbound')
  .alias('out')
  .description('List outbound issues (warehouse issues)')
  .option('--status <status>', 'Filter by status (DRAFT, CONFIRMED, CANCELLED)')
  .option('--type <type>', 'Filter by type')
  .option('-k, --keyword <text>', 'Search keyword')
  .option('-n, --limit <n>', 'Max results', '50')
  .action(async (cmdOpts: any) => {
    await inventoryOutboundCommand({ ...program.opts(), ...cmdOpts });
  });

inventory
  .command('dashboard')
  .description('Inventory KPI summary: SKUs, value, alerts, document stats')
  .action(async () => {
    await inventoryDashboardCommand(program.opts());
  });

// ── pipeline commands ───────────────────────────────────────────────────────

program
  .command('query [entity]')
  .description('Query entity data (outputs JSON for piping)')
  .option('-f, --filter <expr...>', 'Filter: field=value, field>value, field~value')
  .option('-n, --limit <n>', 'Max results', '50')
  .option('-s, --sort <field:dir>', 'Sort field:asc|desc')
  .option('--nq <code>', 'Use NamedQuery instead of Dynamic CRUD')
  .action(async (entity: string | undefined, cmdOpts: any) => {
    await queryCommand(entity, { ...program.opts(), ...cmdOpts });
  });

program
  .command('analyze <analysis>')
  .description('AI-powered analysis of piped data (stdin → AI → stdout)')
  .option('-p, --prompt <text>', 'Additional prompt context')
  .action(async (analysis: string, cmdOpts: any) => {
    await analyzeCommand(analysis, { ...program.opts(), ...cmdOpts });
  });

program
  .command('create <entity>')
  .description('Batch create records from stdin JSON')
  .option('--dry-run', 'Preview without creating')
  .action(async (entity: string, cmdOpts: any) => {
    await createCommand(entity, { ...program.opts(), ...cmdOpts });
  });

program
  .command('exec <commandCode>')
  .description('Execute a DSL Command (e.g. sc:create_showcase)')
  .option('--set <expr...>', 'Set field: key=value or key:type=value (int/float/bool/json/null)')
  .option('--target <pid>', 'Target record PID (for update/status commands)')
  .option('--operation <type>', 'Operation type hint')
  .option('--from <file>', 'Read payload from JSON file (object or array for batch)')
  .option('--stdin', 'Read payload from stdin JSON')
  .option('--dry-run', 'Preview request body without executing')
  .action(async (commandCode: string, cmdOpts: any) => {
    await execCommand(commandCode, { ...program.opts(), ...cmdOpts });
  });

// ── status ──────────────────────────────────────────────────────────────────

program
  .command('status')
  .description('Check platform health and connection status')
  .action(async () => {
    await platformStatusCommand(program.opts());
  });

// ── shell ───────────────────────────────────────────────────────────────────

program
  .command('shell')
  .description('Interactive REPL with persistent context and tab completion')
  .action(async () => {
    await shellCommand(program.opts());
  });

// ── mcp (server + client management) ────────────────────────────────────────

const mcp = program
  .command('mcp')
  .description('Run AuraBoot MCP server and manage external MCP server connections');

mcp
  .command('serve')
  .description('Start the AuraBoot MCP stdio server (for Cursor / Claude Code)')
  .action(async () => {
    await startMcpServer(program.opts());
  });

mcp
  .command('list')
  .description('List configured external MCP servers')
  .action(async () => {
    await mcpListCommand(program.opts());
  });

mcp
  .command('add <name>')
  .description('Add an MCP server')
  .requiredOption('--transport <type>', 'Transport type: stdio or sse')
  .option('--url <url>', 'Server URL (for SSE transport)')
  .option('--command <cmd>', 'Executable command (for stdio transport)')
  .option('--args <args>', 'Comma-separated command arguments (for stdio)')
  .option('--description <text>', 'Human-readable description')
  .option('--env <pairs...>', 'Environment variables as KEY=VALUE')
  .action(async (name: string, cmdOpts: any) => {
    await mcpAddCommand(name, { ...program.opts(), ...cmdOpts });
  });

mcp
  .command('remove <name>')
  .description('Remove an MCP server')
  .action(async (name: string) => {
    await mcpRemoveCommand(name);
  });

mcp
  .command('test <name>')
  .description('Test connection to an MCP server (calls initialize + tools/list)')
  .action(async (name: string) => {
    await mcpTestCommand(name, program.opts());
  });

mcp
  .command('tools <name>')
  .description('List tools provided by an MCP server')
  .action(async (name: string) => {
    await mcpToolsCommand(name, program.opts());
  });

// ── plugin (existing) ───────────────────────────────────────────────────────

const plugin = program
  .command('plugin')
  .description('Plugin management commands');

plugin
  .command('validate [dir]')
  .description('Validate plugin configuration')
  .action(async (dir?: string) => {
    await validateCommand(dir || '.');
  });

plugin
  .command('import [dir]')
  .description('Import a plugin to the AuraBoot platform')
  .option('-t, --target <url>', 'Target platform URL', 'http://localhost:6443')
  .option('-u, --user <email>', 'Login email')
  .option('-p, --password <password>', 'Login password')
  .option('--yes', 'Skip confirmation prompts')
  .option('--dry-run', 'Preview what would be created without importing')
  .option('--conflict-strategy <strategy>', 'Conflict strategy: OVERWRITE | SKIP | ERROR', 'overwrite')
  .action(async (dir: string | undefined, cmdOpts: any) => {
    const opts = { ...program.opts(), ...cmdOpts, dryRun: cmdOpts.dryRun };
    await pluginImportCommand(dir ?? '.', opts);
  });

plugin
  .command('init [name]')
  .description('Create a new plugin from template')
  .option('-d, --dir <path>', 'Output directory for the new plugin (defaults to <cwd>/<name>)')
  .option('--non-interactive', 'Skip all prompts; require values via flags or fail with missing-flag error')
  .option('--plugin-id <id>', 'Plugin ID in reverse-domain form (e.g. com.acme.foo)')
  .option('--namespace <ns>', 'Lowercase alphanumeric namespace')
  .option('--display-name <name>', 'Human-readable display name')
  .option('--plugin-type <type>', "Plugin type: 'config' or 'hybrid'")
  .option('--no-sample-model', 'Do not include sample model scaffold (non-interactive mode)')
  .action(async (name: string | undefined, cmdOpts: any) => {
    await initCommand(name, {
      dir: cmdOpts.dir,
      nonInteractive: cmdOpts.nonInteractive,
      pluginId: cmdOpts.pluginId,
      namespace: cmdOpts.namespace,
      displayName: cmdOpts.displayName,
      pluginType: cmdOpts.pluginType,
      // commander auto-derives `--no-sample-model` → `sampleModel === false`
      noSampleModel: cmdOpts.sampleModel === false,
    });
  });

plugin
  .command('publish [dir]')
  .description('Publish plugin to AuraBoot platform')
  .option('-t, --target <url>', 'Target platform URL', 'http://localhost:6443')
  .option('-u, --user <email>', 'Login email')
  .option('-p, --password <password>', 'Login password')
  .option('--yes', 'Skip confirmation prompts')
  .action(async (dir: string | undefined, options: any) => {
    await publishCommand(dir || '.', options);
  });

plugin
  .command('build [dir]')
  .description('Build and package plugin')
  .option('-o, --output <dir>', 'Output directory', 'dist')
  .action(async (dir: string | undefined, options: any) => {
    await buildCommand(dir || '.', options);
  });

plugin
  .command('diff [dir]')
  .description('Compare local plugin config vs remote platform')
  .option('-t, --target <url>', 'Target platform URL', 'http://localhost:6443')
  .option('-u, --user <email>', 'Login email')
  .option('-p, --password <password>', 'Login password')
  .action(async (dir: string | undefined, options: any) => {
    await diffCommand(dir || '.', options);
  });

// ── dsl (existing) ──────────────────────────────────────────────────────────

const dsl = program
  .command('dsl')
  .description('DSL resource query, generation, and diagnostics');

dsl
  .command('list <type>')
  .description('List resources (models, fields, commands, pages, permissions, menus, dicts)')
  .option('-d, --dir <path>', 'Plugin directory', '.')
  .option('--pretty', 'Human-readable output (default: JSON)')
  .option('--quiet', 'Only output result')
  .option('--model <code>', 'Filter by model code (for fields/commands/pages)')
  .action(async (type: string, options: any) => {
    await listCommand(type, options);
  });

dsl
  .command('inspect <type> [code]')
  .description('Inspect a resource definition with cross-references')
  .option('-d, --dir <path>', 'Plugin directory', '.')
  .option('--pretty', 'Human-readable output')
  .option('--quiet', 'Only output result')
  .action(async (type: string, code: string | undefined, options: any) => {
    await inspectCommand_(type, code, options);
  });

dsl
  .command('status')
  .description('Plugin health overview: counts, score, orphans, issues')
  .option('-d, --dir <path>', 'Plugin directory', '.')
  .option('--pretty', 'Human-readable output')
  .option('--quiet', 'Only output result')
  .action(async (options: any) => {
    await statusCommand(options);
  });

dsl
  .command('deps <model-code>')
  .description('Show dependency graph for a model')
  .option('-d, --dir <path>', 'Plugin directory', '.')
  .option('--pretty', 'Human-readable output')
  .option('--quiet', 'Only output result')
  .action(async (modelCode: string, options: any) => {
    await depsCommand(modelCode, options);
  });

dsl
  .command('scaffold <type> <code>')
  .description('Generate resource skeleton (model, commands, pages)')
  .option('-d, --dir <path>', 'Plugin directory', '.')
  .option('--pretty', 'Human-readable output')
  .option('--quiet', 'Only output result')
  .option('--fields <spec>', 'Field spec: "name:TEXT,status:SELECT,ref:REFERENCE:target_model"')
  .option('--namespace <ns>', 'Namespace override')
  .option('--dry-run', 'Preview without writing files')
  .action(async (type: string, code: string, options: any) => {
    await scaffoldCommand(type, code, { ...options, dryRun: options.dryRun });
  });

dsl
  .command('sync-i18n')
  .description('Scan and generate missing i18n keys')
  .option('-d, --dir <path>', 'Plugin directory', '.')
  .option('--pretty', 'Human-readable output')
  .option('--quiet', 'Only output result')
  .option('--dry-run', 'Report only, don\'t modify files')
  .action(async (options: any) => {
    await syncI18nCommand({ ...options, dryRun: options.dryRun });
  });

dsl
  .command('diagnose')
  .description('Full diagnostic report with 14 checks')
  .option('-d, --dir <path>', 'Plugin directory', '.')
  .option('--pretty', 'Human-readable output')
  .option('--quiet', 'Only output result')
  .option('--severity <level>', 'Filter by severity (error, warning, info)')
  .action(async (options: any) => {
    await diagnoseCommand(options);
  });

// ── pipe (workflow) ─────────────────────────────────────────────────────────

const pipe = program
  .command('pipe')
  .description('Workflow pipelines: compose query → analyze → create steps');

pipe
  .command('run <file-or-template>')
  .description('Execute a workflow YAML/JSON file or built-in template')
  .option('--template', 'Treat argument as a template name')
  .option('--dry-run', 'Preview mode — skip side-effect steps')
  .option('--verbose', 'Print step progress (default: true)')
  .action(async (fileOrName: string, cmdOpts: any) => {
    await pipeRunCommand(fileOrName, { ...program.opts(), ...cmdOpts, dryRun: cmdOpts.dryRun });
  });

pipe
  .command('list')
  .description('List available built-in workflow templates')
  .action(async () => {
    await pipeListCommand(program.opts());
  });

pipe
  .command('validate <file>')
  .description('Validate a workflow definition file')
  .action(async (file: string) => {
    await pipeValidateCommand(file, program.opts());
  });

pipe
  .command('create')
  .description('Create a new workflow file from template or scaffold')
  .option('--from <template>', 'Copy from a built-in template')
  .option('-o, --output <file>', 'Output file path')
  .action(async (cmdOpts: any) => {
    await pipeCreateCommand({ ...program.opts(), ...cmdOpts });
  });

// ── Parse ───────────────────────────────────────────────────────────────────

program.parse();
