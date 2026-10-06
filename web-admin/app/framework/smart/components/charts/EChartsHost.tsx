/**
 * EChartsHost
 *
 * Drop-in wrapper around `echarts-for-react`'s default export, used by every
 * Smart*Chart instead of importing the library directly.
 *
 * echarts-for-react@3.0.6 initializes ECharts asynchronously: it creates a
 * throwaway instance and waits for that instance's `finished` event before
 * issuing its first `setOption`. Under echarts 6 an instance without an
 * option never enters the render pipeline, so `finished` never fires and the
 * first `setOption` never runs — the widget mounts as a correctly-sized but
 * permanently blank container. The stall surfaces whenever the component
 * mounts once with data already ready and no prop changes afterwards
 * (dashboard big-screen / presentation mode); elsewhere later prop updates
 * happen to unblock it through componentDidUpdate.
 *
 * Kicking `updateEChartsOption` once on mount paints the throwaway instance.
 * That paint emits `finished`, which releases the wrapper's own init flow
 * (dispose → re-init → setOption → resize sensor) unchanged. When the
 * wrapper's flow has already completed normally, the instance has an option
 * and the kick is skipped.
 */
import type { EChartsType } from 'echarts';
import { forwardRef, useEffect, useMemo, useRef } from 'react';
import ReactECharts, { type EChartsReactProps } from 'echarts-for-react';

// ReactECharts declares updateEChartsOption as PRIVATE, so a plain
// intersection `ReactECharts & { updateEChartsOption?: ... }` collapses to
// never (private members are invariant under intersection). Drop the
// private member from the host type and re-add it as a public optional.
function markChartReady(instance: EChartsType, ready: boolean): boolean {
  // The wrapper's own finished listener may dispose its temporary instance
  // before ECharts dispatches the remaining listeners for that same event.
  if (instance.isDisposed()) return false;
  const dom = instance.getDom();
  dom.setAttribute('data-chart-ready', String(ready));
  if (!ready) return true;
  const options = instance.getOption() as Record<string, any>;
  const items = (value: any) => Array.isArray(value) ? value : value ? [value] : [];
  const copy = [
    ...items(options.title).map((title) => title.text),
    ...items(options.xAxis).map((axis) => axis.name),
    ...items(options.yAxis).map((axis) => axis.name),
    ...(items(options.legend).length ? items(options.series)
      .filter((series) => ['line', 'bar', 'scatter'].includes(series.type)).map((series) => series.name) : []),
  ].filter((text): text is string => typeof text === 'string' && Boolean(text.trim()));
  dom.setAttribute('data-chart-ui-copy', JSON.stringify(copy));
  return true;
}

type EChartsHostComponent = Omit<ReactECharts, 'updateEChartsOption'> & {
  updateEChartsOption?: () => unknown;
};

const EChartsHost = forwardRef<ReactECharts, EChartsReactProps>(
  function EChartsHost(props, forwardedRef) {
    const componentRef = useRef<ReactECharts | null>(null);
    const events = useMemo(() => ({
      ...props.onEvents,
      rendered: (event: unknown, instance: EChartsType) => {
        if (!markChartReady(instance, false)) return;
        props.onEvents?.rendered?.(event, instance);
      },
      finished: (event: unknown, instance: EChartsType) => {
        if (!markChartReady(instance, true)) return;
        props.onEvents?.finished?.(event, instance);
      },
    }), [props.onEvents]);

    useEffect(() => {
      const component = componentRef.current as EChartsHostComponent | null;
      if (!component || typeof component.updateEChartsOption !== 'function') return;
      let optionMissing: boolean;
      try {
        optionMissing = !component.getEchartsInstance()?.getOption();
      } catch {
        // Some echarts versions signal "option not set yet" by throwing.
        optionMissing = true;
      }
      if (optionMissing) component.updateEChartsOption();
    }, []);

    return (
      <ReactECharts
        ref={(instance) => {
          componentRef.current = instance;
          if (typeof forwardedRef === 'function') forwardedRef(instance);
          else if (forwardedRef) forwardedRef.current = instance;
        }}
        {...props}
        data-chart-ready="false"
        onEvents={events}
        onChartReady={(instance: EChartsType) => {
          if (instance.isDisposed()) return;
          markChartReady(instance, instance.getZr().animation.isFinished());
          props.onChartReady?.(instance);
        }}
      />
    );
  },
);

export default EChartsHost;
