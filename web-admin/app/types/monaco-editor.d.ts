declare module 'monaco-editor' {
  export namespace editor {
    export type IStandaloneCodeEditor = any;
    export type IMarkerData = any;
    export type ITextModel = any;
    export type IStandaloneEditorConstructionOptions = any;
  }
  export namespace languages {
    export type CompletionItem = any;
    export type CompletionItemProvider = any;
    export const CompletionItemKind: any;
    export const CompletionItemInsertTextRule: any;
  }
  export interface IRange {
    startLineNumber: number;
    startColumn: number;
    endLineNumber: number;
    endColumn: number;
  }
  const monaco: any;
  export default monaco;
}
