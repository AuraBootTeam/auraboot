# Requirements Document

## Introduction

This specification addresses the need to allow users to specify flow definition metadata (name, ID, and version) when saving a BPMN process definition. Currently, users can only edit the process name and key in the top toolbar, but there is no explicit control over the ID and version fields during the save operation.

## Glossary

- **BPMN Designer**: The visual workflow designer component for creating BPMN process definitions
- **Process Definition**: A complete BPMN workflow including nodes, edges, and metadata
- **Process Name**: The human-readable display name of the process (e.g., "员工请假审批流程")
- **Process Key**: The unique identifier/code for the process (e.g., "leave_approval_process")
- **Process ID**: The database primary key for the saved process definition
- **Process Version**: The version number of the process definition (incremented on updates)
- **Save Dialog**: A modal dialog that appears when the user clicks the save button
- **Metadata**: The descriptive information about the process (name, key, ID, version, description, category)

## Requirements

### Requirement 1: Save Dialog with Metadata Fields

**User Story:** As a process designer, I want to specify the process name, key, and version when saving, so that I can properly organize and version my process definitions.

#### Acceptance Criteria

1. WHEN the user clicks the "保存" button, THE System SHALL display a save dialog modal
2. WHEN the save dialog opens, THE Dialog SHALL display input fields for process name, process key, version, description, and category
3. WHEN the dialog opens for a new process, THE System SHALL pre-fill the name and key from the toolbar inputs
4. WHEN the dialog opens for an existing process, THE System SHALL pre-fill all fields with current values from the process definition
5. WHEN the user modifies any field in the dialog, THE System SHALL validate the input in real-time

### Requirement 2: Process Name Configuration

**User Story:** As a process designer, I want to set a descriptive name for my process, so that I can easily identify it in lists and reports.

#### Acceptance Criteria

1. WHEN the save dialog displays, THE Dialog SHALL show a "流程名称" input field
2. WHEN the user enters a process name, THE System SHALL accept names between 1 and 100 characters
3. WHEN the user leaves the name field empty, THE System SHALL show a validation error "流程名称不能为空"
4. WHEN the user enters a valid name, THE System SHALL remove any validation errors
5. WHEN the user saves, THE System SHALL store the name in the processDefinition.name field

### Requirement 3: Process Key Configuration

**User Story:** As a process designer, I want to set a unique key for my process, so that it can be referenced programmatically.

#### Acceptance Criteria

1. WHEN the save dialog displays, THE Dialog SHALL show a "流程标识" input field
2. WHEN the user enters a process key, THE System SHALL accept only alphanumeric characters, underscores, and hyphens
3. WHEN the user enters invalid characters, THE System SHALL show a validation error "流程标识只能包含字母、数字、下划线和连字符"
4. WHEN the user leaves the key field empty, THE System SHALL show a validation error "流程标识不能为空"
5. WHEN the user saves, THE System SHALL store the key in the processDefinition.key field

### Requirement 4: Version Management

**User Story:** As a process designer, I want to control the version number of my process, so that I can track changes and maintain version history.

#### Acceptance Criteria

1. WHEN the save dialog displays for a new process, THE Dialog SHALL show version as "1" (read-only or editable)
2. WHEN the save dialog displays for an existing process, THE Dialog SHALL show the current version number
3. WHEN the user saves an existing process, THE System SHALL increment the version by 1 automatically
4. WHEN the user wants to save as a new version, THE System SHALL allow manual version input
5. WHEN the user saves, THE System SHALL store the version in the processDefinition.version field

### Requirement 5: Optional Metadata Fields

**User Story:** As a process designer, I want to add description and category to my process, so that I can provide additional context and organization.

#### Acceptance Criteria

1. WHEN the save dialog displays, THE Dialog SHALL show optional "描述" textarea field
2. WHEN the save dialog displays, THE Dialog SHALL show optional "分类" input field
3. WHEN the user enters a description, THE System SHALL accept up to 500 characters
4. WHEN the user enters a category, THE System SHALL accept up to 50 characters
5. WHEN the user saves, THE System SHALL store description and category in the processDefinition

### Requirement 6: Save Operation with Validation

**User Story:** As a process designer, I want the system to validate my inputs before saving, so that I don't create invalid process definitions.

#### Acceptance Criteria

1. WHEN the user clicks "确定" in the save dialog, THE System SHALL validate all required fields
2. WHEN validation fails, THE System SHALL display error messages and prevent saving
3. WHEN validation succeeds, THE System SHALL close the dialog and execute the save operation
4. WHEN the save operation completes successfully, THE System SHALL show a success message "保存成功"
5. WHEN the save operation fails, THE System SHALL show an error message and keep the dialog open

### Requirement 7: Cancel and Close Behavior

**User Story:** As a process designer, I want to cancel the save operation, so that I can review my process before committing changes.

#### Acceptance Criteria

1. WHEN the user clicks "取消" in the save dialog, THE System SHALL close the dialog without saving
2. WHEN the user clicks the X button or presses ESC, THE System SHALL close the dialog without saving
3. WHEN the dialog closes without saving, THE System SHALL maintain the isDirty state
4. WHEN the dialog closes without saving, THE System SHALL not modify the processDefinition
5. WHEN the user cancels, THE System SHALL return focus to the canvas

### Requirement 8: Update Toolbar After Save

**User Story:** As a process designer, I want the toolbar to reflect the saved values, so that I can see the current process metadata at a glance.

#### Acceptance Criteria

1. WHEN the save operation completes successfully, THE System SHALL update the toolbar name input with the saved name
2. WHEN the save operation completes successfully, THE System SHALL update the toolbar key input with the saved key
3. WHEN the save operation completes successfully, THE System SHALL clear the "未保存" indicator
4. WHEN the save operation completes successfully, THE System SHALL update the processDefinition state
5. WHEN the save operation completes successfully, THE System SHALL set isDirty to false

### Requirement 9: ID Handling for New vs Existing Processes

**User Story:** As a process designer, I want the system to handle IDs correctly for new and existing processes, so that I don't accidentally overwrite existing processes.

#### Acceptance Criteria

1. WHEN saving a new process (no ID), THE System SHALL call createProcessDefinition API
2. WHEN saving an existing process (has ID), THE System SHALL call updateProcessDefinition API with the ID
3. WHEN the API returns a new ID, THE System SHALL store it in the processDefinition state
4. WHEN the user saves a new process, THE System SHALL not display an ID field in the dialog
5. WHEN the user saves an existing process, THE System SHALL display the ID as read-only information

### Requirement 10: Keyboard Shortcuts and Accessibility

**User Story:** As a process designer, I want keyboard shortcuts for saving, so that I can work efficiently.

#### Acceptance Criteria

1. WHEN the user presses Ctrl+S (or Cmd+S on Mac), THE System SHALL open the save dialog
2. WHEN the save dialog is open and the user presses Enter, THE System SHALL attempt to save
3. WHEN the save dialog is open and the user presses ESC, THE System SHALL cancel and close
4. WHEN the dialog opens, THE System SHALL focus on the first input field
5. WHEN the user tabs through fields, THE System SHALL follow a logical order

## Technical Constraints

- Must maintain compatibility with existing BPMNProcessDefinition type
- Must use React Router's form handling or controlled components
- Must integrate with existing validation system
- Must not break existing save functionality
- Must handle API errors gracefully
- Dialog should be responsive and work on mobile devices

## Success Metrics

- Users can successfully save processes with custom metadata
- Validation prevents invalid data from being saved
- Save operation completes within 2 seconds (95th percentile)
- Zero data loss during save operations
- User satisfaction: "Easy to specify process metadata" feedback
