package com.auraboot.framework.faq;

import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Re-distilling the same conversation must be safe.
 *
 * <p>It is not a hypothetical: the queue's "Distil FAQ" button has nothing stopping a reviewer
 * from pressing it twice, and the intended M2 auto-trigger would fire on every close. Two
 * different things have to hold, and they pull in opposite directions:
 *
 * <ul>
 *   <li><b>Drafts are this service's own previous output</b> — regenerate them, or a second run
 *       leaves the reviewer with two copies of every pair to wade through.</li>
 *   <li><b>Anything a human already decided is not the service's to touch</b> — an approved,
 *       published or rejected candidate must survive, and its question must not come back as a
 *       fresh draft. Re-proposing a pair a reviewer already rejected is how a review queue
 *       becomes noise nobody reads.</li>
 * </ul>
 *
 * <p>The orchestration is what is under test here, so the LLM and the data layer are mocked: what
 * matters is <em>which</em> rows get deleted and <em>which</em> pairs get created. The end-to-end
 * proof that this holds against a real database and a real model lives in the golden, which
 * distils the same conversation twice from the browser and asserts the count does not double.
 */
class FaqCandidateServiceIdempotencyTest {

    private static final String CONV_PID = "conv-pid-1";
    private static final long TENANT = 42L;
    private static final String KB_PID = "kb-1";

    private ImConversationMapper conversationMapper;
    private ImMessageMapper messageMapper;
    private ConversationFaqExtractionService extractionService;
    private DynamicDataService dynamicDataService;
    private FaqCandidateService service;

    @BeforeEach
    void setUp() throws Exception {
        conversationMapper = mock(ImConversationMapper.class);
        messageMapper = mock(ImMessageMapper.class);
        extractionService = mock(ConversationFaqExtractionService.class);
        dynamicDataService = mock(DynamicDataService.class);
        service = new FaqCandidateService(conversationMapper, messageMapper, extractionService, dynamicDataService);

        ImConversation conv = new ImConversation();
        conv.setId(7L);
        conv.setPid(CONV_PID);
        conv.setTenantId(TENANT);
        when(conversationMapper.findByPid(CONV_PID, TENANT)).thenReturn(conv);

        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(1L, "human", "退款要多久到账？"),
                message(2L, "agent", "3-5 个工作日。")));

        // create() echoes the row back, the way DynamicDataService does.
        when(dynamicDataService.create(anyString(), any())).thenAnswer(inv -> {
            Map<String, Object> data = new HashMap<>(inv.getArgument(1, Map.class));
            data.put("pid", "new-" + data.get("faq_question"));
            return data;
        });
    }

    private static ImMessage message(long seq, String senderType, String content) {
        ImMessage m = new ImMessage();
        m.setSeq(seq);
        m.setSenderType(senderType);
        m.setContent(content);
        return m;
    }

    private static Map<String, Object> candidate(String pid, String question, String status) {
        Map<String, Object> c = new HashMap<>();
        c.put("pid", pid);
        c.put("faq_question", question);
        c.put("faq_status", status);
        return c;
    }

    /** What findCandidates() sees on this run. */
    private void existingCandidates(Map<String, Object>... rows) {
        PaginationResult<Map<String, Object>> page = new PaginationResult<>();
        page.setRecords(new ArrayList<>(List.of(rows)));
        when(dynamicDataService.list(eq(FaqCandidateService.MODEL), any(DynamicQueryRequest.class)))
                .thenReturn(page);
    }

    private void modelReturns(ExtractedFaq... faqs) throws Exception {
        when(extractionService.extract(anyLong(), anyString())).thenReturn(List.of(faqs));
    }

    @Test
    @DisplayName("re-distilling replaces its own drafts instead of piling up a second copy")
    void reExtract_replacesStaleDrafts() throws Exception {
        existingCandidates(candidate("draft-1", "退款要多久到账？", "draft"));
        modelReturns(new ExtractedFaq("退款要多久到账？", "3-5 个工作日。", 0.9));

        List<Map<String, Object>> created = service.extractFromConversation(TENANT, CONV_PID, KB_PID);

        // The stale draft is dropped...
        verify(dynamicDataService).delete(FaqCandidateService.MODEL, "draft-1");
        // ...and exactly one fresh one takes its place. Two copies of the same pair would make the
        // reviewer read it twice and decide it twice.
        assertEquals(1, created.size(), "one pair in, one candidate out — not two");
        verify(dynamicDataService, times(1)).create(eq(FaqCandidateService.MODEL), any());
    }

    @Test
    @DisplayName("a pair the reviewer already decided is neither deleted nor re-proposed")
    void reExtract_leavesDecidedCandidatesAlone() throws Exception {
        existingCandidates(
                candidate("approved-1", "退款要多久到账？", "approved"),
                candidate("rejected-1", "发票能重开吗？", "rejected"),
                candidate("draft-1", "发货要多久？", "draft"));
        // The model, quite reasonably, distils all three again — it has no idea what a human did.
        modelReturns(
                new ExtractedFaq("退款要多久到账？", "3-5 个工作日。", 0.9),
                new ExtractedFaq("发票能重开吗？", "30 天内可换开。", 0.8),
                new ExtractedFaq("发货要多久？", "48 小时内。", 0.7));

        List<Map<String, Object>> created = service.extractFromConversation(TENANT, CONV_PID, KB_PID);

        // The human's decisions survive untouched — only the service's own draft is regenerated.
        verify(dynamicDataService, never()).delete(FaqCandidateService.MODEL, "approved-1");
        verify(dynamicDataService, never()).delete(FaqCandidateService.MODEL, "rejected-1");
        verify(dynamicDataService).delete(FaqCandidateService.MODEL, "draft-1");

        // And the two already-decided questions do NOT come back as fresh drafts. Re-proposing a
        // pair someone already rejected is how a review queue turns into noise nobody reads.
        assertEquals(1, created.size(), "only the undecided pair is re-proposed");
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataService, times(1)).create(eq(FaqCandidateService.MODEL), cap.capture());
        assertEquals("发货要多久？", cap.getValue().get("faq_question"));
    }

    @Test
    @DisplayName("question matching ignores case and surrounding whitespace")
    void reExtract_decidedMatchIsNormalized() throws Exception {
        existingCandidates(candidate("rejected-1", "  Can I Reissue The Invoice?  ", "rejected"));
        // The model rarely reproduces its own wording byte-for-byte. Matching on the raw string
        // would let a rejected pair back in on nothing more than a stray space.
        modelReturns(new ExtractedFaq("can i reissue the invoice?", "Within 30 days.", 0.8));

        List<Map<String, Object>> created = service.extractFromConversation(TENANT, CONV_PID, KB_PID);

        assertTrue(created.isEmpty(), "a rejected question must not return under a different casing");
        verify(dynamicDataService, never()).create(anyString(), any());
    }

    @Test
    @DisplayName("a conversation with no messages is a no-op, not an LLM call")
    void noMessages_doesNotCallTheModel() throws Exception {
        when(messageMapper.selectList(any())).thenReturn(List.of());
        existingCandidates();

        List<Map<String, Object>> created = service.extractFromConversation(TENANT, CONV_PID, KB_PID);

        assertTrue(created.isEmpty());
        // Paying for a model call on an empty transcript is pure waste, and the auto-trigger would
        // do it on every empty conversation that ever closes.
        verify(extractionService, never()).extract(anyLong(), anyString());
    }
}
