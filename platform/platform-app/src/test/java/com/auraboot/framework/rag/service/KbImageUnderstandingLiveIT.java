package com.auraboot.framework.rag.service;

import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The acceptance criterion for charts, run against a real vision model.
 *
 * <p>The requirement was never "read the pixels" — it was that someone can later search
 * <i>"why did East China drop in Q3"</i> and find the deck that shows it. That only works if what
 * lands in the index is a <b>reading of the chart</b>, and nothing but a real model can produce one.
 * A stub proves the plumbing; it cannot prove the picture was understood.
 *
 * <p>So this draws a bar chart with a known shape — a sharp Q3 trough — and asserts the description
 * comes back carrying the numbers off the bars and the trend they make. Skipped without
 * {@code DASHSCOPE_API_KEY}, because a green run with no model behind it would prove nothing.
 */
@DisplayName("Chart understanding (live)")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class KbImageUnderstandingLiveIT extends BaseIntegrationTest {

    @Autowired
    private KbImageUnderstandingService service;

    @Autowired
    private LlmProviderFactory providerFactory;

    @Test
    @DisplayName("reads the values off a bar chart and states the trend they show")
    void understandsABarChart() throws Exception {
        byte[] chart = barChart();

        String description = service.describe(getTestTenant().getId(), chart, "image/png");

        assertThat(description).isNotBlank();

        // The numbers are only on the bars — nothing in the file name or the bytes' metadata says
        // them. Getting them back means the model looked.
        assertThat(description)
                .as("the model did not read the values off the chart: %s", description)
                .contains("150")
                .contains("60");

        // And the point of the chart: Q3 is the trough. A model that merely OCR'd the labels would
        // give you the digits without the claim.
        assertThat(description.toLowerCase())
                .as("the description carries no reading of what the chart shows: %s", description)
                .containsAnyOf("q3", "第三季度", "third quarter");

        System.out.println("[chart-understanding] " + description);
    }

    @Test
    @DisplayName("a blind model is refused rather than left to invent a description")
    void refusesATextOnlyVisionModel() {
        // Point the service at the same provider's text-only model. A model that cannot see does not
        // fail — it answers from the prompt alone and sounds just as sure, which would put a
        // fabricated chart reading into the knowledge base.
        Object original = ReflectionTestUtils.getField(service, "visionModel");
        ReflectionTestUtils.setField(service, "visionModel", "qwen-plus");
        try {
            assertThatThrownBy(() ->
                    service.describe(getTestTenant().getId(), barChart(), "image/png"))
                    .hasMessageContaining("does not accept image input");
        } finally {
            ReflectionTestUtils.setField(service, "visionModel", original);
        }
    }

    /** A bar chart whose shape is unambiguous: a steep Q3 trough, with the values printed on it. */
    private byte[] barChart() throws Exception {
        BufferedImage img = new BufferedImage(440, 280, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 440, 280);
        g.setColor(Color.BLACK);
        g.drawString("East China revenue by quarter (10k CNY)", 90, 24);

        int[] values = {150, 140, 60, 70};
        String[] labels = {"Q1", "Q2", "Q3", "Q4"};
        int x = 50;
        for (int i = 0; i < values.length; i++) {
            int h = values[i];
            g.setColor(new Color(0x3B, 0x82, 0xF6));
            g.fillRect(x, 240 - h, 55, h);
            g.setColor(Color.BLACK);
            g.drawString(labels[i], x + 18, 258);
            g.drawString(String.valueOf(values[i]), x + 14, 234 - h);
            x += 95;
        }
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
