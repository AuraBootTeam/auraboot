import client from 'prom-client';

// Collect default Node.js metrics: event loop lag, GC, heap, active handles
client.collectDefaultMetrics({
  prefix: 'bff_',
  gcDurationBuckets: [0.001, 0.01, 0.1, 1, 2, 5],
});

// HTTP proxy duration (BFF → backend)
export const proxyDurationHistogram = new client.Histogram({
  name: 'bff_proxy_duration_seconds',
  help: 'Duration of proxied requests from BFF to backend',
  labelNames: ['method', 'path', 'status'] as const,
  buckets: [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5],
});

// SSR render duration
export const ssrDurationHistogram = new client.Histogram({
  name: 'bff_ssr_render_duration_seconds',
  help: 'Duration of SSR rendering',
  labelNames: ['route'] as const,
  buckets: [0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5],
});

// Active SSR renders gauge
export const activeSsrRenders = new client.Gauge({
  name: 'bff_ssr_active_renders',
  help: 'Number of SSR renders currently in progress',
});

export const register = client.register;
