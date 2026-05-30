package com.auraboot.framework.chatbi.v2.mapper;

import com.auraboot.framework.chatbi.v2.entity.ChatBiDisambiguationLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ChatBiDisambiguationLogMapper extends BaseMapper<ChatBiDisambiguationLog> {

    @Select("SELECT * FROM chatbi_disambiguation_log "
          + "WHERE tenant_id = #{tenantId} AND pid = #{pid} LIMIT 1")
    ChatBiDisambiguationLog findByPid(@Param("tenantId") Long tenantId,
                                       @Param("pid") String pid);

    @Select("SELECT * FROM chatbi_disambiguation_log "
          + "WHERE tenant_id = #{tenantId} AND answer_pid = #{answerPid} "
          + "ORDER BY created_at DESC")
    List<ChatBiDisambiguationLog> listByAnswer(@Param("tenantId") Long tenantId,
                                                @Param("answerPid") String answerPid);

    /** Hot ambiguous terms in the last N hours — feeds prompt-quality dashboard. */
    @Select("SELECT * FROM chatbi_disambiguation_log "
          + "WHERE tenant_id = #{tenantId} AND created_at >= #{since} "
          + "ORDER BY created_at DESC LIMIT #{limit}")
    List<ChatBiDisambiguationLog> listRecent(@Param("tenantId") Long tenantId,
                                              @Param("since") Instant since,
                                              @Param("limit") int limit);

    /** Records the user's resolution. Idempotent on user_choice + resolved_at. */
    @Update("UPDATE chatbi_disambiguation_log "
          + "SET user_choice = #{userChoice}, resolved_at = NOW() "
          + "WHERE tenant_id = #{tenantId} AND pid = #{pid} "
          + "AND user_choice IS NULL")
    int recordChoice(@Param("tenantId") Long tenantId,
                     @Param("pid") String pid,
                     @Param("userChoice") String userChoice);
}
