import type { Config } from '@react-router/dev/config';
import 'react-router';

export default {
  future: {
    v8_middleware: true,
  },
  routeDiscovery: { mode: 'initial' },
  ssr: true,
} satisfies Config;
