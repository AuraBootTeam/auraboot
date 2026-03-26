// vite.config.ts
import { reactRouter } from "file:///Users/ghj/work/startup/phenix/AuraMeta/web-admin/node_modules/@react-router/dev/dist/vite.js";
import tailwindcss from "file:///Users/ghj/work/startup/phenix/AuraMeta/web-admin/node_modules/@tailwindcss/vite/dist/index.mjs";
import { defineConfig } from "file:///Users/ghj/work/startup/phenix/AuraMeta/web-admin/node_modules/vite/dist/node/index.js";
import tsconfigPaths from "file:///Users/ghj/work/startup/phenix/AuraMeta/web-admin/node_modules/vite-tsconfig-paths/dist/index.js";
import federation from "file:///Users/ghj/work/startup/phenix/AuraMeta/web-admin/node_modules/@originjs/vite-plugin-federation/dist/index.mjs";
import istanbul from "file:///Users/ghj/work/startup/phenix/AuraMeta/web-admin/node_modules/vite-plugin-istanbul/dist/index.mjs";
var e2eCoverageEnabled = process.env.E2E_COVERAGE === "1";
var vite_config_default = defineConfig({
  plugins: [
    e2eCoverageEnabled && istanbul({
      include: "app/**/*",
      exclude: ["node_modules", "tests", "test-results"],
      extension: [".js", ".ts", ".tsx"],
      requireEnv: false
    }),
    tailwindcss(),
    reactRouter(),
    tsconfigPaths(),
    // Module Federation for plugin hot-loading
    federation({
      name: "aura-host",
      // Remote plugins will be configured dynamically
      remotes: {},
      // Shared dependencies for plugins
      shared: {
        "react": {
          requiredVersion: "^19.0.0"
        },
        "react-dom": {
          requiredVersion: "^19.0.0"
        },
        "react-router": {
          requiredVersion: "7.5.0"
        },
        "zustand": {
          requiredVersion: "^5.0.8"
        },
        "@reduxjs/toolkit": {},
        "lucide-react": {}
      }
    })
  ].filter(Boolean),
  server: {
    host: "0.0.0.0",
    port: Number(process.env.VITE_PORT || 5173),
    strictPort: true,
    proxy: {
      "/api/notifications/stream": {
        target: `http://localhost:${process.env.BFF_PORT || "3500"}`,
        changeOrigin: true,
        secure: false,
        configure: (proxy, options) => {
          console.log(`\u{1F514} Proxying SSE /api/notifications/stream to BFF server`);
          proxy.on("proxyReq", (proxyReq, req, res) => {
            proxyReq.setHeader("Cache-Control", "no-cache");
            proxyReq.setHeader("Connection", "keep-alive");
          });
          proxy.on("proxyRes", (proxyRes, req, res) => {
            proxyRes.headers["cache-control"] = "no-cache";
            proxyRes.headers["x-accel-buffering"] = "no";
          });
        }
      },
      "^/api/": {
        target: `http://localhost:${process.env.BFF_PORT || "3500"}`,
        changeOrigin: true,
        secure: false,
        configure: (proxy, options) => {
          console.log(`\u{1F517} Proxying /api/* requests to BFF server at http://localhost:${process.env.BFF_PORT || "3500"}`);
        }
      }
    }
  },
  build: {
    modulePreload: false,
    target: "esnext",
    minify: "esbuild",
    cssCodeSplit: false
  },
  // SSR: force-bundle CJS packages that don't work with ESM import
  ssr: {
    noExternal: ["gray-matter", "@mdx-js/mdx", "remark-gfm", "rehype-highlight", "reading-time"]
  },
  logLevel: "info"
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcudHMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCIvVXNlcnMvZ2hqL3dvcmsvc3RhcnR1cC9waGVuaXgvQXVyYU1ldGEvd2ViLWFkbWluXCI7Y29uc3QgX192aXRlX2luamVjdGVkX29yaWdpbmFsX2ZpbGVuYW1lID0gXCIvVXNlcnMvZ2hqL3dvcmsvc3RhcnR1cC9waGVuaXgvQXVyYU1ldGEvd2ViLWFkbWluL3ZpdGUuY29uZmlnLnRzXCI7Y29uc3QgX192aXRlX2luamVjdGVkX29yaWdpbmFsX2ltcG9ydF9tZXRhX3VybCA9IFwiZmlsZTovLy9Vc2Vycy9naGovd29yay9zdGFydHVwL3BoZW5peC9BdXJhTWV0YS93ZWItYWRtaW4vdml0ZS5jb25maWcudHNcIjtpbXBvcnQgeyByZWFjdFJvdXRlciB9IGZyb20gXCJAcmVhY3Qtcm91dGVyL2Rldi92aXRlXCI7XG5pbXBvcnQgdGFpbHdpbmRjc3MgZnJvbSBcIkB0YWlsd2luZGNzcy92aXRlXCI7XG5pbXBvcnQgeyBkZWZpbmVDb25maWcgfSBmcm9tIFwidml0ZVwiO1xuaW1wb3J0IHRzY29uZmlnUGF0aHMgZnJvbSBcInZpdGUtdHNjb25maWctcGF0aHNcIjtcbmltcG9ydCBmZWRlcmF0aW9uIGZyb20gXCJAb3JpZ2luanMvdml0ZS1wbHVnaW4tZmVkZXJhdGlvblwiO1xuaW1wb3J0IGlzdGFuYnVsIGZyb20gXCJ2aXRlLXBsdWdpbi1pc3RhbmJ1bFwiO1xuXG5jb25zdCBlMmVDb3ZlcmFnZUVuYWJsZWQgPSBwcm9jZXNzLmVudi5FMkVfQ09WRVJBR0UgPT09IFwiMVwiO1xuXG5leHBvcnQgZGVmYXVsdCBkZWZpbmVDb25maWcoe1xuICBwbHVnaW5zOiBbXG4gICAgZTJlQ292ZXJhZ2VFbmFibGVkICYmXG4gICAgICBpc3RhbmJ1bCh7XG4gICAgICAgIGluY2x1ZGU6IFwiYXBwLyoqLypcIixcbiAgICAgICAgZXhjbHVkZTogW1wibm9kZV9tb2R1bGVzXCIsIFwidGVzdHNcIiwgXCJ0ZXN0LXJlc3VsdHNcIl0sXG4gICAgICAgIGV4dGVuc2lvbjogW1wiLmpzXCIsIFwiLnRzXCIsIFwiLnRzeFwiXSxcbiAgICAgICAgcmVxdWlyZUVudjogZmFsc2UsXG4gICAgICB9KSxcbiAgICB0YWlsd2luZGNzcygpLFxuICAgIHJlYWN0Um91dGVyKCksXG4gICAgdHNjb25maWdQYXRocygpLFxuICAgIC8vIE1vZHVsZSBGZWRlcmF0aW9uIGZvciBwbHVnaW4gaG90LWxvYWRpbmdcbiAgICBmZWRlcmF0aW9uKHtcbiAgICAgIG5hbWU6ICdhdXJhLWhvc3QnLFxuICAgICAgLy8gUmVtb3RlIHBsdWdpbnMgd2lsbCBiZSBjb25maWd1cmVkIGR5bmFtaWNhbGx5XG4gICAgICByZW1vdGVzOiB7fSxcbiAgICAgIC8vIFNoYXJlZCBkZXBlbmRlbmNpZXMgZm9yIHBsdWdpbnNcbiAgICAgIHNoYXJlZDoge1xuICAgICAgICAncmVhY3QnOiB7XG4gICAgICAgICAgcmVxdWlyZWRWZXJzaW9uOiAnXjE5LjAuMCdcbiAgICAgICAgfSxcbiAgICAgICAgJ3JlYWN0LWRvbSc6IHtcbiAgICAgICAgICByZXF1aXJlZFZlcnNpb246ICdeMTkuMC4wJ1xuICAgICAgICB9LFxuICAgICAgICAncmVhY3Qtcm91dGVyJzoge1xuICAgICAgICAgIHJlcXVpcmVkVmVyc2lvbjogJzcuNS4wJ1xuICAgICAgICB9LFxuICAgICAgICAnenVzdGFuZCc6IHtcbiAgICAgICAgICByZXF1aXJlZFZlcnNpb246ICdeNS4wLjgnXG4gICAgICAgIH0sXG4gICAgICAgICdAcmVkdXhqcy90b29sa2l0Jzoge30sXG4gICAgICAgICdsdWNpZGUtcmVhY3QnOiB7fSxcbiAgICAgIH1cbiAgICB9KVxuICBdLmZpbHRlcihCb29sZWFuKSxcbiAgc2VydmVyOiB7XG4gICAgaG9zdDogXCIwLjAuMC4wXCIsXG4gICAgcG9ydDogTnVtYmVyKHByb2Nlc3MuZW52LlZJVEVfUE9SVCB8fCA1MTczKSxcbiAgICBzdHJpY3RQb3J0OiB0cnVlLFxuICAgIHByb3h5OiB7XG4gICAgICAnL2FwaS9ub3RpZmljYXRpb25zL3N0cmVhbSc6IHtcbiAgICAgICAgdGFyZ2V0OiBgaHR0cDovL2xvY2FsaG9zdDoke3Byb2Nlc3MuZW52LkJGRl9QT1JUIHx8ICczNTAwJ31gLFxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXG4gICAgICAgIHNlY3VyZTogZmFsc2UsXG4gICAgICAgIGNvbmZpZ3VyZTogKHByb3h5LCBvcHRpb25zKSA9PiB7XG4gICAgICAgICAgY29uc29sZS5sb2coYFx1RDgzRFx1REQxNCBQcm94eWluZyBTU0UgL2FwaS9ub3RpZmljYXRpb25zL3N0cmVhbSB0byBCRkYgc2VydmVyYCk7XG4gICAgICAgICAgcHJveHkub24oJ3Byb3h5UmVxJywgKHByb3h5UmVxLCByZXEsIHJlcykgPT4ge1xuICAgICAgICAgICAgcHJveHlSZXEuc2V0SGVhZGVyKCdDYWNoZS1Db250cm9sJywgJ25vLWNhY2hlJyk7XG4gICAgICAgICAgICBwcm94eVJlcS5zZXRIZWFkZXIoJ0Nvbm5lY3Rpb24nLCAna2VlcC1hbGl2ZScpO1xuICAgICAgICAgIH0pO1xuICAgICAgICAgIHByb3h5Lm9uKCdwcm94eVJlcycsIChwcm94eVJlcywgcmVxLCByZXMpID0+IHtcbiAgICAgICAgICAgIHByb3h5UmVzLmhlYWRlcnNbJ2NhY2hlLWNvbnRyb2wnXSA9ICduby1jYWNoZSc7XG4gICAgICAgICAgICBwcm94eVJlcy5oZWFkZXJzWyd4LWFjY2VsLWJ1ZmZlcmluZyddID0gJ25vJztcbiAgICAgICAgICB9KTtcbiAgICAgICAgfVxuICAgICAgfSxcbiAgICAgICdeL2FwaS8nOiB7XG4gICAgICAgIHRhcmdldDogYGh0dHA6Ly9sb2NhbGhvc3Q6JHtwcm9jZXNzLmVudi5CRkZfUE9SVCB8fCAnMzUwMCd9YCxcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxuICAgICAgICBzZWN1cmU6IGZhbHNlLFxuICAgICAgICBjb25maWd1cmU6IChwcm94eSwgb3B0aW9ucykgPT4ge1xuICAgICAgICAgIGNvbnNvbGUubG9nKGBcdUQ4M0RcdUREMTcgUHJveHlpbmcgL2FwaS8qIHJlcXVlc3RzIHRvIEJGRiBzZXJ2ZXIgYXQgaHR0cDovL2xvY2FsaG9zdDoke3Byb2Nlc3MuZW52LkJGRl9QT1JUIHx8ICczNTAwJ31gKTtcbiAgICAgICAgfVxuICAgICAgfVxuICAgIH1cbiAgfSxcbiAgYnVpbGQ6IHtcbiAgICBtb2R1bGVQcmVsb2FkOiBmYWxzZSxcbiAgICB0YXJnZXQ6ICdlc25leHQnLFxuICAgIG1pbmlmeTogJ2VzYnVpbGQnLFxuICAgIGNzc0NvZGVTcGxpdDogZmFsc2VcbiAgfSxcbiAgLy8gU1NSOiBmb3JjZS1idW5kbGUgQ0pTIHBhY2thZ2VzIHRoYXQgZG9uJ3Qgd29yayB3aXRoIEVTTSBpbXBvcnRcbiAgc3NyOiB7XG4gICAgbm9FeHRlcm5hbDogWydncmF5LW1hdHRlcicsICdAbWR4LWpzL21keCcsICdyZW1hcmstZ2ZtJywgJ3JlaHlwZS1oaWdobGlnaHQnLCAncmVhZGluZy10aW1lJ10sXG4gIH0sXG4gIGxvZ0xldmVsOiAnaW5mbycsXG59KTtcbiJdLAogICJtYXBwaW5ncyI6ICI7QUFBcVUsU0FBUyxtQkFBbUI7QUFDalcsT0FBTyxpQkFBaUI7QUFDeEIsU0FBUyxvQkFBb0I7QUFDN0IsT0FBTyxtQkFBbUI7QUFDMUIsT0FBTyxnQkFBZ0I7QUFDdkIsT0FBTyxjQUFjO0FBRXJCLElBQU0scUJBQXFCLFFBQVEsSUFBSSxpQkFBaUI7QUFFeEQsSUFBTyxzQkFBUSxhQUFhO0FBQUEsRUFDMUIsU0FBUztBQUFBLElBQ1Asc0JBQ0UsU0FBUztBQUFBLE1BQ1AsU0FBUztBQUFBLE1BQ1QsU0FBUyxDQUFDLGdCQUFnQixTQUFTLGNBQWM7QUFBQSxNQUNqRCxXQUFXLENBQUMsT0FBTyxPQUFPLE1BQU07QUFBQSxNQUNoQyxZQUFZO0FBQUEsSUFDZCxDQUFDO0FBQUEsSUFDSCxZQUFZO0FBQUEsSUFDWixZQUFZO0FBQUEsSUFDWixjQUFjO0FBQUE7QUFBQSxJQUVkLFdBQVc7QUFBQSxNQUNULE1BQU07QUFBQTtBQUFBLE1BRU4sU0FBUyxDQUFDO0FBQUE7QUFBQSxNQUVWLFFBQVE7QUFBQSxRQUNOLFNBQVM7QUFBQSxVQUNQLGlCQUFpQjtBQUFBLFFBQ25CO0FBQUEsUUFDQSxhQUFhO0FBQUEsVUFDWCxpQkFBaUI7QUFBQSxRQUNuQjtBQUFBLFFBQ0EsZ0JBQWdCO0FBQUEsVUFDZCxpQkFBaUI7QUFBQSxRQUNuQjtBQUFBLFFBQ0EsV0FBVztBQUFBLFVBQ1QsaUJBQWlCO0FBQUEsUUFDbkI7QUFBQSxRQUNBLG9CQUFvQixDQUFDO0FBQUEsUUFDckIsZ0JBQWdCLENBQUM7QUFBQSxNQUNuQjtBQUFBLElBQ0YsQ0FBQztBQUFBLEVBQ0gsRUFBRSxPQUFPLE9BQU87QUFBQSxFQUNoQixRQUFRO0FBQUEsSUFDTixNQUFNO0FBQUEsSUFDTixNQUFNLE9BQU8sUUFBUSxJQUFJLGFBQWEsSUFBSTtBQUFBLElBQzFDLFlBQVk7QUFBQSxJQUNaLE9BQU87QUFBQSxNQUNMLDZCQUE2QjtBQUFBLFFBQzNCLFFBQVEsb0JBQW9CLFFBQVEsSUFBSSxZQUFZLE1BQU07QUFBQSxRQUMxRCxjQUFjO0FBQUEsUUFDZCxRQUFRO0FBQUEsUUFDUixXQUFXLENBQUMsT0FBTyxZQUFZO0FBQzdCLGtCQUFRLElBQUksZ0VBQXlEO0FBQ3JFLGdCQUFNLEdBQUcsWUFBWSxDQUFDLFVBQVUsS0FBSyxRQUFRO0FBQzNDLHFCQUFTLFVBQVUsaUJBQWlCLFVBQVU7QUFDOUMscUJBQVMsVUFBVSxjQUFjLFlBQVk7QUFBQSxVQUMvQyxDQUFDO0FBQ0QsZ0JBQU0sR0FBRyxZQUFZLENBQUMsVUFBVSxLQUFLLFFBQVE7QUFDM0MscUJBQVMsUUFBUSxlQUFlLElBQUk7QUFDcEMscUJBQVMsUUFBUSxtQkFBbUIsSUFBSTtBQUFBLFVBQzFDLENBQUM7QUFBQSxRQUNIO0FBQUEsTUFDRjtBQUFBLE1BQ0EsVUFBVTtBQUFBLFFBQ1IsUUFBUSxvQkFBb0IsUUFBUSxJQUFJLFlBQVksTUFBTTtBQUFBLFFBQzFELGNBQWM7QUFBQSxRQUNkLFFBQVE7QUFBQSxRQUNSLFdBQVcsQ0FBQyxPQUFPLFlBQVk7QUFDN0Isa0JBQVEsSUFBSSx3RUFBaUUsUUFBUSxJQUFJLFlBQVksTUFBTSxFQUFFO0FBQUEsUUFDL0c7QUFBQSxNQUNGO0FBQUEsSUFDRjtBQUFBLEVBQ0Y7QUFBQSxFQUNBLE9BQU87QUFBQSxJQUNMLGVBQWU7QUFBQSxJQUNmLFFBQVE7QUFBQSxJQUNSLFFBQVE7QUFBQSxJQUNSLGNBQWM7QUFBQSxFQUNoQjtBQUFBO0FBQUEsRUFFQSxLQUFLO0FBQUEsSUFDSCxZQUFZLENBQUMsZUFBZSxlQUFlLGNBQWMsb0JBQW9CLGNBQWM7QUFBQSxFQUM3RjtBQUFBLEVBQ0EsVUFBVTtBQUNaLENBQUM7IiwKICAibmFtZXMiOiBbXQp9Cg==
