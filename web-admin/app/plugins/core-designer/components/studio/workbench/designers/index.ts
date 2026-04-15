/**
 * Designer Components
 */

export { DesignerRouter, type DesignerRouterProps } from './DesignerRouter';
// BlocksDesigner replaces AreasDesigner (Task 4.4): exports legacy AreasDesigner alias
export {
  BlocksDesigner,
  AreasDesigner,
  type BlocksDesignerProps,
  type AreasDesignerProps,
} from './BlocksDesigner';
export { CanvasEditor, type CanvasEditorProps } from './canvas/CanvasEditor';
