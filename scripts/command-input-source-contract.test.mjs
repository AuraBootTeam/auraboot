import { test } from "node:test";
import assert from "node:assert/strict";
import { commandInputSourceErrors } from "./command-input-source-contract.mjs";
const action = (source) => ({
  action: "command.execute",
  args: { command: "test:create", inputFieldsSource: source },
});
for (const endpoint of [
  "/api/forms/item",
  "/api/forms/${state.pid}",
  "${state.formEndpoint}",
])
  test(`admits resolved or deferred endpoint ${endpoint}`, () =>
    assert.deepEqual(
      commandInputSourceErrors(
        action({
          endpoint,
          payloadBindings: { expectedRevision: "snapshot.revision" },
        }),
      ),
      [],
    ));
for (const source of [
  null,
  [],
  {},
  { endpoint: "http://[" },
  { endpoint: "//foreign/api/forms" },
  { endpoint: "/api/../outside" },
  { endpoint: "https://foreign/api/forms" },
  { endpoint: "/api/forms#x" },
  { endpoint: "/api/forms", extra: true },
  {
    endpoint: "/api/forms",
    payloadBindings: { expectedRevision: "__proto__.revision" },
  },
  { endpoint: "/api/forms", payloadBindings: { constructor: "revision" } },
])
  test(`rejects malformed source ${JSON.stringify(source)}`, () =>
    assert.ok(commandInputSourceErrors(action(source)).length));
test("rejects even null static fields combined with a source", () =>
  assert.ok(
    commandInputSourceErrors({
      ...action({ endpoint: "/api/forms" }),
      args: {
        inputFieldsSource: { endpoint: "/api/forms" },
        inputFields: null,
      },
    }).length,
  ));
for (const type of ["command", "bulk", "state_transition"])
  test(`rejects unsupported ${type} adapter`, () =>
    assert.ok(
      commandInputSourceErrors({
        type,
        inputFieldsSource: { endpoint: "/api/forms" },
      }).length,
    ));
test("leaves existing static commands and unrelated objects alone", () => {
  assert.deepEqual(
    commandInputSourceErrors({ type: "command", inputFields: [] }),
    [],
  );
  assert.deepEqual(
    commandInputSourceErrors({ action: "navigate", args: { path: "/" } }),
    [],
  );
});

test("rejects a legacy bulk source nested in args", () =>
  assert.ok(
    commandInputSourceErrors({
      type: "bulk_command",
      args: { inputFieldsSource: { endpoint: "/api/forms" } },
    }).length,
  ));
