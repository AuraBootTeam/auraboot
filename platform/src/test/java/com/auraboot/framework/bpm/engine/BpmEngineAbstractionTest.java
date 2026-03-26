package com.auraboot.framework.bpm.engine;

import com.auraboot.framework.bpm.engine.adapter.SmartEngineBpmAdapter;
import com.auraboot.framework.bpm.engine.config.BpmAutoConfiguration;
import com.auraboot.framework.bpm.engine.config.BpmProperties;
import com.auraboot.framework.bpm.engine.dto.HistoryRecord;
import com.auraboot.framework.bpm.engine.dto.ProcessInstanceInfo;
import com.auraboot.framework.bpm.engine.dto.ProcessInstanceInfo.ProcessStatus;
import com.auraboot.framework.bpm.engine.dto.TaskInfo;
import com.auraboot.framework.bpm.engine.exception.BpmEngineException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the BPM abstraction layer: SmartEngine adapter, factory, and
 * auto-configuration wiring.
 */
class BpmEngineAbstractionTest {

    // ──────────────────────────────────────────────────────────────────
    // SmartEngine adapter unit tests
    // ──────────────────────────────────────────────────────────────────

    @Nested
    class SmartEngineAdapterTest {

        private SmartEngineBpmAdapter adapter;

        @BeforeEach
        void setUp() {
            adapter = new SmartEngineBpmAdapter();
            adapter.deployProcess("leave-approval", "<bpmn/>");
        }

        @Test
        void engineType_shouldBeSmartEngine() {
            assertThat(adapter.getEngineType()).isEqualTo("smartengine");
        }

        // ── Deploy ────────────────────────────────────────────────────

