/**
 * Performance Hooks Module
 *
 * Advanced performance optimization utilities for React components.
 *
 * @since 3.2.0
 */

// Virtual List
export {
  useVirtualList,
  useVariableVirtualList,
  type VirtualListOptions,
  type VirtualListResult,
  type VirtualItem,
  type VariableVirtualListOptions,
} from './useVirtualList';

// Lazy Loading
export {
  useLazyLoad,
  useLazyData,
  useLazyComponent,
  type LazyLoadOptions,
  type LazyLoadResult,
  type LazyDataOptions,
  type LazyDataResult,
  type LazyComponentOptions,
} from './useLazyLoad';

// Memoization
export {
  useDeepMemo,
  useDeepCallback,
  useSelector,
  usePrevious,
  useStableCallback,
  useThrottledCallback,
  useDebouncedCallback,
  useDebouncedValue,
  useDeepMemoObject,
  useCache,
} from './useMemoized';

// Performance Monitoring
export {
  usePerformance,
  useRenderTiming,
  useMemoryUsage,
  useFPS,
  useNetworkTiming,
  type PerformanceMetric,
  type PerformanceReport,
  type RenderTiming,
} from './usePerformance';
