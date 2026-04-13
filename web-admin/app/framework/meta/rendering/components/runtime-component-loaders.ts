/**
 * Runtime Smart Component Loaders
 *
 * 根据组件元数据自动生成运行时加载器,避免重复维护映射。
 */

import { COMPONENT_RUNTIME_MANIFEST } from '~/framework/meta/registry/components/ComponentRuntimeManifest';
import type { ComponentRuntimeConfig } from '~/framework/meta/registry/components/ComponentConfig';

interface RuntimeEntry {
  loader: () => Promise<Record<string, any>>;
  exportName: string;
  componentName: string;
}

const moduleLoaders: Record<string, () => Promise<Record<string, any>>> = import.meta.glob(
  '../../../components/smart/**/*.tsx',
);

const runtimeEntryMap = new Map<string, RuntimeEntry>();

function registerFromManifest(type: string, runtime: ComponentRuntimeConfig) {
  const loader = moduleLoaders[runtime.modulePath];
  if (!loader) {
    console.warn(`[RuntimeLoader] 未找到组件模块: ${runtime.modulePath} (type: ${type})`);
    return;
  }

  const exportName = runtime.exportName || runtime.componentName || 'default';
  const componentName = runtime.componentName || exportName || type;

  const entry: RuntimeEntry = {
    loader,
    exportName,
    componentName,
  };

  const aliases = new Set<string>();
  aliases.add(componentName);
  aliases.add(componentName.toLowerCase());
  aliases.add(type);
  aliases.add(type.toLowerCase());

  runtime.aliases?.forEach((alias) => {
    aliases.add(alias);
    aliases.add(alias.toLowerCase());
  });

  aliases.forEach((alias) => {
    if (alias) {
      runtimeEntryMap.set(alias, entry);
    }
  });
}

Object.entries(COMPONENT_RUNTIME_MANIFEST).forEach(([type, runtime]) => {
  registerFromManifest(type, runtime);
});

export function getRuntimeComponentEntry(name: string): RuntimeEntry | undefined {
  return runtimeEntryMap.get(name);
}

export function listRuntimeComponentNames(): string[] {
  return Array.from(runtimeEntryMap.keys());
}
