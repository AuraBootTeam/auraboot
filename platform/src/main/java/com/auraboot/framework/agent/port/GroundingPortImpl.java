package com.auraboot.framework.agent.port;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.service.GroundingService;
import com.auraboot.framework.agent.service.GroundingService.GroundingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Enterprise-ai implementation of GroundingPort SPI.
 * Delegates to GroundingService and maps the BIF result to the port's GroundingResult record.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroundingPortImpl implements GroundingPort {

    private static final Set<String> READ_ONLY_INTENTS = Set.of(
            "query", "analyze", "summarize", "compare", "explain", "export", "report", "recommend"
    );

    private final GroundingService groundingService;

    @Override
    public GroundingResult ground(Long tenantId, String userMessage, String pageModel, String recordId) {
        GroundingContext context = GroundingContext.builder()
                .pageModel(pageModel)
                .recordId(recordId)
                .build();

        BusinessIntentFrame bif = groundingService.ground(tenantId, userMessage, context);

        double confidence = bif.getConfidence() != null ? bif.getConfidence().getOverall() : 0.0;
        List<String> candidateSkills = bif.getCandidateSkills() != null ? bif.getCandidateSkills() : List.of();
        boolean readOnly = bif.getIntent() != null && READ_ONLY_INTENTS.contains(bif.getIntent());

        log.debug("GroundingPort: intent={}, object={}, confidence={}, skills={}, readOnly={}",
                bif.getIntent(), bif.getObject(), String.format("%.2f", confidence),
                candidateSkills.size(), readOnly);

        return new GroundingResult(
                bif.getIntent(),
                bif.getObject(),
                confidence,
                candidateSkills,
                readOnly
        );
    }
}
