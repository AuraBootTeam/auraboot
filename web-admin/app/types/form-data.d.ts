declare module 'form-data' {
  import { Readable } from 'stream';

  interface AppendOptions {
    filename?: string;
    contentType?: string;
    knownLength?: number;
    header?: string | Record<string, string>;
  }

  class FormData {
    append(key: string, value: unknown, options?: AppendOptions | string): void;
    getHeaders(): Record<string, string>;
    pipe<T extends NodeJS.WritableStream>(destination: T): T;
    getBoundary(): string;
    getBuffer(): Buffer;
    getLengthSync(): number;
    hasKnownLength(): boolean;
    submit(
      params: string | object,
      callback?: (error: Error | null, response: unknown) => void,
    ): unknown;
  }

  export = FormData;
}
