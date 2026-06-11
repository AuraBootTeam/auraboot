package com.auraboot.framework.bpm.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

@DisplayName("BpmnProcessVersionUtil — version injection")
class BpmnProcessVersionUtilTest {

    private static String processName(String xml) throws Exception {
        Document doc =
                DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(new InputSource(new StringReader(xml)));
        return doc.getElementsByTagName("process")
                .item(0)
                .getAttributes()
                .getNamedItem("name")
                .getNodeValue();
    }

    @Test
    @DisplayName("a raw > in the name attribute does not corrupt version injection")
    void rawGtInNameDoesNotCorruptInjection() throws Exception {
        // Woodstox leaves '>' literal in attribute values; the regex must not mistake it
        // for the tag close. (Regression: the old (<process\s+[^>]*)(>) regex inserted the
        // version attribute mid-name here, producing malformed XML.)
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\">\n"
                        + "  <process id=\"p\" name=\"alarm > 80 &amp; low &lt; 3\" isExecutable=\"true\">\n"
                        + "    <startEvent id=\"s\"/>\n"
                        + "  </process>\n"
                        + "</definitions>\n";

        String out = BpmnProcessVersionUtil.ensureProcessVersion(xml, "1.0.0");

        // Still well-formed and the version landed on the process element.
        assertThat(out).contains("version=\"1.0.0\"");
        Document doc =
                DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(new InputSource(new StringReader(out)));
        assertThat(
                        doc.getElementsByTagName("process")
                                .item(0)
                                .getAttributes()
                                .getNamedItem("version")
                                .getNodeValue())
                .isEqualTo("1.0.0");
        // The name survived intact (not truncated at the raw '>').
        assertThat(processName(out)).isEqualTo("alarm > 80 & low < 3");
    }

    @Test
    @DisplayName("a plain name still gets a version (control)")
    void plainNameStillVersioned() {
        String xml = "<definitions><process id=\"p\" name=\"plain\" isExecutable=\"true\"/></definitions>";
        assertThat(BpmnProcessVersionUtil.ensureProcessVersion(xml, "2.0.0"))
                .contains("<process version=\"2.0.0\" id=\"p\"");
    }

    @Test
    @DisplayName("already-versioned XML is returned unchanged")
    void alreadyVersionedUnchanged() {
        String xml = "<definitions><process id=\"p\" version=\"3.0.0\" name=\"x\"/></definitions>";
        assertThat(BpmnProcessVersionUtil.ensureProcessVersion(xml, "9.9.9")).isEqualTo(xml);
    }
}
