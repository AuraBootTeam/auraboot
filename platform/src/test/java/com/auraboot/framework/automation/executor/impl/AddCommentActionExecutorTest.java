package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.meta.service.RecordCommentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddCommentActionExecutorTest {

    @Mock
    private RecordCommentService recordCommentService;

    @InjectMocks
    private AddCommentActionExecutor executor;

    @Test
    void supports_addCommentOnly() {
        assertThat(executor.supports("add_comment")).isTrue();
        assertThat(executor.supports("cc_task")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_usesCurrentAutomationRecordAndReturnsCommentPid() {
        when(recordCommentService.addComment("wd_leave_request", "REQ-1",
                "请假单 REQ-1 已自动补充评论", "ROLE:wd_manager"))
                .thenReturn(Map.of("commentPid", "comment-1"));

        AutomationAction action = AutomationAction.builder()
                .type("add_comment")
                .config(Map.of(
                        "content", "请假单 ${recordPid} 已自动补充评论",
                        "mentions", "ROLE:wd_manager"))
                .build();

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of(
                "modelCode", "wd_leave_request",
                "recordPid", "REQ-1"));

        assertThat(result)
                .containsEntry("success", true)
                .containsEntry("modelCode", "wd_leave_request")
                .containsEntry("recordPid", "REQ-1")
                .containsEntry("content", "请假单 REQ-1 已自动补充评论")
                .containsEntry("mentions", "ROLE:wd_manager")
                .containsEntry("commentPid", "comment-1");
        verify(recordCommentService).addComment("wd_leave_request", "REQ-1",
                "请假单 REQ-1 已自动补充评论", "ROLE:wd_manager");
    }

    @Test
    void execute_requiresContent() {
        AutomationAction action = AutomationAction.builder()
                .type("add_comment")
                .config(Map.of("mentions", "ROLE:wd_manager"))
                .build();

        assertThatThrownBy(() -> executor.execute(action, Map.of(
                "modelCode", "wd_leave_request",
                "recordPid", "REQ-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }
}
