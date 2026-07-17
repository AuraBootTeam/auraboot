package com.auraboot.framework.view.mapper;

import com.auraboot.framework.view.entity.SavedViewChipPin;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@link SavedViewChipPin}. All queries are expressed as
 * MyBatis-Plus wrappers in the service, so no custom statements live here.
 */
@Mapper
public interface SavedViewChipPinMapper extends BaseMapper<SavedViewChipPin> {
}
