import type { Config } from '@react-router/dev/config';
import 'react-router';

export default {
  appDirectory: process.env.AURA_REACT_ROUTER_APP_DIR || 'app',
  buildDirectory: process.env.AURA_REACT_ROUTER_BUILD_DIR || 'build',
  future: {
    v8_middleware: true,
  },
  routeDiscovery: { mode: 'initial' },
  ssr: true,
} satisfies Config;
