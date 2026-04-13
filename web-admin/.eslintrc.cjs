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
        "@typescript-eslint/no-unused-vars": [
          "error",
          {
            argsIgnorePattern: "^_",
            varsIgnorePattern: "^_",
            caughtErrorsIgnorePattern: "^_",
          },
        ],
        "no-undef": "off",
        "no-redeclare": "off",
        "@typescript-eslint/no-redeclare": "error",
        "no-duplicate-imports": "error",
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
  ],
};
