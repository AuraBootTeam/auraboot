package com.auraboot.framework.rag.d7;

import com.auraboot.framework.rag.dto.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5 (DDR-A option A1): RRF fusion of compiled pages and raw chunks.
 */
class D7RagFusionTest {

    private static D7CompiledKnowledgeMatch compiled(String id) {
        return new D7CompiledKnowledgeMatch(
                D7CompiledKnowledgePage.builder().id(id).title(id).build(), 1.0, false);
    }

    private static RetrievalResult raw(String pid) {
        return RetrievalResult.builder().chunkPid(pid).docName(pid).content("c-" + pid).chunkIndex(0).build();
    }

    @Test
    void topCompiledBeatsTopRaw_withDefaultWeight() {
        List<D7RagFusion.FusedItem> fused = D7RagFusion.fuse(
                List.of(compiled("p1")), List.of(raw("r1")), 60, 1.5);
        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).isCompiled()).isTrue();
        assertThat(fused.get(1).raw().getChunkPid()).isEqualTo("r1");
    }

    @Test
    void highRankedRawBeatsLowRankedCompiled() {
        // compiled rank3: 1.5/(60+3)=0.0238 < raw rank1: 1/(60+1)=0.0164? No — compute:
        // 1.5/63 = 0.0238, 1/61 = 0.0164 → compiled rank3 still wins with w=1.5.
        // With weight 1.0: compiled rank2 = 1/62 = 0.01613 < raw rank1 = 1/61 = 0.01639 → raw wins.
        List<D7RagFusion.FusedItem> fused = D7RagFusion.fuse(
                List.of(compiled("p1"), compiled("p2")), List.of(raw("r1")), 60, 1.0);
        assertThat(fused.get(0).isCompiled()).isTrue();           // p1: 1/61
        assertThat(fused.get(1).isCompiled()).isFalse();          // r1: 1/61 ties p1; sorted stable — r1 ≥ p2
        assertThat(fused.get(2).compiled().getPage().getId()).isEqualTo("p2"); // p2: 1/62 last
    }

    @Test
    void emptyInputs_yieldEmptyOrSingleSide() {
        assertThat(D7RagFusion.fuse(List.of(), List.of(), 60, 1.5)).isEmpty();
        List<D7RagFusion.FusedItem> rawOnly = D7RagFusion.fuse(null, List.of(raw("r1")), 60, 1.5);
        assertThat(rawOnly).hasSize(1);
        assertThat(rawOnly.get(0).isCompiled()).isFalse();
    }
}
