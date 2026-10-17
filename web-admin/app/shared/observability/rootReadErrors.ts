export const ROOT_DEPENDENCY_UNAVAILABLE = 'root_dependency_unavailable';

/** A dependency outage is distinct from missing configuration or an invalid response. */
export class RootDependencyUnavailableError extends Error {
  constructor(
    readonly dependency: string,
    readonly upstreamStatus: number,
  ) {
    super(`Root dependency ${dependency} is unavailable (${upstreamStatus})`);
    this.name = 'RootDependencyUnavailableError';
  }
}
