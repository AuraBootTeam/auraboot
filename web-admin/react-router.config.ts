import type { Config } from '@react-router/dev/config';
import 'react-router';

export default {
  future: {
    unstable_middleware: true, // 👈 Enable middleware
    // ...Other future or unstable flags
  },
  ssr: true,
} satisfies Config;

declare module 'react-router' {
  interface Future {
    unstable_middleware: true; // 👈 Enable middleware types
  }
}
