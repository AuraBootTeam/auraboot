package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.SkillRunRepository;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Hermetic Spring-proxy contract; real PostgreSQL/JVM rollback is qualified separately. */
class ModelCreateSkillTransactionTest {
    private final MetaModelService models = mock(MetaModelService.class);
    private final MetaFieldService fields = mock(MetaFieldService.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final ObjectMapper mapper = new ObjectMapper();
    private ModelCreateSkill skill;
    private MetaModelDTO created;
    private SkillRequest request;

    @BeforeEach
    void setUp() throws Exception {
        MetaContext.setContext(41L, 42L, null, "transaction-test");
        ModelCreateSkill target = new ModelCreateSkill(models, fields,
                mock(MetaModelFieldBindingMapper.class), mock(DynamicDataMapper.class), mapper,
                mock(SkillRunRepository.class));
        target.init();
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        skill = (ModelCreateSkill) proxy.getProxy();
        created = MetaModelDTO.builder().pid("01M60000000000000000000001")
                .code("transaction_customer").displayName("Customer").build();
        when(models.create(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return created;
        });
        when(fields.create(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        });
        request = new SkillRequest();
        request.setSkillName("model:create");
        request.setParams(mapper.valueToTree(Map.of("code", created.getCode(), "displayName", "Customer")));
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void commitsOnlyAfterModelFieldsAndPublicationSucceed() {
        when(models.publish(anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(transactions.commits).isZero();
            return created;
        });
        assertThat(skill.execute(request).getStatus()).isEqualTo(SkillResult.Status.SUCCESS);
        verify(models).create(any());
        verify(fields).create(any());
        assertThat(transactions.begins).isEqualTo(1);
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isZero();
    }

    @Test
    void publicationFailureRollsBackTheWholeSkillInsteadOfCommittingADraft() {
        when(models.publish(anyString(), anyString())).thenThrow(new IllegalStateException("publish failed"));
        assertThatThrownBy(() -> skill.execute(request)).isInstanceOf(SkillSpiException.class);
        verify(models).create(any());
        verify(fields).create(any());
        assertThat(transactions.begins).isEqualTo(1);
        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isEqualTo(1);
    }

    private static final class RecordingTransactions extends AbstractPlatformTransactionManager {
        int begins;
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { begins++; }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
