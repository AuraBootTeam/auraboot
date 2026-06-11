package com.auraboot.framework.bpm.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.auraboot.framework.bpm.util.BpmnProcessVersionUtil;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.configuration.ProcessEngineConfiguration;
import com.auraboot.smart.framework.engine.configuration.impl.DefaultProcessEngineConfiguration;
import com.auraboot.smart.framework.engine.configuration.impl.DefaultSmartEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Regression guard for BPMN process/node {@code name} (and edge {@code label}) values that
 * contain XML-special characters such as {@code <}, {@code >}, {@code &}, {@code "}.
 *
 * <p>Context (G3-T2 golden, 2026-06-11): an automation whose name contained {@code >} failed to
 * deploy with a swallowed {@code DeployException: Parse process definition file failure!}. The
 * documented root cause ("the converter doesn't escape {@code >}, so SmartEngine rejects it") was
 * only half right: the active StAX writer (Woodstox) does leave {@code >} literal in attribute
 * values — which is legal XML and SmartEngine accepts — but the deploy-time version-injection
 * regex {@code (<process\s+[^>]*)(>)} then mistook that raw {@code >} for the process tag close
 * and inserted {@code version=...} mid-name, corrupting the XML. The fix lives in
 * {@link BpmnProcessVersionUtil}; these tests prove a name with {@code >} survives convert →
 * version-inject → deploy. The automation name flows only into the process {@code name} attribute
 * (see {@code AutomationFlowCompiler#compile}, which reuses this converter unchanged).
 */
@DisplayName("JsonToBpmnConverter — XML-special chars in name/label")
class JsonToBpmnConverterSpecialCharDeployTest {

    private final JsonToBpmnConverter converter =
            new JsonToBpmnConverter(new ObjectMapper(), null);

    /** name + node label + edge label all carrying XML-special characters incl. {@code >}. */
    private static final String FLOW_JSON =
            """
            {
              "key": "special_char_proc",
              "name": "alarm > 80 & level < 3 \\"hot\\"",
              "nodes": [
                {"id": "start", "type": "startEvent",
                 "data": {"type": "startEvent", "label": "begin > here"}},
                {"id": "task", "type": "userTask",
                 "data": {"type": "userTask", "label": "review < & > \\"x\\"", "config": {}}},
                {"id": "end", "type": "endEvent",
                 "data": {"type": "endEvent", "label": "End"}}
              ],
              "edges": [
                {"id": "e1", "source": "start", "target": "task",
                 "data": {"label": "when a > b & c < d"}},
                {"id": "e2", "source": "task", "target": "end"}
              ]
            }
            """;

    private static Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
    }

    @Test
    @DisplayName("converter output is well-formed and the name with special chars round-trips")
    void converterOutputIsWellFormedAndRoundTrips() throws Exception {
        String bpmn = converter.convert(FLOW_JSON);

        // < & " are escaped; > may be left literal by the active StAX writer (both are valid XML),
        // so we assert on well-formedness + round-trip rather than on a specific representation.
        Document doc = parse(bpmn);
        String parsedName =
                doc.getElementsByTagName("process")
                        .item(0)
                        .getAttributes()
                        .getNamedItem("name")
                        .getNodeValue();
        assertThat(parsedName).isEqualTo("alarm > 80 & level < 3 \"hot\"");
    }

    @Test
    @DisplayName("a name containing > deploys to SmartEngine after safe version injection")
    void nameWithAngleBracketDeploysToSmartEngine() throws Exception {
        // ProcessDeploymentService injects the SmartEngine-required version the same way.
        String bpmn = BpmnProcessVersionUtil.ensureProcessVersion(converter.convert(FLOW_JSON), "1.0.0");

        // The injection must not have corrupted the XML.
        parse(bpmn);

        ProcessEngineConfiguration cfg = new DefaultProcessEngineConfiguration();
        SmartEngine engine = new DefaultSmartEngine();
        engine.init(cfg);

        final String deployable = bpmn;
        assertThatCode(
                        () ->
                                engine.getRepositoryCommandService()
                                        .deploy(
                                                new ByteArrayInputStream(
                                                        deployable.getBytes(StandardCharsets.UTF_8)),
                                                "1"))
                .doesNotThrowAnyException();
    }
}
