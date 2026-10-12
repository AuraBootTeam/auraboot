/** A deliberate user cancellation stops an action flow without error feedback. */
export class ActionCancelledError extends Error {
  constructor() {
    super('User cancelled');
    this.name = 'ActionCancelledError';
  }
}
