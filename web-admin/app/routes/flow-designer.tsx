import FlowDesigner from '~/flow-designer/FlowDesigner';

type MetaArgs = Record<string, unknown>;

export const meta = (_: MetaArgs) => {
  return [
    { title: 'Flow Designer - 表单设计器' },
    { name: 'description', content: '基于React Flow的表单设计器，支持多行多列布局' },
  ];
};

export default function FlowDesignerPage() {
  return (
    <div className="h-screen">
      <FlowDesigner />
    </div>
  );
}
