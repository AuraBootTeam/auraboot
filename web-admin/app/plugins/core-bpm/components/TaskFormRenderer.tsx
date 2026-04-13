/**
 * Task Form Renderer - Renders Page DSL forms bound to BPM tasks
 */

import { useState } from 'react';
import { Button } from '~/components/ui/button';
import { Input } from '~/components/ui/input';
import type { TaskFormData } from '../services/bpmFormService';

interface TaskFormRendererProps {
  formData: TaskFormData;
  onSubmit: (data: Record<string, any>) => void;
  onCancel?: () => void;
}

export function TaskFormRenderer({ formData, onSubmit, onCancel }: TaskFormRendererProps) {
  const [values, setValues] = useState<Record<string, any>>(
    formData.forms?.[0]?.initialValues || {},
  );
  const [activeTab, setActiveTab] = useState(0);

  if (!formData.hasForm || !formData.forms?.length) {
    return <div className="p-4 text-center text-gray-500">No form bound to this task</div>;
  }

  const forms = formData.forms;
  const currentForm = forms[activeTab];

  const handleFieldChange = (field: string, value: any) => {
    setValues((prev) => ({ ...prev, [field]: value }));
  };

  const handleSubmit = () => {
    onSubmit(values);
  };

  return (
    <div className="space-y-4">
      {/* Tab bar for multiple forms */}
      {forms.length > 1 && (
        <div className="flex border-b border-gray-200">
          {forms.map((form, index) => (
            <button
              key={index}
              className={`px-4 py-2 text-sm font-medium ${
                activeTab === index
                  ? 'border-b-2 border-blue-600 text-blue-600'
                  : 'text-gray-500 hover:text-gray-700'
              }`}
              onClick={() => setActiveTab(index)}
            >
              {form.formRef}
            </button>
          ))}
        </div>
      )}

      {/* Form content */}
      <div className="rounded-lg border border-gray-200 p-4">
        <div className="mb-4 text-sm text-gray-500">
          Form: {currentForm.formRef} {currentForm.version && `(v${currentForm.version})`}
        </div>

        {/* Dynamic field rendering based on field permissions */}
        {Object.entries(currentForm.initialValues).map(([field, initialValue]) => {
          const permission = currentForm.fieldPermissions[field] || 'editable';
          if (permission === 'hidden') return null;

          return (
            <div key={field} className="mb-4">
              <label className="mb-1 block text-sm font-medium text-gray-700">{field}</label>
              <Input
                type="text"
                value={values[field] ?? initialValue ?? ''}
                onChange={(e) => handleFieldChange(field, e.target.value)}
                disabled={permission === 'readonly'}
                className={
                  permission === 'readonly' ? 'border-gray-200 bg-gray-100 text-gray-500' : ''
                }
              />
            </div>
          );
        })}
      </div>

      {/* Actions */}
      <div className="flex justify-end gap-2">
        {onCancel && (
          <Button variant="outline" onClick={onCancel}>
            Cancel
          </Button>
        )}
        <Button onClick={handleSubmit}>Submit</Button>
      </div>
    </div>
  );
}
