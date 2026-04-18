/**
 * Model creation entry — Step 0 type selector.
 *
 * Users land here and pick between a physical Model (backed by a real table,
 * full CRUD) and a virtual Model (read-only wrapper over NamedQuery / HTTP
 * endpoint / SQL view). The physical branch renders the existing form
 * component in-place; the virtual branch navigates to a dedicated wizard
 * route (added in a follow-up task).
 */

import { useState } from 'react';
import { useNavigate } from 'react-router';
import { PhysicalModelForm } from '~/plugins/core-meta/components/PhysicalModelForm';

type Step = 'type' | 'physical';

export default function NewModelPage() {
  const [step, setStep] = useState<Step>('type');
  const navigate = useNavigate();

  if (step === 'physical') {
    return <PhysicalModelForm onCancel={() => setStep('type')} />;
  }

  // Step 0: type selector
  return (
    <div className="mx-auto max-w-4xl p-6">
      <h1 className="text-2xl font-semibold mb-2">新建 Model</h1>
      <p className="text-gray-500 mb-8">选择 Model 类型：</p>

      <div className="grid grid-cols-2 gap-6">
        <TypeCard
          icon="🗄️"
          title="物理 Model"
          description="背后有物理表。支持全功能 CRUD、字段设计、索引配置。"
          features={['全功能 CRUD', '字段设计', '索引 / 触发器', '权限到字段']}
          onClick={() => setStep('physical')}
          dataTestId="model-type-physical"
        />
        <TypeCard
          icon="🔗"
          title="虚拟 Model"
          description="包装 NamedQuery / HTTP Endpoint / SQL View。一期只读。"
          features={['只读 list / detail', '外部数据源', 'Schema 自动检测', '写操作二期启用']}
          onClick={() => navigate('/meta/models/new/virtual')}
          dataTestId="model-type-virtual"
        />
      </div>

      <div className="mt-8">
        <button
          onClick={() => navigate('/meta/models')}
          className="text-sm text-gray-500 hover:text-gray-700"
        >
          ← 返回列表
        </button>
      </div>
    </div>
  );
}

interface TypeCardProps {
  icon: string;
  title: string;
  description: string;
  features: string[];
  onClick: () => void;
  dataTestId: string;
}

function TypeCard({ icon, title, description, features, onClick, dataTestId }: TypeCardProps) {
  return (
    <button
      onClick={onClick}
      data-testid={dataTestId}
      className="rounded-lg border-2 border-gray-200 bg-white p-6 text-left transition-all hover:border-blue-400 hover:shadow-md"
    >
      <div className="text-4xl mb-3">{icon}</div>
      <h3 className="text-lg font-medium text-gray-900 mb-2">{title}</h3>
      <p className="text-sm text-gray-600 mb-4">{description}</p>
      <ul className="space-y-1">
        {features.map((f) => (
          <li key={f} className="text-xs text-gray-500 flex items-center gap-1">
            <span className="text-green-500">✓</span> {f}
          </li>
        ))}
      </ul>
    </button>
  );
}
