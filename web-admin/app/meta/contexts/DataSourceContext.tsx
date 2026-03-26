/**
 * DataSourceContext - 数据源管理器上下文
 *
 * 用于在 React 组件树中共享 DataSourceManager 实例
 * 解决多个组件创建重复 DataSourceManager 导致重复请求的问题
 *
 * 使用场景:
 * - 页面级别提供 DataSourceManager (通过 usePageDataSources)
 * - 所有子组件通过 useDataSourceManager 访问同一实例
 * - SmartSelect, SmartRadio 等组件自动使用 context 中的实例
 */

import { createContext, useContext } from 'react';
import type { ReactNode } from 'react';
import type { DataSourceManager } from '~/meta/runtime/data-pipeline/DataSourceManager';

/**
 * DataSource Context
 */
export const DataSourceContext = createContext<DataSourceManager | null>(null);

/**
 * DataSource Provider Props
 */
export interface DataSourceProviderProps {
  manager: DataSourceManager;
  children: ReactNode;
}

/**
 * DataSource Provider Component
 *
 * @example
 * ```tsx
 * function MyPage() {
 *   const { manager } = usePageDataSources({ ... });
 *
 *   return (
 *     <DataSourceProvider manager={manager}>
 *       <MyForm />
 *     </DataSourceProvider>
 *   );
 * }
 * ```
 */
export function DataSourceProvider({ manager, children }: DataSourceProviderProps) {
  return <DataSourceContext.Provider value={manager}>{children}</DataSourceContext.Provider>;
}

/**
 * Hook to access DataSourceManager from context
 *
 * @throws Error if used outside DataSourceProvider
 *
 * @example
 * ```tsx
 * function MyComponent() {
 *   const manager = useDataSourceManager();
 *
 *   useEffect(() => {
 *     manager.fetch('ds_myData');
 *   }, [manager]);
 *
 *   return <div>...</div>;
 * }
 * ```
 */
export function useDataSourceManager(): DataSourceManager {
  const manager = useContext(DataSourceContext);

  if (!manager) {
    throw new Error(
      'useDataSourceManager must be used within a DataSourceProvider. ' +
        'Wrap your component tree with <DataSourceProvider manager={...}>',
    );
  }

  return manager;
}

/**
 * Hook to optionally access DataSourceManager from context
 * Returns null if not within a provider (no error thrown)
 *
 * @example
 * ```tsx
 * function MyComponent() {
 *   const manager = useDataSourceManagerOptional();
 *
 *   if (manager) {
 *     // Use context manager
 *   } else {
 *     // Create internal manager or use fallback
 *   }
 *
 *   return <div>...</div>;
 * }
 * ```
 */
export function useDataSourceManagerOptional(): DataSourceManager | null {
  return useContext(DataSourceContext);
}
