import Editor, { loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api.js';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker.js?worker';
import 'monaco-editor/esm/vs/basic-languages/sql/sql.contribution.js';

// Both platform editors use the same bundled instance and same-origin worker.
// This module is loaded only after hydration, keeping Monaco out of SSR.
self.MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};
loader.config({ monaco });

export default Editor;
