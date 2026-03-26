package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.RegisterRequest;
import com.auraboot.framework.auth.service.AuthService;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.strategy.AuthStrategy;
import com.auraboot.framework.auth.strategy.LoginCompletionHelper;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.exception.UserException;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AuthService implementation that delegates to channel-specific {@link AuthStrategy} instances.
 * <p>
 * The existing {@link #authenticate(AuthenticationRequest)} method is preserved for backward
 * compatibility and internally delegates to the EMAIL_PASSWORD strategy.
 * The new {@link #authenticateByChannel(AuthStrategyRequest)} method routes to the appropriate
 * strategy based on the channelCode.
 *
 * @since 7.0.0
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final Map<String, AuthStrategy> strategyMap;
    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final SessionManagementService sessionManagementService;

    public AuthServiceImpl(List<AuthStrategy> strategies,
                           UserService userService,
                           JwtUtil jwtUtil,
                           UserDetailsService userDetailsService,
                           SessionManagementService sessionManagementService) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(AuthStrategy::getChannelCode, Function.identity()));
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.sessionManagementService = sessionManagementService;

        log.info("Registered {} auth strategies: {}", strategyMap.size(), strategyMap.keySet());
    }

    @Override
    public AuthenticationResponse authenticate(AuthenticationRequest request) throws UserException {
        // Delegate to EMAIL_PASSWORD strategy for backward compatibility
        AuthStrategyRequest strategyRequest = new AuthStrategyRequest();
        strategyRequest.setEmail(request.getEmail());
        strategyRequest.setPassword(request.getPassword());
        strategyRequest.setChannelCode("email_password");
        return authenticateByChannel(strategyRequest);
    }

    @Override
    public AuthenticationResponse authenticateByChannel(AuthStrategyRequest request) {
        String channelCode = request.getChannelCode();
        if (channelCode == null || channelCode.isBlank()) {
            throw new BusinessException("channelCode is required");
        }

        AuthStrategy strategy = strategyMap.get(channelCode);
        if (strategy == null) {
            throw new BusinessException("Unsupported login channel: " + channelCode);
        }

        log.info("Authenticating via channel: {}", channelCode);
        return strategy.authenticate(request);
    }

    @Override
    public AuthenticationResponse register(RegisterRequest request) throws UserException {
        User user = userService.signUp(request.getEmail(), request.getPassword(), request.getDisplayName());
        final UserDetails userDetails = userDetailsService.loadUserByUsername(request.getEmail());
        final String jwt = jwtUtil.generateTokenWithTenantId(userDetails, user.getPid().toString(), null);

        // Register server-side session so the JWT passes session validation
        try {
            sessionManagementService.createSession(user.getId(), jwt, null, null);
        } catch (Exception e) {
            log.warn("Failed to create session for registered user: {}", e.getMessage());
        }

        return new AuthenticationResponse(jwt, user.getId(), user.getPid(), user.getNickName());
    }
}
