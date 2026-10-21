package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.entity.NotificationSendLog;
import com.auraboot.framework.notification.mapper.NotificationSendLogMapper;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import org.springframework.transaction.TransactionDefinition;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic orchestration counterexamples. Real PostgreSQL restart/atomicity is covered separately. */
class WechatSubscriptionDeliveryJournalTest {
    private final NotificationSendLogMapper ledger=mock(NotificationSendLogMapper.class);
    private final ReliableIntegrationAccessor reliable=mock(ReliableIntegrationAccessor.class);
    private final WechatSubscriptionMessageSender sender=mock(WechatSubscriptionMessageSender.class);
    private final LocalTransactions transactions=new LocalTransactions();
    private final WechatSubscriptionDeliveryJournal journal=new WechatSubscriptionDeliveryJournal(ledger,reliable,sender,transactions);
    private final WechatSubscriptionMessageSender.Prepared prepared=new WechatSubscriptionMessageSender.Prepared(
            new WechatSubscriptionGrantResolver.Grant(42,"ref","user","app","identity","openid","template","grant","reservation",
                Instant.parse("2026-10-13T01:00:00Z"),Map.of("thing1","value")),"config","developer","http://localhost");
    private NotificationSendLog row;
    @BeforeEach void fixture() {
        MetaContext.setContext(42L,12L,"actor","actor");row=new NotificationSendLog();row.setTenantId(42L);
        row.setDeliveryKey("key");row.setRequestHash("hash");row.setStatus("pending");
        row.setRequestSnapshot(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).valueToTree(prepared).toString());
        when(ledger.findDurable(42,"key")).thenAnswer(call->row);
        when(ledger.claimDurable(eq(42L),eq("key"),eq("hash"),anyString())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();row.setStatus("attempting");return 1;
        });
        when(ledger.finishDurable(eq(42L),eq("key"),eq("hash"),anyString(),anyString(),any(),any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();row.setStatus(call.getArgument(4));return 1;
        });
    }
    @AfterEach void cleanup(){MetaContext.clear();TransactionSynchronizationManager.clear();}
    static class LocalTransactions extends AbstractPlatformTransactionManager {
        int commits;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object value,TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){commits++;}
        protected void doRollback(DefaultTransactionStatus status){}
    }
    @Test void transportStartsOnlyAfterCommittedClaimOutsideDbTransaction() {
        when(sender.sendPrepared(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(transactions.commits).isGreaterThanOrEqualTo(2);
            return new WechatSubscriptionMessageSender.Result(WechatSubscriptionMessageSender.Status.PROVIDER_ACCEPTED,null,0L);
        });
        assertThat(journal.attempt(42,"key").getStatus()).isEqualTo("accepted");
        verify(sender).sendPrepared(any());verify(ledger).finishDurable(eq(42L),eq("key"),eq("hash"),anyString(),eq("accepted"),eq(0L),isNull());
    }
    @Test void terminalRowsFromFreshServiceCannotReopenOrCallTransport() {
        for(String terminal:new String[]{"accepted","rejected","unknown","not_attempted"}) {
            row.setStatus(terminal);
            var restarted=new WechatSubscriptionDeliveryJournal(ledger,reliable,sender,transactions);
            assertThat(restarted.attempt(42,"key").getStatus()).isEqualTo(terminal);
        }
        verify(ledger,never()).claimDurable(anyLong(),anyString(),anyString(),anyString());verifyNoInteractions(sender);
    }
    @Test void lostConcurrentClaimPerformsZeroTransportCalls() {
        when(ledger.claimDurable(eq(42L),eq("key"),eq("hash"),anyString())).thenReturn(0);
        journal.attempt(42,"key");verifyNoInteractions(sender);verify(ledger,never()).finishDurable(anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any());
    }
    @Test void tokenNotAttemptedAndMessageUnknownRemainDifferentTerminalFacts() {
        when(sender.sendPrepared(any())).thenReturn(new WechatSubscriptionMessageSender.Result(
                WechatSubscriptionMessageSender.Status.NOT_ATTEMPTED,WechatSubscriptionMessageSender.Reason.TOKEN_FAILED,null));
        assertThat(journal.attempt(42,"key").getStatus()).isEqualTo("not_attempted");
        row.setStatus("pending");when(sender.sendPrepared(any())).thenReturn(new WechatSubscriptionMessageSender.Result(
                WechatSubscriptionMessageSender.Status.OUTCOME_UNKNOWN,WechatSubscriptionMessageSender.Reason.SEND_UNKNOWN,null));
        assertThat(journal.attempt(42,"key").getStatus()).isEqualTo("unknown");
    }
    @Test void finishPersistenceFailureLeavesAttemptingAndDoesNotIssueAnotherSend() {
        when(sender.sendPrepared(any())).thenReturn(new WechatSubscriptionMessageSender.Result(WechatSubscriptionMessageSender.Status.PROVIDER_ACCEPTED,null,0L));
        when(ledger.finishDurable(eq(42L),eq("key"),eq("hash"),anyString(),anyString(),any(),any())).thenThrow(new IllegalStateException("synthetic persistence failure"));
        assertThatThrownBy(()->journal.attempt(42,"key")).hasMessage("synthetic persistence failure");
        var restarted=new WechatSubscriptionDeliveryJournal(ledger,reliable,sender,transactions);
        assertThat(restarted.attempt(42,"key").getStatus()).isEqualTo("attempting");verify(sender,times(1)).sendPrepared(any());
    }
    @Test void outerTransactionAndForeignTenantCannotReachNetworkOrLedger() {
        assertThatThrownBy(()->journal.attempt(43,"key")).hasMessage("NOTIFICATION_TENANT_MISMATCH");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(()->journal.attempt(42,"key")).hasMessage("NOTIFICATION_NETWORK_OUTSIDE_TRANSACTION_REQUIRED");
        verifyNoInteractions(ledger,sender);
    }
    @Test void publicTransportCannotBypassDurableJournal() throws Exception {
        assertThat(java.lang.reflect.Modifier.isPublic(WechatSubscriptionMessageSender.class
                .getDeclaredMethod("sendOnce",WechatSubscriptionMessageSender.Request.class).getModifiers())).isFalse();
    }
    @Test void immutableReservationTimeProducesExactReplayEnvelopeAndMissingTimeCannotQueue() {
        var fact=prepared.grant();
        var stable=new WechatSubscriptionMessageSender.Prepared(new WechatSubscriptionGrantResolver.Grant(
                fact.tenantId(),fact.resolutionReference(),fact.userPid(),fact.appId(),fact.identityPid(),fact.openid(),
                fact.templateId(),fact.grantReference(),fact.reservationReference(),fact.expiresAt(),fact.values(),
                Instant.parse("2026-10-13T00:00:00.123456789Z")),prepared.configurationHash(),prepared.miniprogramState(),prepared.apiBase());
        when(sender.prepare(any())).thenReturn(new WechatSubscriptionMessageSender.Preparation(null,stable));
        when(reliable.enqueueIdempotent(any())).thenReturn(true,false);
        var tx=new TransactionTemplate(transactions);
        assertThat(tx.execute(status->journal.enqueue(new WechatSubscriptionMessageSender.Request(42,"ref"))).status())
                .isEqualTo(WechatSubscriptionDeliveryJournal.Status.QUEUED);
        assertThat(tx.execute(status->journal.enqueue(new WechatSubscriptionMessageSender.Request(42,"ref"))).status())
                .isEqualTo(WechatSubscriptionDeliveryJournal.Status.REPLAYED);
        var captured=org.mockito.ArgumentCaptor.forClass(com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope.class);
        verify(reliable,times(2)).enqueueIdempotent(captured.capture());
        assertThat(captured.getAllValues().get(0)).isEqualTo(captured.getAllValues().get(1));
        assertThat(captured.getValue().occurredAt()).isEqualTo(Instant.parse("2026-10-13T00:00:00.123456Z"));
        clearInvocations(reliable);when(sender.prepare(any())).thenReturn(new WechatSubscriptionMessageSender.Preparation(null,prepared));
        assertThat(tx.execute(status->journal.enqueue(new WechatSubscriptionMessageSender.Request(42,"ref"))).reason())
                .isEqualTo(WechatSubscriptionMessageSender.Reason.GRANT_INVALID);
        verifyNoInteractions(reliable);
    }
    @Test void missingConfigurationNeverQueuesOrCreatesIntent() {
        when(sender.prepare(any())).thenReturn(new WechatSubscriptionMessageSender.Preparation(
                new WechatSubscriptionMessageSender.Result(WechatSubscriptionMessageSender.Status.BLOCKED,WechatSubscriptionMessageSender.Reason.DISABLED,null),null));
        assertThat(journal.enqueue(new WechatSubscriptionMessageSender.Request(42,"ref")).status()).isEqualTo(WechatSubscriptionDeliveryJournal.Status.BLOCKED);
        verifyNoInteractions(ledger,reliable);
    }
}
