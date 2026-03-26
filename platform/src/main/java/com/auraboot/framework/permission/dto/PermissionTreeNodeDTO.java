package com.auraboot.framework.permission.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * Permission Tree Node DTO
 *
 * <p>Hierarchical representation of permissions for the assignment UI tree.
 *
 * @author AuraBoot Platform
 * @since V4
 */
@Data
public class PermissionTreeNodeDTO {

    private Long id;
    private String pid;
    private String code;
    private String name;
    private String type;
    private String module;
    private String status;
    private List<PermissionTreeNodeDTO> children = new ArrayList<>();

    public static PermissionTreeNodeDTO fromDTO(PermissionDTO dto) {
        PermissionTreeNodeDTO node = new PermissionTreeNodeDTO();
        node.setId(dto.getId());
        node.setPid(dto.getPid());
        node.setCode(dto.getCode());
        node.setName(dto.getName());
        node.setType(dto.getResourceType());
        node.setModule(dto.getResourceCode());
        node.setStatus(dto.getStatus());
        return node;
    }
}
