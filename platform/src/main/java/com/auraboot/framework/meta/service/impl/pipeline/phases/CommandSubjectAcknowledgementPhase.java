package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.pf4j.AnnouncementSubjectAcknowledgementAccessorFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Current original-subject authority is mandatory before an idempotency result can bypass handlers. */
@Component
@Order(515)
@RequiredArgsConstructor
public class CommandSubjectAcknowledgementPhase implements CommandPhase {
    private final AnnouncementSubjectAcknowledgementAccessorFactory factory;
    @Override public String name(){return "subject_acknowledgement";}
    @Override public void execute(CommandPipelineContext context){factory.prepare(context);}
}
