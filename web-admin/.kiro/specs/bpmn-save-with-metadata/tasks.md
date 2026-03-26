# Implementation Plan - BPMN Save with Metadata

## Task List

- [x] 1. Create SaveDialog Component
  - [x] 1.1 Create SaveDialog.tsx component file with basic structure
    - Create component with props interface (isOpen, onClose, onSave, initialData, isNew)
    - Set up form state using useState for all fields (name, key, version, description, category)
    - Set up errors state for validation messages
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_
  
  - [x] 1.2 Implement form UI with all input fields
    - Add modal overlay with semi-transparent background
    - Add dialog container with white background and shadow
    - Add form fields: name (input), key (input), version (read-only input), description (textarea), category (input)
    - Add required field indicators (*)
    - Add cancel and submit buttons
    - _Requirements: 1.2, 2.1, 3.1, 4.1, 5.1, 5.2_
  
  - [x] 1.3 Implement form validation logic
    - Create validateForm function with all validation rules
    - Validate name: required, 1-100 characters
    - Validate key: required, alphanumeric + underscore + hyphen pattern
    - Validate description: optional, max 500 characters
    - Validate category: optional, max 50 characters
    - Display error messages below each field
    - _Requirements: 2.2, 2.3, 2.4, 3.2, 3.3, 3.4, 5.3, 5.4_
  
  - [x] 1.4 Implement form submission handler
    - Create handleSubmit function that validates before calling onSave
    - Show loading state during save operation
    - Handle successful save (close dialog)
    - Handle save errors (keep dialog open, show error)
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
  
  - [x] 1.5 Add dialog close and cancel handlers
    - Implement onClose handler for cancel button
    - Implement ESC key handler to close dialog
    - Implement overlay click handler to close dialog
    - Ensure dialog doesn't modify state when cancelled
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_
  
  - [x] 1.6 Style the SaveDialog component
    - Apply Tailwind CSS classes for layout and spacing
    - Style modal overlay (fixed, inset-0, bg-black/50)
    - Style dialog container (max-w-lg, rounded-lg, shadow-xl)
    - Style input fields (border, rounded, padding)
    - Style buttons (primary blue for submit, secondary gray for cancel)
    - Add focus states and transitions
    - _Requirements: 1.1, 1.2_

- [x] 2. Integrate SaveDialog with BPMNDesigner
  - [x] 2.1 Add dialog state to BPMNDesigner component
    - Add showSaveDialog state (boolean)
    - Add setShowSaveDialog state setter
    - _Requirements: 1.1_
  
  - [x] 2.2 Modify handleSave to open dialog instead of saving directly
    - Keep existing validation logic (validate process structure)
    - Replace direct save with setShowSaveDialog(true)
    - Prepare initial data from current state (processName, processKey, processDefinition)
    - _Requirements: 1.1, 1.4_
  
  - [x] 2.3 Create handleSaveWithMetadata function
    - Accept ProcessMetadata parameter
    - Merge metadata with nodes and edges
    - Call createProcessDefinition or updateProcessDefinition based on ID
    - Update processDefinition state with returned data
    - Update toolbar inputs (processName, processKey)
    - Set isDirty to false
    - Close dialog
    - Show success message
    - _Requirements: 6.3, 6.4, 8.1, 8.2, 8.3, 8.4, 8.5_
  
  - [x] 2.4 Render SaveDialog in BPMNDesigner
    - Add SaveDialog component to JSX
    - Pass isOpen={showSaveDialog}
    - Pass onClose={() => setShowSaveDialog(false)}
    - Pass onSave={handleSaveWithMetadata}
    - Pass initialData with current values
    - Pass isNew={!processDefinition?.id}
    - _Requirements: 1.1, 9.4, 9.5_
  
  - [x] 2.5 Handle version management
    - For new processes, set version to 1
    - For existing processes, increment version by 1 on save
    - Display version as read-only in dialog
    - _Requirements: 4.1, 4.2, 4.3, 4.5_

