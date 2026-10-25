package com.auraboot.framework.architecture;

import com.auraboot.framework.faq.*;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import com.auraboot.framework.i18n.service.AiTranslationService;
import com.auraboot.framework.i18n.service.TranslationGenerator;
import com.auraboot.framework.im.service.ImAiResponder;
import com.auraboot.framework.im.service.ImAiService;
import com.auraboot.framework.meta.ai.AiFieldGenerator;
import com.auraboot.framework.meta.ai.AiFieldProcessor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real Spring constructor resolution without agent implementation or infrastructure. */
class OptionalAiConsumerAssemblyTest {
    record Consumer(Class<?> type, Class<?> port) {}

    static Stream<Consumer> consumers() {
        return Stream.of(new Consumer(ImAiService.class, ImAiResponder.class),
                new Consumer(AiFieldProcessor.class, AiFieldGenerator.class),
                new Consumer(AiTranslationService.class, TranslationGenerator.class),
                new Consumer(ConversationFaqExtractionService.class, FaqCandidateExtractor.class),
                new Consumer(FaqCandidateCommandHandler.class, FaqKnowledgePublisher.class));
    }

    @ParameterizedTest
    @MethodSource("consumers")
    void missingPortAllowsTheCoreConsumerToAssemble(Consumer consumer) {
        try (var context = context(consumer)) {
            context.refresh();
            assertThat(context.getBean(consumer.type())).isNotNull();
            assertThat(context.getBeansOfType(consumer.port())).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("consumers")
    void onePortKeepsTheInstalledAdapter(Consumer consumer) {
        try (var context = context(consumer)) {
            Object adapter = mock(consumer.port());
            context.getBeanFactory().registerSingleton("adapter", adapter);
            context.refresh();
            assertThat(context.getBean(consumer.port())).isSameAs(adapter);
            assertThat(context.getBean(consumer.type())).isNotNull();
        }
    }

    @ParameterizedTest
    @MethodSource("consumers")
    void duplicatePortsAreAnAssemblyError(Consumer consumer) {
        try (var context = context(consumer)) {
            context.getBeanFactory().registerSingleton("adapterOne", mock(consumer.port()));
            context.getBeanFactory().registerSingleton("adapterTwo", mock(consumer.port()));
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class);
        }
    }

    private AnnotationConfigApplicationContext context(Consumer consumer) {
        var context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("i18nResources", mock(I18nResourceMapper.class));
        context.getBeanFactory().registerSingleton("faqCandidates", mock(FaqCandidateService.class));
        context.register(consumer.type());
        return context;
    }
}
