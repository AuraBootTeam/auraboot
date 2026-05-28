package com.auraboot.framework.semantic.service;

import com.auraboot.framework.semantic.dto.SemanticLineageResponse;
import com.auraboot.framework.semantic.entity.AbSemanticLineageEdge;
import com.auraboot.framework.semantic.mapper.AbSemanticLineageEdgeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Backs {@code GET /api/semantic/lineage/{pid}}. Returns BOTH directions
 * (incoming + outgoing) for impact analysis.
 */
@Service
@RequiredArgsConstructor
public class SemanticLineageService {

    private final AbSemanticLineageEdgeMapper lineageMapper;

    public SemanticLineageResponse describe(Long tenantId, String nodePid) {
        SemanticLineageResponse out = new SemanticLineageResponse();
        out.setNodePid(nodePid);

        List<AbSemanticLineageEdge> outgoing = lineageMapper.findOutgoing(tenantId, nodePid);
        List<AbSemanticLineageEdge> incoming = lineageMapper.findIncoming(tenantId, nodePid);

        for (AbSemanticLineageEdge e : outgoing) {
            out.getOutgoing().add(toDto(e));
        }
        for (AbSemanticLineageEdge e : incoming) {
            out.getIncoming().add(toDto(e));
        }
        // If we found at least one outgoing edge, infer the node type from src.
        if (!outgoing.isEmpty()) {
            out.setNodeType(outgoing.get(0).getSrcNodeType());
        } else if (!incoming.isEmpty()) {
            out.setNodeType(incoming.get(0).getDstNodeType());
        }
        return out;
    }

    private SemanticLineageResponse.Edge toDto(AbSemanticLineageEdge e) {
        SemanticLineageResponse.Edge dto = new SemanticLineageResponse.Edge();
        dto.setSrcPid(e.getSrcNodePid());
        dto.setSrcType(e.getSrcNodeType());
        dto.setDstPid(e.getDstNodePid());
        dto.setDstType(e.getDstNodeType());
        dto.setRefType(e.getRefType());
        return dto;
    }
}
