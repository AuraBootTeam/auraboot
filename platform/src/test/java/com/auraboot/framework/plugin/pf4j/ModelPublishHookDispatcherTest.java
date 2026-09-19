package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.event.ModelTablePublishedEvent;
import com.auraboot.framework.plugin.extension.ModelPublishHookExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ModelPublishHookDispatcher Unit Tests")
class ModelPublishHookDispatcherTest {

    private final AuraPluginManager pluginManager = mock(AuraPluginManager.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final ModelPublishHookDispatcher dispatcher =
            new ModelPublishHookDispatcher(pluginManager, dataSource);

    @Test
    @DisplayName("Dispatches every hook with the model code and the transaction connection")
    void dispatchesHooksWithModelCodeAndConnection() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        ModelPublishHookExtension first = mock(ModelPublishHookExtension.class);
        ModelPublishHookExtension second = mock(ModelPublishHookExtension.class);
        when(pluginManager.getExtensionsOfType(ModelPublishHookExtension.class))
                .thenReturn(List.of(first, second));

        dispatcher.onModelTablePublished(new ModelTablePublishedEvent(this, "qo_cost_scenario_common"));

        verify(first).afterModelTablePublished("qo_cost_scenario_common", connection);
        verify(second).afterModelTablePublished("qo_cost_scenario_common", connection);
    }

    @Test
    @DisplayName("A failing hook propagates so the model publish fails closed")
    void failingHookPropagates() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        ModelPublishHookExtension failing = mock(ModelPublishHookExtension.class);
        when(pluginManager.getExtensionsOfType(ModelPublishHookExtension.class))
                .thenReturn(List.of(failing));
        doThrow(new IllegalStateException("guard trigger install failed"))
                .when(failing).afterModelTablePublished(anyString(), eq(connection));

        assertThatThrownBy(() -> dispatcher
                .onModelTablePublished(new ModelTablePublishedEvent(this, "qo_cost_scenario_common")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("guard trigger install failed");
    }

    @Test
    @DisplayName("Without hooks the event is a no-op and no connection is opened")
    void noHooksIsNoOp() throws Exception {
        when(pluginManager.getExtensionsOfType(ModelPublishHookExtension.class))
                .thenReturn(List.of());

        dispatcher.onModelTablePublished(new ModelTablePublishedEvent(this, "any_model"));

        verify(dataSource, never()).getConnection();
    }

    @Test
    @DisplayName("Only the published model code is handed to hooks")
    void handsOutOnlyThePublishedModelCode() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        ModelPublishHookExtension hook = mock(ModelPublishHookExtension.class);
        when(pluginManager.getExtensionsOfType(ModelPublishHookExtension.class))
                .thenReturn(List.of(hook));

        dispatcher.onModelTablePublished(new ModelTablePublishedEvent(this, "other_model"));

        verify(hook).afterModelTablePublished("other_model", connection);
        verify(hook, never()).afterModelTablePublished(eq("qo_cost_scenario_common"), eq(connection));
    }
}
