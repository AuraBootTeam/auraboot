package com.auraboot.framework.user.service;

import com.auraboot.framework.auth.service.VerificationCodeService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.dto.EmailLinkStatusResponse;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.util.EmailAddressNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserEmailLinkService {

    private static final String CODE_TYPE = "email_bind";

    private final UserMapper userMapper;
    private final VerificationCodeService verificationCodeService;

    public EmailLinkStatusResponse status(Long userId) {
        User user = requireUser(userId);
        return response(user);
    }

    public void sendCode(Long userId, String rawEmail, String ipAddress) {
        String email = requireEmail(rawEmail);
        assertAvailable(userId, email);
        verificationCodeService.sendCode(email, CODE_TYPE, ipAddress);
    }

    @Transactional
    public EmailLinkStatusResponse confirm(Long userId, String rawEmail, String code) {
        String email = requireEmail(rawEmail);
        assertAvailable(userId, email);
        if (!verificationCodeService.verifyCode(email, code, CODE_TYPE)) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Invalid or expired verification code");
        }
        try {
            if (userMapper.bindVerifiedEmail(userId, email) != 1) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "User not found");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResponseCode.IdentifierAlreadyBeenTaken,
                    "This email is linked to another account");
        }
        return response(requireUser(userId));
    }

    private void assertAvailable(Long userId, String email) {
        User existing = userMapper.findByNormalizedEmail(email);
        if (existing != null && !existing.getId().equals(userId)) {
            throw new BusinessException(ResponseCode.IdentifierAlreadyBeenTaken,
                    "This email is linked to another account");
        }
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || Boolean.TRUE.equals(user.getDeletedFlag())) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "User not found");
        }
        return user;
    }

    private String requireEmail(String rawEmail) {
        String normalized = EmailAddressNormalizer.normalize(rawEmail);
        if (normalized == null) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "Email is required");
        }
        return normalized;
    }

    private EmailLinkStatusResponse response(User user) {
        String email = EmailAddressNormalizer.normalize(user.getEmail());
        boolean linked = email != null;
        return new EmailLinkStatusResponse(
                linked,
                linked && Boolean.TRUE.equals(user.getEmailVerified()),
                linked ? mask(email) : null);
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        String local = at <= 0 ? "" : email.substring(0, at);
        String visible = local.substring(0, Math.min(2, local.length()));
        return visible + "***" + email.substring(at);
    }
}
