package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dto.MemberQueryRequest;
import com.auraboot.framework.tenant.dto.MemberResponse;
import com.auraboot.framework.tenant.service.TenantMemberApplicationService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

@Slf4j
@Service
@Transactional
public class TenantMemberApplicationServiceImpl implements TenantMemberApplicationService {
    
    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private UserService userService;

    @Autowired
    private TeamMemberService teamMemberService;
    
    @Override
    public PaginationResult<MemberResponse> searchMembers(MemberQueryRequest request, Long userId) {
        try {
            // 获取当前用户的租户ID
            Long tenantId = MetaContext.getCurrentTenantId();
            if (tenantId == null) {
                tenantId = tenantMemberService.getTenantIdByUserId(userId);
            }
            
            if (tenantId == null) {
                throw new BusinessException(ResponseCode.BadParam, "用户未加入任何租户");
            }
            
            // 分页查询成员
            Page<TenantMember> page = tenantMemberService.findMembers(
                request.getPageNum(), 
                request.getPageSize(), 
                tenantId,
                request.getKeyword(), 
                request.getMemberType(), 
                request.getStatus()
            );
            
            // 转换为响应对象
            List<MemberResponse> memberResponses = page.getRecords().stream()
                .map(this::convertToMemberResponse)
                .collect(Collectors.toList());


            PaginationResult<MemberResponse> memberResponsePaginationResult = PaginationResult.of(memberResponses, page.getTotal(), request.getPageNum(), request.getPageSize());

            return memberResponsePaginationResult;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("搜索成员失败", e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "搜索成员失败: " + e.getMessage());
        }
    }
    
    @Override
    public MemberResponse getMemberById(String memberPid, Long userId) {
        try {
            TenantMember member = tenantMemberService.findByPid(memberPid);
            if (member == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "成员不存在");
            }

            // 验证权限：只能查看同租户的成员
            Long currentTenantId = MetaContext.getCurrentTenantId();
            if (currentTenantId == null) {
                currentTenantId = tenantMemberService.getTenantIdByUserId(userId);
            }

            if (!member.getTenantId().equals(currentTenantId)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "无权限查看该成员信息");
            }

