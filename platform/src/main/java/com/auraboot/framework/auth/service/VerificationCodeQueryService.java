package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.entity.VerificationCode;
import com.auraboot.framework.auth.mapper.VerificationCodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Read access to verification codes for controllers (ArchUnit rule 2:
 * controllers must not touch the mapper layer). Holds only the dev-diagnostics
 * lookup the dev verify-code controller needs.
 */
@Service
@RequiredArgsConstructor
public class VerificationCodeQueryService {

    private final VerificationCodeMapper verificationCodeMapper;

    /** Latest unused verification code for the target (email or phone), or null. */
    public VerificationCode findLatestByTarget(String target) {
        return verificationCodeMapper.findLatestByTarget(target);
    }
}
