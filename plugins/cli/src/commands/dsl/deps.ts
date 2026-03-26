import { loadPlugin } from '../../utils/plugin-loader.js';
import { buildResourceIndex } from '../../utils/resource-index.js';
import { successOutput, errorOutput, formatOutput, printTree, FormatOptions } from '../../utils/output-formatter.js';
import chalk from 'chalk';

interface DepNode {
  code: string;
  type: string;
  via?: string;
}

interface DepsData {
  modelCode: string;
  forward: DepNode[];   // Models this model depends on
  reverse: DepNode[];   // Resources that depend on this model
}

export async function depsCommand(modelCode: string, options: { dir: string; pretty: boolean; quiet: boolean }): Promise<void> {
  const files = loadPlugin(options.dir);
  const idx = buildResourceIndex(files);
  const fmt: FormatOptions = { format: options.pretty ? 'pretty' : 'json', quiet: options.quiet };

  if (!idx.models.has(modelCode)) {
    const output = errorOutput('dsl.deps', [{
      code: 'not_found',
      message: `Model '${modelCode}' not found`,
      suggestion: `Use 'aura dsl list models' to see available models`,
    }]);
    formatOutput(output, fmt);
    process.exit(1);
  }

  // Forward dependencies: REFERENCE fields in this model that point to other models
  const forward: DepNode[] = [];
  const modelFields = idx.fieldsByModel.get(modelCode) || [];
  for (const f of modelFields) {
    if (f.dataType === 'reference' && f.extension?.referenceModel) {
      forward.push({
        code: f.extension.referenceModel,
        type: 'model',
        via: `field:${f.code}`,
      });
    }
  }

  // Also check parent model references
  const model = idx.models.get(modelCode);
  if (model?.extension?.parentModel) {
    forward.push({
      code: model.extension.parentModel,
      type: 'model',
      via: 'extension.parentModel',
    });
  }

  // Reverse dependencies: who references this model?
  const reverse: DepNode[] = [];

  // 1. REFERENCE fields pointing to this model
  const refFields = idx.referenceFields.get(modelCode) || [];
  for (const f of refFields) {
    const models = idx.bindingsByField.get(f.code) || [];
    for (const mc of models) {
      reverse.push({ code: mc, type: 'model', via: `reference_field:${f.code}` });
    }
  }

  // 2. Commands targeting this model
  const cmds = idx.commandsByModel.get(modelCode) || [];
  for (const c of cmds) {
    reverse.push({ code: c.code, type: 'command', via: 'modelCode' });
  }

  // 3. Pages targeting this model
  const pgs = idx.pagesByModel.get(modelCode) || [];
  for (const p of pgs) {
    reverse.push({ code: p.pageKey, type: 'page', via: 'modelCode' });
  }

  // 4. Child models (parentModel = this model)
  for (const m of idx.raw.models) {
    if (m.extension?.parentModel === modelCode) {
      reverse.push({ code: m.code, type: 'model', via: 'extension.parentModel (child)' });
    }
  }

  // 5. Cascade delete references
  for (const c of idx.raw.commands) {
    if (c.cascadeDelete) {
      for (const cd of c.cascadeDelete) {
        if (cd.childModel === modelCode) {
          reverse.push({ code: c.code, type: 'command', via: 'cascadeDelete' });
        }
      }
    }
  }

  const data: DepsData = { modelCode, forward, reverse };
  const output = successOutput('dsl.deps', data, files.manifest.pluginId);

  if (options.pretty) {
    formatOutput(output, fmt);

    console.log(chalk.bold.cyan(`Dependencies for: ${modelCode}`));
    console.log();

    if (forward.length > 0) {
      printTree(
        chalk.bold('Forward (this model depends on):'),
        forward.map(d => ({
          label: `${chalk.green(d.code)} ${chalk.dim(`[${d.type}]`)} via ${chalk.dim(d.via || '-')}`,
        })),
      );
    } else {
      console.log(chalk.dim('No forward dependencies.'));
    }

    console.log();

    if (reverse.length > 0) {
      // Group by type
      const byType = new Map<string, DepNode[]>();
      for (const d of reverse) {
        if (!byType.has(d.type)) byType.set(d.type, []);
        byType.get(d.type)!.push(d);
      }

      printTree(
        chalk.bold('Reverse (depends on this model):'),
        Array.from(byType.entries()).map(([type, deps]) => ({
          label: `${type}s (${deps.length})`,
          children: deps.map(d => ({ label: `${d.code} ${chalk.dim(`via ${d.via || '-'}`)}` })),
        })),
      );
    } else {
      console.log(chalk.dim('No reverse dependencies.'));
    }

    console.log(`\nTotal: ${forward.length} forward, ${reverse.length} reverse`);
  } else {
    formatOutput(output, fmt);
  }
}
