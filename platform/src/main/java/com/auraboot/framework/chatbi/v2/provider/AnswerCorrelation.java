package com.auraboot.framework.chatbi.v2.provider;

/**
 * Thread-local correlation carrying the {@code answerPid} + {@code conversationPid}
 * the audit row should be stamped with. The orchestrator (W4-M2.3 ChatBiAnswerService)
 * sets this immediately before calling {@link LlmProvider#translate} and clears it
 * in a finally block.
 *
 * <p>Modelled as a thread-local so the {@link LlmProvider} SPI signature does
 * not have to grow two extra parameters that 95% of call sites would forward
 * verbatim. A test that never sets correlation sees {@link #BLANK} and the
 * audit row simply lacks the linkage — observability degrades gracefully
 * rather than failing the call.
 */
public final class AnswerCorrelation {

    private static final ThreadLocal<AnswerCorrelation> CURRENT = new ThreadLocal<>();
    private static final AnswerCorrelation BLANK = new AnswerCorrelation(null, null);

    private final String answerPid;
    private final String conversationPid;

    private AnswerCorrelation(String answerPid, String conversationPid) {
        this.answerPid = answerPid;
        this.conversationPid = conversationPid;
    }

    public String answerPid() {
        return answerPid;
    }

    public String conversationPid() {
        return conversationPid;
    }

    /** Returns the live correlation, or {@link #BLANK} if none was set. */
    public static AnswerCorrelation current() {
        AnswerCorrelation c = CURRENT.get();
        return c != null ? c : BLANK;
    }

    public static void set(String answerPid, String conversationPid) {
        CURRENT.set(new AnswerCorrelation(answerPid, conversationPid));
    }

    public static void clear() {
        CURRENT.remove();
    }
}
