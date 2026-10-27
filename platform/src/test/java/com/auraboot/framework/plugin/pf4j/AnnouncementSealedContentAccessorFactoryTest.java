package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.plugin.extension.AnnouncementSealedContentAccessor.*;
import com.auraboot.framework.plugin.extension.AnnouncementDocumentCanonicalizer;
import com.auraboot.framework.workbench.service.AnnouncementContentSealService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnnouncementSealedContentAccessorFactoryTest {
    final SqlSessionFactory sessions=mock(SqlSessionFactory.class);
    final AnnouncementContentSealService content=mock(AnnouncementContentSealService.class);
    final DataSource source=mock(DataSource.class);final Connection connection=mock(Connection.class);
    final AnnouncementSealedContentAccessorFactory factory=new AnnouncementSealedContentAccessorFactory(sessions,content);
    ConnectionHolder holder;CommandPipelineContext context;PublishedContent proof;
    final ExactVersion expected=new ExactVersion("01ARZ3NDEKTSV4RRFFQ69G5FAV","01ARZ3NDEKTSV4RRFFQ69G5FAW","a".repeat(64),AnnouncementDocumentCanonicalizer.SCHEMA);
    @BeforeEach void setup() {
        MetaContext.setContext(1L,2L,"actor","actor");MetaContext.setMemberId(3L);
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        holder=new ConnectionHolder(connection);holder.setSynchronizedWithTransaction(true);TransactionSynchronizationManager.bindResource(source,holder);
        when(sessions.getConfiguration()).thenReturn(new Configuration(new Environment("unit",new JdbcTransactionFactory(),source)));
        var command=new CommandDefinition();command.setCode("policy:publish");command.setModelCode("policy");
        var request=new CommandExecuteRequest();request.setTargetRecordId("policy-target");
        context=CommandPipelineContext.builder().commandCode("policy:publish").command(command).request(request).tenantId(1L).userId(2L).build();
        context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("policy.publish"));context.setTargetRecordReadable(true);
        context.setPermitPlan(new CommandPermitPlan(CommandPermitPlan.Decision.PERMIT,null,null,"policy-target",CommandPermitPlan.ScopeGrade.TARGET,1L));
        var document=new AnnouncementDocumentCanonicalizer.Document("Title","<p>Raw</p>",List.of());
        proof=new PublishedContent(expected,"announcement.v1",expected.expectedSnapshotHash(),document,AnnouncementDocumentCanonicalizer.encode(document),
                new ApprovedBinding("submission","result","definition",1,"b".repeat(64)),"c".repeat(64),"member",Instant.EPOCH,null);
        when(content.lockActor()).thenReturn("member");when(content.requirePublished(expected,"member")).thenReturn(proof);
    }
    @AfterEach void clear(){TransactionSynchronizationManager.unbindResourceIfPossible(source);TransactionSynchronizationManager.clear();MetaContext.clear();}
    <T>T command(Supplier<T> task){return MetaContext.runWithCommandPermitPlan("TARGET",1L,"policy","policy-target",()->MetaContext.runWithAuthorizedCommandCode("policy:publish",task));}
    @Test void capabilityIsIssuedOnlyInTheAuthorizedOriginalPhysicalTransaction() {
        assertNull(factory.forCommand(context));
        command(()->{try(var scope=factory.forCommand(context)) {assertNotNull(scope);assertEquals(proof,scope.accessor().requirePublishedContentForCommand(expected));}return null;});
        assertNull(MetaContext.getAuthorizedCommandCode());
        // Handler close and command-frame exit are normal before commit, not grounds to discard the TX proof.
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.beforeCommit(false));
        verify(content,times(2)).requirePublished(expected,"member");
    }
    @Test void escapedCapabilityAndNestedFrameCannotMintProofs() {
        command(()->{var scope=factory.forCommand(context);var escaped=scope.accessor();scope.close();
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->escaped.requirePublishedContentForCommand(expected));return null;});
        assertTrue(holder.isRollbackOnly());verifyNoInteractions(content);
    }
    @Test void aNestedCommandBeforeIssuanceHasNoCapability() {
        command(()->MetaContext.runWithAuthorizedCommandCode("policy:publish",()->{assertNull(factory.forCommand(context));return null;}));
        verifyNoInteractions(content);
    }
    @Test void actorAndPhysicalHolderChangesAreDenied() {
        command(()->{try(var scope=factory.forCommand(context)) {
            MetaContext.setMemberId(4L);
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->scope.accessor().requirePublishedContentForCommand(expected));
            MetaContext.setMemberId(3L);
            TransactionSynchronizationManager.unbindResource(source);
            var replacement=new ConnectionHolder(connection);replacement.setSynchronizedWithTransaction(true);TransactionSynchronizationManager.bindResource(source,replacement);
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->scope.accessor().requirePublishedContentForCommand(expected));
        }return null;});verifyNoInteractions(content);
    }
    @Test void commitRevalidationRejectsChangedOfficialFactsAfterHandlerExit() {
        command(()->{try(var scope=factory.forCommand(context)){scope.accessor().requirePublishedContentForCommand(expected);}return null;});
        when(content.requirePublished(expected,"member")).thenThrow(new IllegalStateException("withdrawn"));
        assertThrows(IllegalStateException.class,()->TransactionSynchronizationManager.getSynchronizations().forEach(s->s.beforeCommit(false)));
    }
    @Test void dryRunReadOnlyAndDeniedTargetsNeverReceiveCapabilities() {
        command(()->{
            context.getRequest().setDryRun(true);assertNull(factory.forCommand(context));context.getRequest().setDryRun(false);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);assertNull(factory.forCommand(context));
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            context.setTargetRecordReadable(false);assertNull(factory.forCommand(context));return null;
        });verifyNoInteractions(content);
    }
    @Test void asynchronousUseDoesNotInheritTheOriginalCapability() throws Exception {
        command(()->{
            try(var scope=factory.forCommand(context)) {
                var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    var failure=worker.submit(()->assertThrows(org.springframework.security.access.AccessDeniedException.class,
                            ()->scope.accessor().requirePublishedContentForCommand(expected)));
                    assertNotNull(failure.get(5,java.util.concurrent.TimeUnit.SECONDS));
                } catch(Exception failure){throw new IllegalStateException(failure);}
                finally{worker.shutdownNow();}
            }return null;
        });assertFalse(holder.isRollbackOnly());verifyNoInteractions(content);
    }
    @Test void oneCommandCannotGrowItsAnnouncementLockSet() {
        command(()->{try(var scope=factory.forCommand(context)) {
            scope.accessor().requirePublishedContentForCommand(expected);
            var other=new ExactVersion(expected.announcementPid(),"01ARZ3NDEKTSV4RRFFQ69G5FAX",expected.expectedSnapshotHash(),expected.projectionSchemaId());
            assertThrows(IllegalArgumentException.class,()->scope.accessor().requirePublishedContentForCommand(other));
        }return null;});assertTrue(holder.isRollbackOnly());
    }
}
