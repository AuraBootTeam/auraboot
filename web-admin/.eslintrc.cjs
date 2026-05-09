module.exports = {
  root: true,
  env: {
    browser: true,
    node: true,
    es2022: true,
  },
  parserOptions: {
    ecmaVersion: "latest",
    sourceType: "module",
    ecmaFeatures: {
      jsx: true,
    },
  },
  settings: {
    react: {
      version: "detect",
    },
  },
  ignorePatterns: ["build/", "node_modules/", ".react-router/"],
  overrides: [
    {
      files: ["**/*.{ts,tsx}"],
      parser: "@typescript-eslint/parser",
      plugins: ["@typescript-eslint", "react-hooks"],
      rules: {
        // Demoted to warn for baseline cleanup — 96 violations across 173 files exist on main.
        // Track and fix in a dedicated cleanup task.
        "@typescript-eslint/no-unused-vars": [
          "warn",
          {
            argsIgnorePattern: "^_",
            varsIgnorePattern: "^_",
            caughtErrorsIgnorePattern: "^_",
          },
        ],
        "no-undef": "off",
        "no-redeclare": "off",
        // Demoted to warn — 1 violation on main; fix in cleanup task.
        "@typescript-eslint/no-redeclare": "warn",
        // Demoted to warn for baseline cleanup — 75 violations across 173 files exist on main.
        // Track and fix in a dedicated cleanup task.
        "no-duplicate-imports": "warn",
        "react-hooks/rules-of-hooks": "error",
        "react-hooks/exhaustive-deps": "warn",
      },
    },

    // ─── Platform red lines ─────────────────────────────────────
    // Plugins must use only the framework public API. Reaching into
    // ~/framework/internal/* or sibling plugins breaks the encapsulation
    // that lets core evolve without coordinating with every plugin.
    {
      files: ["app/plugins/**/*.{ts,tsx}"],
      excludedFiles: ["app/plugins/**/__tests__/**", "app/plugins/README.md"],
      rules: {
        "no-restricted-imports": [
          "error",
          {
            patterns: [
              {
                group: ["~/framework/internal/*", "../**/framework/internal/*"],
                message:
                  "Plugins must not reach into framework internals. Import from '~/framework' (the public barrel) only.",
              },
              {
                group: ["~/plugins/*/!(index|manifest)*"],
                message:
                  "Cross-plugin imports must go through the kernel registries (PluginContext / SlotRegistry / WidgetRegistry). Direct imports of another plugin's internals are forbidden.",
              },
            ],
          },
        ],
      },
    },

    // Core must not import enterprise overlay code. This catches the
    // accidental case where a developer references a path that only
    // exists in the enterprise build (which would break OSS).
    {
      files: ["app/**/*.{ts,tsx}"],
      excludedFiles: ["app/plugins/ent-**", "app/**/__tests__/**"],
      rules: {
        "no-restricted-imports": [
          "error",
          {
            patterns: [
              {
                group: ["**/ent-*/**", "@auraboot/enterprise/*"],
                message:
                  "Core code must not import enterprise overlay modules. Use a slot or extension point instead.",
              },
            ],
          },
        ],
      },
    },
    {
      files: ["**/*.{js,jsx}"],
      plugins: ["react-hooks"],
      rules: {
        "no-undef": "error",
        "no-redeclare": "error",
        "no-duplicate-imports": "error",
        "react-hooks/rules-of-hooks": "error",
        "react-hooks/exhaustive-deps": "warn",
      },
    },

    // ─── Test env-drift gate (Phase 3 — env-scripts-testing v3) ─────────
    // Forbid direct reads of env-contract vars (BE_PORT / VITE_PORT / BFF_PORT
    // / PG_HOST / PG_PORT / PG_USER / PG_DB / PGPASSWORD / BACKEND_URL /
    // PLAYWRIGHT_BASE_URL / BFF_URL) inside tests/. Specs and helpers must
    // import the resolved constants from `tests/helpers/environments` (or call
    // `loadEnv()`) so a single config change reroutes every caller.
    //
    // The canonical loader (`tests/helpers/environments.ts`) and Playwright
    // configs are exempted — they are the only places the raw env values are
    // legitimately read.
    {
      files: ["tests/**/*.{ts,tsx,js,jsx,mjs,cjs}"],
      excludedFiles: [
        "tests/helpers/environments.ts",
        "tests/helpers/playwright-env.ts",
        "tests/helpers/pg-env.ts",
        "tests/**/playwright*.config.ts",
      ],
      rules: {
        "no-restricted-syntax": [
          "error",
          {
            selector:
              "MemberExpression[object.object.name='process'][object.property.name='env'][property.name=/^(BE_PORT|VITE_PORT|BFF_PORT|PG_HOST|PG_PORT|PG_USER|PG_DB|PGPASSWORD|BACKEND_URL|PLAYWRIGHT_BASE_URL|BFF_URL)$/]",
            message:
              "Do not read env-contract vars directly in tests. Import BACKEND_URL / BASE_URL / BFF_URL / PSQL_BASE from 'tests/helpers/environments' (or call loadEnv()).",
          },
        ],
      },
    },
  ],
};
