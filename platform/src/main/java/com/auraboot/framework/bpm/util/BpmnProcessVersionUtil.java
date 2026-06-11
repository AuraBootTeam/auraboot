package com.auraboot.framework.bpm.util;

import java.util.regex.Matcher;

/**
 * Ensures a generated BPMN {@code <process>} element carries the {@code version}
 * attribute that SmartEngine requires before deployment.
 */
public final class BpmnProcessVersionUtil {

    private BpmnProcessVersionUtil() {}

    /**
     * Inject {@code version="<versionAttr>"} into the first {@code <process>} element when it
     * has no version yet, returning the input unchanged otherwise.
     *
     * <p>The version is inserted as the <em>first</em> attribute, right after {@code <process },
     * rather than before the tag's closing {@code >}. This matters because the active StAX
     * writer on the classpath (Woodstox) leaves a literal {@code >} unescaped in attribute
     * values — {@code >} is legal raw inside an XML attribute — so a process whose {@code name}
     * (or a node label) contains {@code >} produces e.g. {@code name="alarm > 80"}. The previous
     * {@code (<process\s+[^>]*)(>)} regex matched up to that <em>first</em> raw {@code >} inside
     * the name and inserted the version attribute mid-value, corrupting the XML and making
     * SmartEngine fail with a swallowed "Parse process definition file failure!". Anchoring on
     * {@code <process } sidesteps the tag-close entirely. (2026-06-11 G3-T2 golden follow-up.)
     */
    public static String ensureProcessVersion(String bpmnXml, String versionAttr) {
        if (bpmnXml == null) {
            return bpmnXml;
        }
        // Check for an existing version attribute on the content, NOT on the `<?xml version="1.0"?>`
        // declaration — otherwise a double-quoted XML declaration (the JDK StAX writer's style;
        // Woodstox uses single quotes) is mistaken for a process version and injection is skipped,
        // leaving SmartEngine to fail with "empty version".
        String afterProlog = bpmnXml.replaceFirst("^\\s*<\\?xml.*?\\?>", "");
        if (afterProlog.contains("version=\"")) {
            return bpmnXml;
        }
        return bpmnXml.replaceFirst(
                "(<process\\s)", "$1version=\"" + Matcher.quoteReplacement(versionAttr) + "\" ");
    }
}
