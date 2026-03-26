# Design Document - BPMN Save with Metadata

## Overview

This design implements a save dialog that allows users to specify process definition metadata (name, key, version, description, category) when saving a BPMN process. The solution uses a modal dialog component with form validation and integrates with the existing Zustand store and API services.

## Architecture

### Component Structure

```
BPMNDesigner (Main Component)
├── Toolbar (Top bar with name/key inputs)
├── BPMNPalette (Left sidebar)
├── BPMNCanvas (Center canvas)
├── BPMNPropertyPanel (Right sidebar)
└── SaveDialog (NEW - Modal dialog for save operation)
```

### Data Flow

```
User clicks Save Button
    ↓
Open SaveDialog with current metadata
    ↓
User fills/edits form fields
    ↓
User clicks "确定"
    ↓
Validate form inputs
    ↓
Call API (create or update)
    ↓
Update store and toolbar
    ↓
Close dialog and show success message
```

## Components and Interfaces

### 1. SaveDialog Component

**Location**: `app/bpmn-designer/components/SaveDialog.tsx`

**Props Interface**:
```typescript
interface SaveDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (metadata: ProcessMetadata) => Promise<void>;
  initialData: {
    id?: string;
    name: string;
    key: string;
    version?: number;
    description?: string;
    category?: string;
  };
  isNew: boolean; // true if creating new process
}
```

**State**:
```typescript
interface SaveDialogState {
  name: string;
  key: string;
  version: number;
  description: string;
  category: string;
  errors: {
    name?: string;
    key?: string;
    version?: string;
  };
  isSaving: boolean;
}
```

**Validation Rules**:
- **name**: Required, 1-100 characters
- **key**: Required, alphanumeric + underscore + hyphen only, pattern: `/^[a-zA-Z0-9_-]+$/`
- **version**: Positive integer, auto-increment for updates
- **description**: Optional, max 500 characters
- **category**: Optional, max 50 characters

### 2. Updated BPMNDesigner Component

**Changes**:
```typescript
// Add state for dialog
const [showSaveDialog, setShowSaveDialog] = useState(false);

// Modify handleSave to open dialog instead of saving directly
const handleSave = () => {
  // Validate process structure first
  const result = validate();
  if (!result.valid) {
    alert(`流程验证失败:\n${result.errors.map((e) => `- ${e.message}`).join('\n')}`);
    return;
  }
  
  // Open save dialog
  setShowSaveDialog(true);
};

// New handler for actual save operation
const handleSaveWithMetadata = async (metadata: ProcessMetadata) => {
  setSaving(true);
  try {
    const definition = {
      ...metadata,
      nodes,
      edges,
      status: 'draft' as const,
    };

    if (processDefinition?.id) {
      const updated = await updateProcessDefinition(processDefinition.id, definition);
      setProcessDefinition(updated);
    } else {
      const created = await createProcessDefinition(definition);
      setProcessDefinition(created);
    }

    // Update toolbar inputs
    setProcessName(metadata.name);
    setProcessKey(metadata.key);
    
    setDirty(false);
    setShowSaveDialog(false);
    alert('保存成功');
  } catch (error) {
    console.error('保存失败:', error);
    throw error; // Let dialog handle the error
  } finally {
    setSaving(false);
  }
};
```

### 3. ProcessMetadata Type

**Location**: `app/bpmn-designer/types/index.ts`

```typescript
export interface ProcessMetadata {
  id?: string;
  name: string;
  key: string;
  version?: number;
  description?: string;
  category?: string;
}
```

## Data Models

### SaveDialog Form Data

```typescript
interface FormData {
  name: string;          // Required, 1-100 chars
  key: string;           // Required, alphanumeric + _ -
  version: number;       // Auto-managed
  description: string;   // Optional, max 500 chars
  category: string;      // Optional, max 50 chars
}
```

### Validation Errors

```typescript
interface ValidationErrors {
  name?: string;
  key?: string;
  version?: string;
  description?: string;
  category?: string;
}
```

## UI Design

### SaveDialog Layout

```
┌─────────────────────────────────────────┐
│  保存流程定义                    [X]     │
├─────────────────────────────────────────┤
│                                         │
│  流程名称 *                             │
│  ┌───────────────────────────────────┐ │
│  │ 员工请假审批流程                  │ │
│  └───────────────────────────────────┘ │
│                                         │
│  流程标识 *                             │
│  ┌───────────────────────────────────┐ │
│  │ leave_approval_process            │ │
│  └───────────────────────────────────┘ │
│                                         │
│  版本号                                 │
│  ┌───────────────────────────────────┐ │
│  │ 1                    (自动管理)   │ │
│  └───────────────────────────────────┘ │
│                                         │
│  描述                                   │
│  ┌───────────────────────────────────┐ │
│  │                                   │ │
│  │                                   │ │
│  └───────────────────────────────────┘ │
│                                         │
│  分类                                   │
│  ┌───────────────────────────────────┐ │
│  │ 人事管理                          │ │
│  └───────────────────────────────────┘ │
│                                         │
│         [取消]           [确定]         │
└─────────────────────────────────────────┘
```