        @Test
        void deployProcess_withNullKey_shouldThrow() {
            assertThatThrownBy(() -> adapter.deployProcess(null, "<bpmn/>"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void deployProcess_withNullXml_shouldThrow() {
            assertThatThrownBy(() -> adapter.deployProcess("key", null))
                    .isInstanceOf(NullPointerException.class);
        }

        // ── Start ─────────────────────────────────────────────────────

        @Test
        void startProcess_shouldReturnRunningInstance() {
            Map<String, Object> vars = Map.of("days", 3);
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", "BIZ-001", vars);

            assertThat(info.getProcessInstanceId()).isNotBlank();
            assertThat(info.getProcessDefinitionKey()).isEqualTo("leave-approval");
            assertThat(info.getBusinessKey()).isEqualTo("BIZ-001");
            assertThat(info.getStatus()).isEqualTo(ProcessStatus.RUNNING);
            assertThat(info.getVariables()).containsEntry("days", 3);
            assertThat(info.getStartTime()).isNotNull();
        }

        @Test
        void startProcess_withUndeployedKey_shouldThrow() {
            assertThatThrownBy(() -> adapter.startProcess("unknown", null, Map.of()))
                    .isInstanceOf(BpmEngineException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void startProcess_withNullVariables_shouldSucceed() {
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", null, null);
            assertThat(info.getStatus()).isEqualTo(ProcessStatus.RUNNING);
            assertThat(info.getVariables()).isNotNull().isEmpty();
        }

        // ── Get instance ──────────────────────────────────────────────

        @Test
        void getProcessInstance_shouldReturnCorrectInstance() {
            ProcessInstanceInfo started = adapter.startProcess("leave-approval", null, Map.of());
            ProcessInstanceInfo fetched = adapter.getProcessInstance(started.getProcessInstanceId());
            assertThat(fetched.getProcessInstanceId()).isEqualTo(started.getProcessInstanceId());
        }

        @Test
        void getProcessInstance_unknownId_shouldThrow() {
            assertThatThrownBy(() -> adapter.getProcessInstance("nonexistent"))
                    .isInstanceOf(BpmEngineException.class)
                    .hasMessageContaining("not found");
        }

        // ── Suspend / Resume ──────────────────────────────────────────

        @Test
        void suspendProcess_shouldChangeStat() {
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", null, Map.of());
            adapter.suspendProcess(info.getProcessInstanceId());
            assertThat(adapter.getProcessInstance(info.getProcessInstanceId()).getStatus())
                    .isEqualTo(ProcessStatus.SUSPENDED);
        }

        @Test
        void resumeProcess_afterSuspend_shouldBeRunning() {
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", null, Map.of());
            adapter.suspendProcess(info.getProcessInstanceId());
            adapter.resumeProcess(info.getProcessInstanceId());
            assertThat(adapter.getProcessInstance(info.getProcessInstanceId()).getStatus())
                    .isEqualTo(ProcessStatus.RUNNING);
        }

        @Test
        void resumeProcess_withoutSuspend_shouldThrow() {
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", null, Map.of());
            assertThatThrownBy(() -> adapter.resumeProcess(info.getProcessInstanceId()))
                    .isInstanceOf(BpmEngineException.class)
                    .hasMessageContaining("not suspended");
        }

        // ── Cancel ────────────────────────────────────────────────────

        @Test
        void cancelProcess_shouldTerminate() {
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", null, Map.of());
            adapter.cancelProcess(info.getProcessInstanceId());

            ProcessInstanceInfo cancelled = adapter.getProcessInstance(info.getProcessInstanceId());
            assertThat(cancelled.getStatus()).isEqualTo(ProcessStatus.CANCELLED);
            assertThat(cancelled.getEndTime()).isNotNull();
        }

        @Test
        void cancelProcess_alreadyCancelled_shouldThrow() {
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", null, Map.of());
            adapter.cancelProcess(info.getProcessInstanceId());
            assertThatThrownBy(() -> adapter.cancelProcess(info.getProcessInstanceId()))
                    .isInstanceOf(BpmEngineException.class)
                    .hasMessageContaining("already terminated");
        }

        @Test
        void cancelProcess_unknownId_shouldThrow() {
            assertThatThrownBy(() -> adapter.cancelProcess("nonexistent"))
                    .isInstanceOf(BpmEngineException.class);
        }

        // ── Tasks ─────────────────────────────────────────────────────

        @Test
        void getActiveTasks_shouldReturnInitialTask() {
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", null, Map.of());
            List<TaskInfo> tasks = adapter.getActiveTasks(info.getProcessInstanceId());

            assertThat(tasks).hasSize(1);
            assertThat(tasks.get(0).getProcessInstanceId()).isEqualTo(info.getProcessInstanceId());
            assertThat(tasks.get(0).getTaskName()).isEqualTo("Initial Review");
        }

        @Test
        void completeTask_shouldRemoveTask() {
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", null, Map.of());
            List<TaskInfo> tasks = adapter.getActiveTasks(info.getProcessInstanceId());
            String taskId = tasks.get(0).getTaskId();

            adapter.completeTask(taskId, Map.of("approved", true));

            assertThat(adapter.getActiveTasks(info.getProcessInstanceId())).isEmpty();
        }

        @Test
        void completeTask_lastTask_shouldCompleteProcess() {
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", null, Map.of());
            List<TaskInfo> tasks = adapter.getActiveTasks(info.getProcessInstanceId());

            adapter.completeTask(tasks.get(0).getTaskId(), Map.of());

            ProcessInstanceInfo completed = adapter.getProcessInstance(info.getProcessInstanceId());
            assertThat(completed.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
            assertThat(completed.getEndTime()).isNotNull();
        }

        @Test
        void completeTask_unknownId_shouldThrow() {
            assertThatThrownBy(() -> adapter.completeTask("nonexistent", Map.of()))
                    .isInstanceOf(BpmEngineException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void getTasksByAssignee_shouldFilterCorrectly() {
            // No assignee set by default
            assertThat(adapter.getTasksByAssignee("alice")).isEmpty();
        }

        // ── History ───────────────────────────────────────────────────

        @Test
        void getProcessHistory_shouldContainStartEvent() {
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", null, Map.of());
            List<HistoryRecord> history = adapter.getProcessHistory(info.getProcessInstanceId());

            assertThat(history).isNotEmpty();
            assertThat(history.get(0).getActivityName()).isEqualTo("Process Started");
            assertThat(history.get(0).getActivityType()).isEqualTo("startEvent");
        }

        @Test
        void getProcessHistory_afterFullLifecycle_shouldContainAllEvents() {
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", null, Map.of());
            String pid = info.getProcessInstanceId();

            adapter.suspendProcess(pid);
            adapter.resumeProcess(pid);

            List<TaskInfo> tasks = adapter.getActiveTasks(pid);
            adapter.completeTask(tasks.get(0).getTaskId(), Map.of());

            List<HistoryRecord> history = adapter.getProcessHistory(pid);
            assertThat(history).hasSizeGreaterThanOrEqualTo(4); // start, suspend, resume, task, end
        }

        @Test
        void getProcessHistory_unknownInstance_shouldReturnEmpty() {
            assertThat(adapter.getProcessHistory("nonexistent")).isEmpty();
        }

        // ── Cancel clears tasks ───────────────────────────────────────

        @Test
        void cancelProcess_shouldRemoveActiveTasks() {
            ProcessInstanceInfo info = adapter.startProcess("leave-approval", null, Map.of());
            assertThat(adapter.getActiveTasks(info.getProcessInstanceId())).isNotEmpty();

            adapter.cancelProcess(info.getProcessInstanceId());
            assertThat(adapter.getActiveTasks(info.getProcessInstanceId())).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Factory + auto-configuration tests
    // ──────────────────────────────────────────────────────────────────

    @Nested
    class FactoryAndConfigTest {

        @Test
        void factory_shouldResolveSmartEngine() {
            SmartEngineBpmAdapter adapter = new SmartEngineBpmAdapter();
            BpmAutoConfiguration config = new BpmAutoConfiguration();
            BpmEngineFactory factory = config.bpmEngineFactory(List.of(adapter));

            BpmEngine engine = factory.create("smartengine");
            assertThat(engine.getEngineType()).isEqualTo("smartengine");
        }

        @Test
        void factory_unknownType_shouldThrow() {
            SmartEngineBpmAdapter adapter = new SmartEngineBpmAdapter();
            BpmAutoConfiguration config = new BpmAutoConfiguration();
            BpmEngineFactory factory = config.bpmEngineFactory(List.of(adapter));

            assertThatThrownBy(() -> factory.create("camunda"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("camunda")
                    .hasMessageContaining("smartengine");
        }

        @Test
        void properties_defaultEngine_shouldBeSmartEngine() {
            BpmProperties props = new BpmProperties();
            assertThat(props.getEngine()).isEqualTo("smartengine");
        }

        @Test
        void properties_canBeOverridden() {
            BpmProperties props = new BpmProperties();
            props.setEngine("flowable");
            assertThat(props.getEngine()).isEqualTo("flowable");
        }

        @Test
        void bpmEngine_bean_shouldUseConfiguredType() {
            SmartEngineBpmAdapter adapter = new SmartEngineBpmAdapter();
            BpmAutoConfiguration config = new BpmAutoConfiguration();
            BpmEngineFactory factory = config.bpmEngineFactory(List.of(adapter));

            BpmProperties props = new BpmProperties(); // default = smartengine
            BpmEngine primary = config.bpmEngine(factory, props);

            assertThat(primary.getEngineType()).isEqualTo("smartengine");
        }
    }
}
