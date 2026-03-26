package com.auraboot.framework.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.auraboot.framework.feedback.dao.entity.Feedback;
import com.auraboot.framework.feedback.dao.entity.FeedbackComment;
import com.auraboot.framework.feedback.dao.entity.FeedbackVote;
import com.auraboot.framework.feedback.dao.mapper.FeedbackCommentMapper;
import com.auraboot.framework.feedback.dao.mapper.FeedbackMapper;
import com.auraboot.framework.feedback.dao.mapper.FeedbackVoteMapper;
import com.auraboot.framework.feedback.dto.*;
import com.auraboot.framework.feedback.service.impl.FeedbackServiceImpl;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doAnswer;

/**
 * Unit tests for FeedbackServiceImpl.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock
    private FeedbackMapper feedbackMapper;

    @Mock
    private FeedbackVoteMapper feedbackVoteMapper;

    @Mock
    private FeedbackCommentMapper feedbackCommentMapper;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private FeedbackServiceImpl feedbackService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(100L);
        testUser.setNickName("TestUser");
        testUser.setEmail("test@example.com");
    }

    // --- createFeedback ---

    @Test
    void createFeedback_success() {
        when(feedbackMapper.insert(any(Feedback.class))).thenReturn(1);
        when(userMapper.selectById(100L)).thenReturn(testUser);
        when(feedbackVoteMapper.selectCount(any())).thenReturn(0L);
        when(feedbackCommentMapper.selectCount(any())).thenReturn(0L);

        CreateFeedbackRequest req = new CreateFeedbackRequest();
        req.setType("feature");
        req.setTitle("Add dark mode");
        req.setDescription("Please add dark mode support");
        req.setPriority("high");

        FeedbackResponse resp = feedbackService.createFeedback(100L, req);

        assertThat(resp).isNotNull();
        assertThat(resp.getType()).isEqualTo("feature");
        assertThat(resp.getTitle()).isEqualTo("Add dark mode");
        assertThat(resp.getStatus()).isEqualTo("open");
        assertThat(resp.getPriority()).isEqualTo("high");
        assertThat(resp.getVoteCount()).isEqualTo(0);
        assertThat(resp.getUserName()).isEqualTo("TestUser");

        verify(feedbackMapper).insert(any(Feedback.class));
    }

    @Test
    void createFeedback_invalidType_throws() {
        CreateFeedbackRequest req = new CreateFeedbackRequest();
        req.setType("invalid");
        req.setTitle("Test");

        assertThatThrownBy(() -> feedbackService.createFeedback(100L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid feedback type");
    }

    @Test
    void createFeedback_invalidPriority_throws() {
        CreateFeedbackRequest req = new CreateFeedbackRequest();
        req.setType("bug");
        req.setTitle("Test");
        req.setPriority("urgent");

        assertThatThrownBy(() -> feedbackService.createFeedback(100L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid priority");
    }

    @Test
    void createFeedback_defaultPriority() {
        when(feedbackMapper.insert(any(Feedback.class))).thenReturn(1);
        when(userMapper.selectById(100L)).thenReturn(testUser);
        when(feedbackVoteMapper.selectCount(any())).thenReturn(0L);
        when(feedbackCommentMapper.selectCount(any())).thenReturn(0L);

        CreateFeedbackRequest req = new CreateFeedbackRequest();
        req.setType("bug");
        req.setTitle("Something broken");
        // No priority set - should default to MEDIUM

        FeedbackResponse resp = feedbackService.createFeedback(100L, req);
        assertThat(resp.getPriority()).isEqualTo("medium");
    }

    // --- getFeedbackById ---

    @Test
    void getFeedbackById_success() {
        Feedback fb = buildFeedback(1L, "feature", "open");
        when(feedbackMapper.selectById(1L)).thenReturn(fb);
        when(userMapper.selectById(100L)).thenReturn(testUser);
        when(feedbackVoteMapper.selectCount(any())).thenReturn(0L);
        when(feedbackCommentMapper.selectCount(any())).thenReturn(0L);

        FeedbackResponse resp = feedbackService.getFeedbackById(1L, 100L);
        assertThat(resp.getId()).isEqualTo(1L);
        assertThat(resp.getTitle()).isEqualTo("Test feedback");
    }

    @Test
    void getFeedbackById_notFound_throws() {
        when(feedbackMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> feedbackService.getFeedbackById(999L, 100L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getFeedbackById_deleted_throws() {
        Feedback fb = buildFeedback(1L, "bug", "open");
        fb.setDeletedFlag(true);
        when(feedbackMapper.selectById(1L)).thenReturn(fb);

        assertThatThrownBy(() -> feedbackService.getFeedbackById(1L, 100L))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --- toggleVote ---

    @Test
    void toggleVote_addVote() {
        Feedback fb = buildFeedback(1L, "feature", "open");
        fb.setVoteCount(5);
        when(feedbackMapper.selectById(1L)).thenReturn(fb);
        when(feedbackVoteMapper.selectOne(any())).thenReturn(null);
        when(feedbackVoteMapper.insert(any(FeedbackVote.class))).thenReturn(1);
        when(feedbackMapper.updateById(any(Feedback.class))).thenReturn(1);

        boolean result = feedbackService.toggleVote(1L, 100L);

        assertThat(result).isTrue();
        verify(feedbackVoteMapper).insert(any(FeedbackVote.class));
        // vote_count should be incremented to 6
        assertThat(fb.getVoteCount()).isEqualTo(6);
    }

    @Test
    void toggleVote_removeVote() {
        Feedback fb = buildFeedback(1L, "feature", "open");
        fb.setVoteCount(5);
        when(feedbackMapper.selectById(1L)).thenReturn(fb);

        FeedbackVote existingVote = new FeedbackVote();
        existingVote.setId(10L);
        existingVote.setFeedbackId(1L);
        existingVote.setUserId(100L);
        when(feedbackVoteMapper.selectOne(any())).thenReturn(existingVote);
        when(feedbackVoteMapper.deleteById(10L)).thenReturn(1);
        when(feedbackMapper.updateById(any(Feedback.class))).thenReturn(1);

        boolean result = feedbackService.toggleVote(1L, 100L);

        assertThat(result).isFalse();
        verify(feedbackVoteMapper).deleteById(10L);
        assertThat(fb.getVoteCount()).isEqualTo(4);
    }

    @Test
    void toggleVote_feedbackNotFound_throws() {
        when(feedbackMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> feedbackService.toggleVote(999L, 100L))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --- updateStatus ---

    @Test
    void updateStatus_success() {
        Feedback fb = buildFeedback(1L, "bug", "open");
        when(feedbackMapper.selectById(1L)).thenReturn(fb);
        when(feedbackMapper.updateById(any(Feedback.class))).thenReturn(1);
        // toResponse(feedback, null) still resolves userName and commentCount
        when(userMapper.selectById(100L)).thenReturn(testUser);
        when(feedbackCommentMapper.selectCount(any())).thenReturn(0L);

        UpdateFeedbackStatusRequest req = new UpdateFeedbackStatusRequest();
        req.setStatus("in_progress");

        FeedbackResponse resp = feedbackService.updateStatus(1L, req);
        assertThat(resp.getStatus()).isEqualTo("in_progress");
    }

    @Test
    void updateStatus_invalidStatus_throws() {
        UpdateFeedbackStatusRequest req = new UpdateFeedbackStatusRequest();
        req.setStatus("invalid");

        assertThatThrownBy(() -> feedbackService.updateStatus(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid status");
    }

    // --- addComment ---

    @Test
    void addComment_success() {
        Feedback fb = buildFeedback(1L, "feature", "open");
        when(feedbackMapper.selectById(1L)).thenReturn(fb);
        when(feedbackCommentMapper.insert(any(FeedbackComment.class))).thenReturn(1);
        when(userMapper.selectById(100L)).thenReturn(testUser);

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("Great idea!");

        CommentResponse resp = feedbackService.addComment(1L, 100L, req);

        assertThat(resp).isNotNull();
        assertThat(resp.getContent()).isEqualTo("Great idea!");
        assertThat(resp.getUserName()).isEqualTo("TestUser");
        verify(feedbackCommentMapper).insert(any(FeedbackComment.class));
    }

    @Test
    void addComment_feedbackNotFound_throws() {
        when(feedbackMapper.selectById(999L)).thenReturn(null);

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("Comment on missing feedback");

        assertThatThrownBy(() -> feedbackService.addComment(999L, 100L, req))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --- getComments ---

    @Test
    void getComments_returnsOrdered() {
        FeedbackComment c1 = new FeedbackComment();
        c1.setId(1L);
        c1.setFeedbackId(1L);
        c1.setUserId(100L);
        c1.setContent("First comment");
        c1.setCreatedAt(new Date());

        FeedbackComment c2 = new FeedbackComment();
        c2.setId(2L);
        c2.setFeedbackId(1L);
        c2.setUserId(100L);
        c2.setContent("Second comment");
        c2.setCreatedAt(new Date());

        when(feedbackCommentMapper.selectList(any())).thenReturn(List.of(c1, c2));
        when(userMapper.selectById(100L)).thenReturn(testUser);

        List<CommentResponse> comments = feedbackService.getComments(1L);

        assertThat(comments).hasSize(2);
        assertThat(comments.get(0).getContent()).isEqualTo("First comment");
        assertThat(comments.get(1).getContent()).isEqualTo("Second comment");
    }

    // --- deleteFeedback ---

    @Test
    void deleteFeedback_success() {
        Feedback fb = buildFeedback(1L, "bug", "open");
        when(feedbackMapper.selectById(1L)).thenReturn(fb);
        when(feedbackMapper.updateById(any(Feedback.class))).thenReturn(1);

        feedbackService.deleteFeedback(1L);

        assertThat(fb.getDeletedFlag()).isTrue();
        verify(feedbackMapper).updateById(fb);
    }

    @Test
    void deleteFeedback_notFound_throws() {
        when(feedbackMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> feedbackService.deleteFeedback(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --- listFeedback ---

    @Test
    void listFeedback_withFilters() {
        // MyBatis selectPage modifies the passed Page object in place,
        // so we use doAnswer to populate it
        Feedback fb = buildFeedback(1L, "bug", "open");
        doAnswer(invocation -> {
            Page<Feedback> page = invocation.getArgument(0);
            page.setRecords(List.of(fb));
            page.setTotal(1);
            return page;
        }).when(feedbackMapper).selectPage(any(Page.class), any(QueryWrapper.class));

        when(userMapper.selectById(100L)).thenReturn(testUser);
        when(feedbackVoteMapper.selectCount(any())).thenReturn(0L);
        when(feedbackCommentMapper.selectCount(any())).thenReturn(0L);

        Page<FeedbackResponse> result = feedbackService.listFeedback(
                1, 10, "bug", "open", "voteCount", "desc", 100L);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getType()).isEqualTo("bug");
        assertThat(result.getTotal()).isEqualTo(1);
    }

    // --- Helpers ---

    private Feedback buildFeedback(Long id, String type, String status) {
        Feedback fb = new Feedback();
        fb.setId(id);
        fb.setPid("test_pid_" + id);
        fb.setUserId(100L);
        fb.setType(type);
        fb.setTitle("Test feedback");
        fb.setDescription("Test description");
        fb.setStatus(status);
        fb.setPriority("medium");
        fb.setVoteCount(0);
        fb.setCreatedAt(new Date());
        fb.setUpdatedAt(new Date());
        fb.setDeletedFlag(false);
        return fb;
    }
}
