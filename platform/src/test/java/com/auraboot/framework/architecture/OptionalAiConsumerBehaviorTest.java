package com.auraboot.framework.architecture;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.faq.*;
import com.auraboot.framework.i18n.dto.AiTranslateRequest;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import com.auraboot.framework.i18n.service.AiTranslationService;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.service.ImAiService;
import com.auraboot.framework.meta.ai.AiFieldProcessor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Absent-module failures must precede persistence or fabricated successful results. */
class OptionalAiConsumerBehaviorTest {
    @Test
    void translationRejectsBeforeEvenReadingMissingKeys() {
        var resources = mock(I18nResourceMapper.class);
        var service = new AiTranslationService(resources, null);
        assertMissing(() -> service.translate(AiTranslateRequest.builder().targetLocale("ja-JP").build()));
        verifyNoInteractions(resources);
    }

    @Test
    void fieldGenerationReturnsTheExistingFailureEnvelope() {
        var processor = new AiFieldProcessor(null);
        ReflectionTestUtils.setField(processor, "aiEnabled", true);
        var result = processor.process(AiFieldProcessor.AiGenerationRequest.builder().operation("generate").build());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("not installed");
        assertThat(result.getContent()).isNull();
        assertThat(result.getTokensUsed()).isNull();
    }

    @Test
    void faqExtractionRejectsBeforePriorDraftsCanBeDeleted() {
        var conversations = mock(ImConversationMapper.class);
        var messages = mock(ImMessageMapper.class);
        var data = mock(DynamicDataService.class);
        var service = new FaqCandidateService(conversations, messages,
                new ConversationFaqExtractionService(null), data);
        assertMissing(() -> service.extractFromConversation(42L, "conversation", "knowledge-base"));
        verifyNoInteractions(conversations, messages, data);
    }

    @Test
    void evenAnEmptyTranscriptDoesNotPretendExtractionWasInstalled() {
        assertMissing(() -> new ConversationFaqExtractionService(null).extract(42L, ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"faq:publish", "faq:unpublish"})
    void faqPublicationRejectsBeforeReadingOrChangingCandidateState(String command) {
        var candidates = mock(FaqCandidateService.class);
        var data = mock(DataAccessor.class);
        var handler = new FaqCandidateCommandHandler(null, candidates);
        var context = new CommandContext(42L, "core-faq-loop", "faq", command,
                "faq_candidate", "candidate", Map.of(), Map.of(com.auraboot.framework.plugin.extension.CommandHandlerExtension.DATA_ACCESSOR_KEY, data), false);
        assertMissing(() -> handler.execute(context));
        verifyNoInteractions(candidates, data);
    }

    @Test
    void asynchronousResponderHasAnExplicitSynchronousAvailabilityCheck() {
        var service = new ImAiService(null);
        assertMissing(service::requireAvailable);
        assertMissing(() -> service.generateResponse(null, 42L));
    }

    private void assertMissing(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.UnsupportedFeature))
                .hasMessageContaining("not installed");
    }
}