### Styling

- Modal overlay: Semi-transparent black background
- Dialog: White background, rounded corners, shadow
- Width: 500px (max-width for mobile)
- Padding: 24px
- Input fields: Full width with 8px margin bottom
- Buttons: Right-aligned, 8px gap
- Error messages: Red text below input fields

## Error Handling

### Validation Errors

```typescript
const validateForm = (data: FormData): ValidationErrors => {
  const errors: ValidationErrors = {};
  
  // Name validation
  if (!data.name.trim()) {
    errors.name = '流程名称不能为空';
  } else if (data.name.length > 100) {
    errors.name = '流程名称不能超过100个字符';
  }
  
  // Key validation
  if (!data.key.trim()) {
    errors.key = '流程标识不能为空';
  } else if (!/^[a-zA-Z0-9_-]+$/.test(data.key)) {
    errors.key = '流程标识只能包含字母、数字、下划线和连字符';
  }
  
  // Description validation
  if (data.description && data.description.length > 500) {
    errors.description = '描述不能超过500个字符';
  }
  
  // Category validation
  if (data.category && data.category.length > 50) {
    errors.category = '分类不能超过50个字符';
  }
  
  return errors;
};
```

### API Error Handling

```typescript
try {
  await handleSaveWithMetadata(metadata);
} catch (error) {
  if (error.response?.status === 409) {
    setErrors({ key: '流程标识已存在，请使用其他标识' });
  } else if (error.response?.status === 400) {
    alert('请求数据格式错误，请检查输入');
  } else {
    alert('保存失败，请重试');
  }
  // Keep dialog open for user to fix errors
}
```

## Testing Strategy

### Unit Tests

1. **SaveDialog Component**
   - Renders with initial data
   - Validates required fields
   - Shows error messages
   - Calls onSave with correct data
   - Handles cancel action

2. **Validation Functions**
   - Name validation (empty, too long)
   - Key validation (empty, invalid characters)
   - Description length validation
   - Category length validation

### Integration Tests

1. **Save Flow**
   - Open dialog from save button
   - Fill form and submit
   - Verify API call with correct data
   - Verify store update
   - Verify toolbar update

2. **Error Scenarios**
   - Validation errors prevent save
   - API errors show appropriate messages
   - Cancel closes dialog without saving

### E2E Tests (Playwright)

```typescript
test('save process with metadata', async ({ page }) => {
  // Navigate to designer
  await page.goto('/bpmn-designer');
  
  // Add some nodes
  await page.dragAndDrop('[data-node-type="startEvent"]', '.canvas');
  
  // Click save button
  await page.click('button:has-text("保存")');
  
  // Fill save dialog
  await page.fill('input[name="name"]', '测试流程');
  await page.fill('input[name="key"]', 'test_process');
  await page.fill('textarea[name="description"]', '这是一个测试流程');
  
  // Submit
  await page.click('button:has-text("确定")');
  
  // Verify success
  await expect(page.locator('text=保存成功')).toBeVisible();
});
```

## Implementation Plan

### Phase 1: Create SaveDialog Component
- Create SaveDialog.tsx with form UI
- Implement form state management
- Add validation logic
- Add styling

### Phase 2: Integrate with BPMNDesigner
- Add dialog state to BPMNDesigner
- Modify handleSave to open dialog
- Create handleSaveWithMetadata function
- Update toolbar after save

### Phase 3: Add Keyboard Shortcuts
- Add Ctrl+S handler to open dialog
- Add Enter handler in dialog to submit
- Add ESC handler to close dialog

### Phase 4: Testing
- Write unit tests for validation
- Write integration tests for save flow
- Write E2E tests for user scenarios
- Manual testing on different browsers

### Phase 5: Polish
- Add loading states
- Improve error messages
- Add animations
- Ensure accessibility

## Dependencies

- React (existing)
- Zustand (existing)
- Tailwind CSS (existing)
- React Router (existing)
- No new dependencies required

## Performance Considerations

- Dialog renders only when open (conditional rendering)
- Form validation debounced to avoid excessive re-renders
- API calls use existing service layer (no additional overhead)
- Modal overlay uses CSS for smooth animations

## Accessibility

- Dialog has proper ARIA labels
- Focus management (trap focus in dialog)
- Keyboard navigation (Tab, Enter, ESC)
- Screen reader announcements for errors
- Proper label associations for inputs

## Security Considerations

- Input sanitization on client side
- Server-side validation required
- XSS prevention (React handles by default)
- CSRF protection (existing API layer)

## Future Enhancements

- Auto-save draft functionality
- Version comparison view
- Duplicate process with new key
- Import/export with metadata
- Process template library
