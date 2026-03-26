package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.RegisterRequest;
import com.auraboot.framework.user.exception.UserException;

public interface AuthService {
    AuthenticationResponse authenticate(AuthenticationRequest request) throws UserException;

    /**
     * Authenticate via a specific login channel (SMS, email code, etc.).
     *
     * @param request multi-channel authentication request
     * @return authentication response containing JWT and user info
     * @since 7.0.0
     */
    AuthenticationResponse authenticateByChannel(AuthStrategyRequest request);

    AuthenticationResponse register(RegisterRequest request) throws UserException;

}