import type { Config } from '@react-router/dev/config';
import 'react-router';

export default {
  future: {
    v8_middleware: true, // Enable route middleware
    // ...Other future or unstable flags
  },
  ssr: true,
} satisfies Config;

declare module 'react-router' {
  interface Future {
    v8_middleware: true; // Enable middleware types
  }
}