- [ ] 3. Add ProcessMetadata type definition
  - [ ] 3.1 Add ProcessMetadata interface to types/index.ts
    - Define interface with id, name, key, version, description, category fields
    - Export interface for use in components
    - _Requirements: 2.5, 3.5, 4.5, 5.5_

- [ ] 4. Implement keyboard shortcuts
  - [ ] 4.1 Add Ctrl+S keyboard shortcut to open save dialog
    - Add keydown event listener in BPMNDesigner
    - Check for Ctrl+S (or Cmd+S on Mac)
    - Prevent default browser save behavior
    - Call handleSave to open dialog
    - _Requirements: 10.1_
  
  - [ ] 4.2 Add Enter key handler in SaveDialog
    - Add keydown event listener in dialog
    - Check for Enter key
    - Call handleSubmit if no validation errors
    - _Requirements: 10.2_
  
  - [ ] 4.3 Add ESC key handler in SaveDialog
    - Add keydown event listener in dialog
    - Check for ESC key
    - Call onClose to cancel and close dialog
    - _Requirements: 10.3_
  
  - [ ] 4.4 Implement focus management
    - Focus first input field when dialog opens
    - Trap focus within dialog (prevent tabbing outside)
    - Restore focus to save button when dialog closes
    - _Requirements: 10.4, 10.5_

- [ ] 5. Add error handling and user feedback
  - [ ] 5.1 Handle API errors in handleSaveWithMetadata
    - Catch 409 conflict errors (duplicate key)
    - Catch 400 bad request errors
    - Catch generic errors
    - Display appropriate error messages
    - Keep dialog open on error
    - _Requirements: 6.5_
  
  - [ ] 5.2 Add loading state during save
    - Show loading spinner on submit button
    - Disable form inputs during save
    - Prevent multiple submissions
    - _Requirements: 6.3_
  
  - [ ] 5.3 Add success feedback
    - Show "保存成功" alert after successful save
    - Update "未保存" indicator in toolbar
    - _Requirements: 6.4, 8.3_

- [ ] 6. Testing and validation
  - [ ]* 6.1 Write unit tests for SaveDialog validation
    - Test name validation (empty, too long)
    - Test key validation (empty, invalid characters)
    - Test description length validation
    - Test category length validation
    - _Requirements: 2.2, 2.3, 2.4, 3.2, 3.3, 3.4, 5.3, 5.4_
  
  - [ ]* 6.2 Write integration tests for save flow
    - Test opening dialog from save button
    - Test form submission with valid data
    - Test API call with correct parameters
    - Test store update after save
    - Test toolbar update after save
    - _Requirements: 6.1, 6.2, 6.3, 8.1, 8.2, 8.4_
  
  - [ ]* 6.3 Manual testing on different scenarios
    - Test saving new process
    - Test updating existing process
    - Test validation errors
    - Test API errors
    - Test cancel behavior
    - Test keyboard shortcuts
    - _Requirements: All_

- [ ] 7. Polish and accessibility
  - [ ] 7.1 Add ARIA labels and roles
    - Add role="dialog" to dialog container
    - Add aria-labelledby for dialog title
    - Add aria-describedby for dialog description
    - Add aria-required for required fields
    - Add aria-invalid for fields with errors
    - _Requirements: 10.4_
  
  - [ ] 7.2 Ensure responsive design
    - Test dialog on mobile devices
    - Adjust max-width for small screens
    - Ensure touch-friendly button sizes
    - _Requirements: 1.1_
  
  - [ ] 7.3 Add smooth animations
    - Add fade-in animation for modal overlay
    - Add slide-up animation for dialog
    - Add transition for button hover states
    - _Requirements: 1.1_

## Notes

- All tasks should be implemented incrementally and tested before moving to the next
- Focus on core functionality first (tasks 1-3), then add enhancements (tasks 4-7)
- Optional tasks (marked with *) can be skipped for MVP but are recommended for production
- Each task should include console logging for debugging during development
- Ensure backward compatibility with existing save functionality
