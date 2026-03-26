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
