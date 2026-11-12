/** Static admission for the two dynamic command form consumers. Runtime validates resolved values. */
const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
const object = (value) =>
  value !== null && typeof value === "object" && !Array.isArray(value);
const forbidden = new Set(["__proto__", "prototype", "constructor"]);
const safePath = (value) =>
  typeof value === "string" &&
  /^[\w$]+(?:\.[\w$]+)*$/.test(value) &&
  !value.split(".").some((key) => forbidden.has(key));

export function commandInputSourceErrors(node) {
  if (!object(node)) return [];
  const verb = node.action === "command.execute";
  const args = verb
    ? node.args
    : own(node, "inputFieldsSource")
      ? node
      : node.args;
  if (!object(args) || !own(args, "inputFieldsSource")) return [];
  if (!verb)
    return [
      "inputFieldsSource requires the command.execute action dispatcher; legacy command, bulk and table adapters do not consume it.",
    ];
  const source = args.inputFieldsSource;
  const errors = [];
  if (own(args, "inputFields"))
    errors.push("inputFieldsSource and inputFields are mutually exclusive.");
  if (
    !object(source) ||
    Object.keys(source).some(
      (key) => !["endpoint", "payloadBindings"].includes(key),
    )
  )
    return [
      ...errors,
      "inputFieldsSource must contain only endpoint and optional payloadBindings.",
    ];
  if (typeof source.endpoint !== "string" || !source.endpoint.trim())
    errors.push("inputFieldsSource.endpoint must be a nonempty string.");
  else {
    const endpoint = source.endpoint;
    // Template expressions are resolved once by the dispatcher, then checked again at runtime.
    const probe = endpoint.replace(/\$\{[^}]+\}/g, "value");
    if (/[\\\u0000-\u0020#]/.test(probe))
      errors.push("inputFieldsSource.endpoint contains unsafe URL characters.");
    else if (!/^\$\{[^}]+\}$/.test(endpoint)) {
      try {
        const url = new URL(probe, "https://aura.invalid");
        if (
          !probe.startsWith("/api/") ||
          url.origin !== "https://aura.invalid" ||
          !url.pathname.startsWith("/api/")
        )
          errors.push(
            "inputFieldsSource.endpoint must resolve to a same-origin /api/ URL.",
          );
      } catch {
        errors.push("inputFieldsSource.endpoint must be a valid URL.");
      }
    }
  }
  if (
    source.payloadBindings !== undefined &&
    (!object(source.payloadBindings) ||
      Object.entries(source.payloadBindings).some(
        ([key, path]) =>
          !/^[A-Za-z_$][\w$]*$/.test(key) ||
          forbidden.has(key) ||
          !safePath(path),
      ))
  )
    errors.push(
      "inputFieldsSource.payloadBindings must map safe payload keys to explicit response paths.",
    );
  return errors;
}
