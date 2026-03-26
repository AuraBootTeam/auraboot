# Requirements Document

## Introduction

This specification addresses the issue where BPMN node property changes (such as node labels/names) made in the property panel are not immediately reflected in the canvas visualization. Currently, when a user modifies a node's label in the right-side property panel, the change is saved to the Zustand store but the canvas does not update to show the new label until the page is refreshed or the node is moved.

## Glossary

- **BPMN Designer**: The visual workflow designer component that allows users to create and edit BPMN process definitions
- **Canvas**: The central area where BPMN nodes and edges are displayed and can be manipulated using React Flow
- **Property Panel**: The right-side panel that displays and allows editing of properties for selected nodes or edges
- **Zustand Store**: The state management system (useBPMNStore) that holds the current state of nodes, edges, and other designer data
- **React Flow**: The underlying library used for rendering the flow diagram with nodes and edges
- **Node Label**: The text displayed on a node that identifies its purpose (e.g., "用户任务", "审批流程")
- **Sync Mechanism**: The bidirectional data flow between the Zustand store and React Flow's local state

## Requirements

### Requirement 1: Real-time Node Label Updates

**User Story:** As a process designer, I want to see node label changes immediately reflected in the canvas, so that I can verify my changes without needing to refresh or manipulate the node.

#### Acceptance Criteria

1. WHEN a user modifies the "节点标签" field in the property panel, THE Canvas SHALL display the updated label on the node within 100 milliseconds
2. WHEN the node label is updated, THE Canvas SHALL maintain the node's current position and connections
3. WHEN the node label is updated, THE Designer SHALL mark the process as unsaved (isDirty = true)
4. WHEN multiple rapid label changes occur, THE Canvas SHALL debounce updates to prevent performance issues
5. WHEN a node label exceeds the display width, THE Canvas SHALL truncate the text with ellipsis as currently implemented

### Requirement 2: Real-time Node Configuration Updates

**User Story:** As a process designer, I want to see all node configuration changes (not just labels) reflected immediately in the canvas, so that I can understand the complete state of my process.

#### Acceptance Criteria

1. WHEN a user modifies any node configuration field (description, assignee type, priority, etc.), THE Store SHALL update the node data immediately
2. WHEN node configuration changes affect visual display (e.g., assignee type badge on UserTaskNode), THE Canvas SHALL re-render the node to show the changes
3. WHEN configuration changes do not affect visual display, THE Canvas SHALL NOT trigger unnecessary re-renders
4. WHEN a user switches between different nodes, THE Property Panel SHALL display the current state from the store
5. WHEN the store updates node data, THE Canvas SHALL synchronize within 100 milliseconds

### Requirement 3: Bidirectional Synchronization

**User Story:** As a process designer, I want changes from both the property panel and direct canvas manipulation to be synchronized correctly, so that the system maintains data consistency.

#### Acceptance Criteria

1. WHEN a user drags a node to a new position on the canvas, THE Store SHALL update the node position
2. WHEN the store updates node position, THE Canvas SHALL NOT reset other node properties
3. WHEN a user updates a property in the panel, THE Canvas SHALL NOT lose the node's current position
4. WHEN synchronization occurs, THE System SHALL prevent infinite update loops
5. WHEN the isSyncingFromStore flag is true, THE Canvas SHALL NOT trigger store updates

### Requirement 4: Edge Label Updates

**User Story:** As a process designer, I want to see edge label changes immediately reflected in the canvas, so that I can verify connection conditions and labels.

#### Acceptance Criteria

1. WHEN a user modifies the "连线标签" field in the property panel, THE Canvas SHALL display the updated label on the edge within 100 milliseconds
2. WHEN an edge label is updated, THE Canvas SHALL maintain the edge's routing and connection points
3. WHEN an edge label is cleared (empty string), THE Canvas SHALL hide the label display
4. WHEN an edge label is set, THE Canvas SHALL display it with the configured styling (white background, rounded corners)
5. WHEN the store updates edge data, THE Canvas SHALL synchronize the label field to both edge.label and edge.data.label

### Requirement 5: Performance Optimization

**User Story:** As a process designer working with complex workflows, I want the property updates to be performant, so that the designer remains responsive even with many nodes.

#### Acceptance Criteria

1. WHEN a property is updated, THE System SHALL complete the update cycle in less than 100 milliseconds
2. WHEN multiple properties are updated rapidly, THE System SHALL batch updates to prevent excessive re-renders
3. WHEN the canvas has more than 50 nodes, THE System SHALL maintain update performance within 200 milliseconds
4. WHEN synchronization occurs, THE System SHALL only re-render affected nodes, not the entire canvas
5. WHILE a user is typing in an input field, THE System SHALL debounce store updates to every 300 milliseconds

### Requirement 6: Error Handling and Edge Cases

**User Story:** As a process designer, I want the system to handle edge cases gracefully, so that I don't lose work or encounter unexpected behavior.

#### Acceptance Criteria

1. WHEN a node is deleted while its properties are being edited, THE Property Panel SHALL clear and show the default "no selection" message
2. WHEN the store and canvas states become out of sync, THE System SHALL log a warning to the console
3. WHEN a property update fails, THE System SHALL revert to the previous value and show an error message
4. WHEN the user rapidly switches between nodes, THE Property Panel SHALL always display the correct node's properties
5. IF synchronization is blocked by the isSyncingFromStore flag, THEN THE System SHALL retry after the flag is cleared

### Requirement 7: Validation and Feedback

**User Story:** As a process designer, I want immediate feedback when I make changes, so that I know my actions were successful.

#### Acceptance Criteria

1. WHEN a property is successfully updated, THE System SHALL mark the process as unsaved (show "未保存" indicator)
2. WHEN a node label is updated, THE Validation System SHALL re-validate the process if validation was previously run
3. WHEN an invalid value is entered (e.g., negative priority), THE Property Panel SHALL show validation feedback
4. WHEN all changes are saved, THE System SHALL clear the unsaved indicator
5. WHEN the user attempts to leave with unsaved changes, THE System SHALL prompt for confirmation (future enhancement)

## Technical Constraints

- Must maintain compatibility with React Flow v12.8.4
- Must use Zustand with Immer middleware for state management
- Must not break existing drag-and-drop functionality
- Must not introduce memory leaks or performance degradation
- Must maintain the current debounce mechanism for store-to-canvas sync (300ms)
- Must preserve the isSyncingFromStore flag mechanism to prevent infinite loops

## Success Metrics

- Property changes visible in canvas within 100ms (95th percentile)
- Zero data loss during property updates
- No infinite update loops detected in testing
- User satisfaction: "Changes appear immediately" feedback
- Performance: < 200ms update time with 50+ nodes
