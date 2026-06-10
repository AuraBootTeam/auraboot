package com.auraboot.framework.decision.service;

import com.auraboot.framework.decision.dto.DecisionTableDmnXmlDTO;
import com.auraboot.framework.decision.dto.DecisionTableDmnXmlRequest;

public interface DecisionTableDmnXmlService {

    DecisionTableDmnXmlDTO exportDmn(DecisionTableDmnXmlRequest request);

    DecisionTableDmnXmlDTO importDmn(DecisionTableDmnXmlRequest request);

    DecisionTableDmnXmlDTO roundTrip(DecisionTableDmnXmlRequest request);
}
