/**
 * CommandRegistry — placeholder for M2.
 *
 * The full Command system (with hook chains, pre/post middleware, and
 * audit) lives in the existing app/services. This kernel-side registry
 * will eventually replace it as plugins migrate to using PluginContext
 * for command registration.
 *
 * For M2, this is a thin facade over the action map maintained by
 * PluginLoader. M3+ will move command resolution here.
 */

export const COMMAND_REGISTRY_PLACEHOLDER = true
