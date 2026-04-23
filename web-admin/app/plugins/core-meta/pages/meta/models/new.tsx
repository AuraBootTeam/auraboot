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
    <div className="mx-auto max-w-5xl p-6">
      <div className="mb-6">
        <h1 className="mb-2 text-3xl font-bold text-gray-900">新建模型</h1>
        <p className="text-sm text-gray-500">先选择数据来源，再进入对应的创建流程。</p>
      </div>

      <div className="mb-8 rounded-2xl border border-gray-200 bg-white p-6 shadow-sm">
        <div className="mb-5">
          <h2 className="text-lg font-semibold text-gray-900">选择模型来源</h2>
          <p className="mt-1 text-sm text-gray-500">
            物理模型适合平台内标准业务数据，虚拟模型适合外部接口、查询结果或 SQL View。
          </p>
        </div>

        <div className="grid gap-6 md:grid-cols-2">
          <TypeCard
            icon="🗄️"
            title="物理模型"
            description="创建平台内部的业务实体，先建模型壳，再继续配置字段和页面。"
            tag="推荐用于标准 CRUD"
            features={['适合业务主数据', '支持字段与页面持续配置', '创建后进入详情页继续设计']}
            primaryAction="创建物理模型"
            onClick={() => setStep('physical')}
            dataTestId="model-type-physical"
          />
          <TypeCard
            icon="🔗"
            title="虚拟模型"
            description="接入 NamedQuery、Endpoint 或 SQL View，适合只读展示和聚合结果。"
            tag="推荐用于外部/查询数据"
            features={['支持外部或查询结果映射', '向导式配置数据来源与检测 schema', '适合 list/detail 场景']}
            primaryAction="进入虚拟模型向导"
            onClick={() => navigate('/meta/models/new/virtual')}
            dataTestId="model-type-virtual"
          />
        </div>

        <div className="mt-6 rounded-xl border border-gray-200 bg-gray-50 px-4 py-3 text-sm text-gray-600">
          不确定怎么选时：
          <span className="ml-1">内部业务数据选物理模型，外部接口或查询结果选虚拟模型。</span>
        </div>
      </div>

      <div className="mt-6">
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
  tag: string;
  features: string[];
  primaryAction: string;
  onClick: () => void;
  dataTestId: string;
}

function TypeCard({
  icon,
  title,
  description,
  tag,
  features,
  primaryAction,
  onClick,
  dataTestId,
}: TypeCardProps) {
  return (
    <button
      onClick={onClick}
      data-testid={dataTestId}
      className="rounded-2xl border border-gray-200 bg-white p-6 text-left transition-all hover:border-blue-400 hover:shadow-md"
    >
      <div className="mb-3 flex items-center justify-between gap-3">
        <div className="text-4xl">{icon}</div>
        <span className="rounded-full bg-blue-50 px-3 py-1 text-xs font-medium text-blue-700">
          {tag}
        </span>
      </div>
      <h3 className="mb-2 text-lg font-medium text-gray-900">{title}</h3>
      <p className="mb-5 text-sm leading-6 text-gray-600">{description}</p>
      <ul className="mb-6 space-y-2">
        {features.map((f) => (
          <li key={f} className="flex items-center gap-2 text-sm text-gray-500">
            <span className="text-green-500">✓</span> {f}
          </li>
        ))}
      </ul>
      <div className="inline-flex rounded-md bg-gray-900 px-4 py-2 text-sm font-medium text-white">
        {primaryAction}
      </div>
    </button>
  );
}
