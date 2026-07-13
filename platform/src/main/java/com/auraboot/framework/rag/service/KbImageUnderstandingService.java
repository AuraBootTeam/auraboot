package com.auraboot.framework.rag.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Turns an image into indexable text with a vision model (G2-2).
 *
 * <p>The requirement is <b>charts</b>, not scans. A chart has to be <i>understood</i> — "East China
 * revenue fell to 60 in Q3, down from 140" — and no amount of character recognition gets you there:
 * OCR would return the axis labels and the digits, unattached to any claim. So this asks a vision
 * model what the picture means, and indexes the answer.
 *
 * <p>The model must actually be able to see. If no vision provider is configured, ingestion fails
 * with a message that says so — it does not fall back to the file name or to a blind text model,
 * either of which would put a confident, invented description into the knowledge base.
 *
 * <p>Out of scope: OCR of scanned documents (a photographed contract). That is a different problem
 * with a different tool.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbImageUnderstandingService {

    /** Roughly the ceiling a vision endpoint will take inline; larger images are rejected up front. */
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    static final String PROMPT = """
            You are indexing this image for a company knowledge base, so that employees can later \
            find it by searching in natural language.

            Describe what the image actually shows, in prose, covering:
            - what kind of image it is (bar chart, line chart, table, screenshot, diagram, photo);
            - every concrete label and number you can read, including axis labels, units and legends;
            - the business fact it demonstrates — the trend, comparison or outlier, stated plainly \
            with the numbers that support it.

            State only what is visible. Do not speculate about causes, and do not invent figures. \
            Answer in the language used in the image; if it has no text, answer in English.
            """;

    private final LlmProviderFactory providerFactory;

    /** Provider code for the vision model. Must be one whose configured model can accept images. */
    @Value("${aura.rag.vision.provider:qianwen}")
    private String visionProviderCode;

    /** The vision model itself. The provider's own default model is usually text-only. */
    @Value("${aura.rag.vision.model:qwen-vl-max}")
    private String visionModel;

    @Value("${aura.rag.vision.max-tokens:1024}")
    private int maxTokens;

    /**
     * @param imageBytes the raw image
     * @param mediaType  MIME type — one of image/png, image/jpeg, image/gif, image/webp
     * @return a description of the image, to be chunked and indexed
     * @throws IllegalStateException if no usable vision provider is configured
     */
    public String describe(Long tenantId, byte[] imageBytes, String mediaType) throws Exception {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("The image is empty");
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("The image is too large to analyse (limit "
                    + (MAX_IMAGE_BYTES / 1024 / 1024) + " MB)");
        }

        LlmProviderFactory.ProviderConfig config =
                providerFactory.resolveConfig(tenantId, visionProviderCode);
        if (config == null || config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "No vision provider is configured (aura.rag.vision.provider=" + visionProviderCode
                            + "). Images cannot be added to a knowledge base until one is.");
        }

        String effectiveProviderCode =
                LlmProviderFactory.effectiveProviderCode(visionProviderCode, config);
        LlmProvider provider = providerFactory.getProvider(effectiveProviderCode);
        if (provider == null) {
            throw new IllegalStateException(
                    "Vision provider implementation is not available: " + effectiveProviderCode);
        }

        LlmChatRequest request = LlmChatRequest.builder()
                .providerCode(effectiveProviderCode)
                .model(visionModel)
                .maxTokens(maxTokens)
                .messages(List.of(LlmChatRequest.Message.imageBase64(
                        "user", mediaType, Base64.getEncoder().encodeToString(imageBytes), PROMPT)))
                .build();

        LlmChatResponse response = provider.chat(request, config.getApiKey(), config.getBaseUrl());
        String description = extractText(response);
        if (description == null || description.isBlank()) {
            throw new IllegalStateException(
                    "The vision model returned nothing for this image (" + mediaType + ")");
        }

        log.info("Image understood via {}/{}: {} bytes in, {} chars out",
                effectiveProviderCode, visionModel, imageBytes.length, description.length());
        return description.strip();
    }

    /** The response carries typed content blocks; only the text ones are the description. */
    private static String extractText(LlmChatResponse response) {
        if (response == null || response.getContent() == null) {
            return "";
        }
        return response.getContent().stream()
                .filter(block -> "text".equals(block.getType()))
                .map(LlmChatResponse.ContentBlock::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** MIME type for a doc_type=image file, derived from its name. */
    public static String mediaTypeForFile(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> throw new IllegalArgumentException("Unsupported image type: " + fileName);
        };
    }

    /** The image extensions the platform accepts, as MIME types. */
    public static final Map<String, String> SUPPORTED_IMAGE_MEDIA_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");
}