            return convertToMemberResponse(member);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取成员信息失败，memberPid: {}", memberPid, e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "获取成员信息失败: " + e.getMessage());
        }
    }
    
    @Override
    public boolean approveMember(String memberPid, String action, String reason, Long userId) {
        try {
            TenantMember member = tenantMemberService.findByPid(memberPid);
            if (member == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "成员不存在");
            }

            // 验证权限：只能操作同租户的成员
            Long currentTenantId = MetaContext.getCurrentTenantId();
            if (currentTenantId == null) {
                currentTenantId = tenantMemberService.getTenantIdByUserId(userId);
            }

            if (!member.getTenantId().equals(currentTenantId)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "无权限操作该成员");
            }

            // 根据操作类型更新状态
            if ("approve".equals(action)) {
                member.setStatus(StatusConstants.ACTIVE);
                log.info("用户 {} 审批通过成员 {}", userId, memberPid);
            } else if ("reject".equals(action)) {
                member.setStatus(StatusConstants.REJECTED);
                
                // 将拒绝原因存储到extensions JSON字段中
                if (reason != null && !reason.trim().isEmpty()) {
                    try {
                        ObjectMapper objectMapper = new ObjectMapper();
                        ObjectNode extensionsNode;
                        
                        // 如果已有extensions数据，则解析现有数据
                        if (member.getExtensions() != null && !member.getExtensions().trim().isEmpty()) {
                            extensionsNode = (ObjectNode) objectMapper.readTree(member.getExtensions());
                        } else {
                            extensionsNode = objectMapper.createObjectNode();
                        }
                        
                        // 添加拒绝原因和拒绝时间
                        extensionsNode.put("rejectReason", reason.trim());
                        extensionsNode.put("rejectTime", Instant.now().toEpochMilli());
                        extensionsNode.put("rejectedBy", userId);
                        
                        member.setExtensions(objectMapper.writeValueAsString(extensionsNode));
                    } catch (Exception e) {
                        log.warn("存储拒绝原因到extensions字段失败: {}", e.getMessage());
                        // 即使JSON处理失败，也不影响主要的拒绝流程
                    }
                }
                
                log.info("用户 {} 拒绝成员 {}, 原因: {}", userId, memberPid, reason);
            } else {
                throw new BusinessException(ResponseCode.BadParam, "无效的操作类型: " + action);
            }
            
            member.setUpdatedBy(userId);
            member.setUpdatedAt(Instant.now());
            
            tenantMemberService.updateMember(member);
            return true;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("审批成员失败，memberPid: {}, action: {}", memberPid, action, e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "审批成员失败: " + e.getMessage());
        }
    }
    
    @Override
    public boolean updateMemberStatus(String memberPid, String status, String reason, Long userId) {
        try {
            TenantMember member = tenantMemberService.findByPid(memberPid);
            if (member == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "成员不存在");
            }

            // 验证权限：只能操作同租户的成员
            Long currentTenantId = MetaContext.getCurrentTenantId();
            if (currentTenantId == null) {
                currentTenantId = tenantMemberService.getTenantIdByUserId(userId);
            }

            if (!member.getTenantId().equals(currentTenantId)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "无权限操作该成员");
            }

            // 根据状态调用相应的服务方法
            boolean result = false;
            switch (status) {
                case StatusConstants.ACTIVE:
                    result = tenantMemberService.activateMember(member.getId());
                    break;
                case StatusConstants.INACTIVE:
                    result = tenantMemberService.deactivateMember(member.getId());
                    break;
                case StatusConstants.SUSPENDED:
                    result = tenantMemberService.suspendMember(member.getId(), reason);
                    break;
                default:
                    throw new BusinessException(ResponseCode.BadParam, "无效的状态: " + status);
            }
            
            log.info("用户 {} 更新成员 {} 状态为 {}, 原因: {}", userId, memberPid, status, reason);
            return result;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("更新成员状态失败，memberPid: {}, status: {}", memberPid, status, e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "更新成员状态失败: " + e.getMessage());
        }
    }
    
    @Override
    public boolean removeMember(String memberPid, Long userId) {
        try {
            TenantMember member = tenantMemberService.findByPid(memberPid);
            if (member == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "成员不存在");
            }

            // 验证权限：只能操作同租户的成员
            Long currentTenantId = MetaContext.getCurrentTenantId();
            if (currentTenantId == null) {
                currentTenantId = tenantMemberService.getTenantIdByUserId(userId);
            }

            if (!member.getTenantId().equals(currentTenantId)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "无权限操作该成员");
            }

            // 不能删除自己
            if (member.getUserId().equals(userId)) {
                throw new BusinessException(ResponseCode.BadParam, "不能删除自己");
            }
            
            boolean result = tenantMemberService.removeMember(member.getId());
            log.info("用户 {} 移除成员 {}", userId, memberPid);
            return result;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("移除成员失败，memberPid: {}", memberPid, e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "移除成员失败: " + e.getMessage());
        }
    }
    
    @Override
    public boolean batchRemoveMembers(List<String> memberPids, Long userId) {
        try {
            if (memberPids == null || memberPids.isEmpty()) {
                return true;
            }
            
            Long currentTenantId = MetaContext.getCurrentTenantId();
            if (currentTenantId == null) {
                currentTenantId = tenantMemberService.getTenantIdByUserId(userId);
            }
            
            for (String memberPid : memberPids) {
                TenantMember member = tenantMemberService.findByPid(memberPid);
                if (member == null) {
                    log.warn("成员不存在: {}", memberPid);
                    continue;
                }
                
                // 验证权限
                if (!member.getTenantId().equals(currentTenantId)) {
                    log.warn("无权限操作成员: {}", memberPid);
                    continue;
                }
                
                // 不能删除自己
                if (member.getUserId().equals(userId)) {
                    log.warn("不能删除自己: {}", memberPid);
                    continue;
                }
                
                tenantMemberService.removeMember(member.getId());
            }
            
            log.info("用户 {} 批量移除成员: {}", userId, memberPids);
            return true;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("批量移除成员失败", e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "批量移除成员失败: " + e.getMessage());
        }
    }
    
    @Override
    public List<Map<String, Object>> getMemberTeams(String memberPid) {
        TenantMember member = tenantMemberService.findByPid(memberPid);
        if (member == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Member not found");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        return teamMemberService.getTeamMembershipsByUserId(member.getUserId(), tenantId);
    }

    /**
     * 转换为成员响应对象
     */
    private MemberResponse convertToMemberResponse(TenantMember member) {
        MemberResponse response = new MemberResponse();
        BeanUtils.copyProperties(member, response);
        
        // 获取用户信息
        if (member.getUserId() != null) {
            try {
                User user = userService.findByUserId(member.getUserId());
                if (user != null) {
                    MemberResponse.UserInfo userInfo = new MemberResponse.UserInfo();
                    userInfo.setId(user.getId());
                    userInfo.setPid(user.getPid());
                    userInfo.setUsername(user.getUserName());
                    userInfo.setEmail(user.getEmail());
                    userInfo.setPhone(user.getMobile());
//                    userInfo.setRealName(user.getRealName());
//                    userInfo.setAvatar(user.getAvatar());
                    response.setUser(userInfo);
                }
            } catch (Exception e) {
                log.warn("获取用户信息失败，userId: {}", member.getUserId(), e);
            }
        }
        
        return response;
    }
}