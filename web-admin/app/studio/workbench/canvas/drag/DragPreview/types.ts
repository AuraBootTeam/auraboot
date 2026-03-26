export interface DragPreviewProps {
  type: string;
  name: string;
  icon: string;
  /** Whether this is a field being dragged (vs a component) */
  isField?: boolean;
  /** Original field code if dragging from field library */
  fieldCode?: string;
}
