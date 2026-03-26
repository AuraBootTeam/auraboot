/*
 * Copyright 2004, 2005, 2006 Acegi Technology Pty Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.auraboot.framework.auth.dto;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.SpringSecurityCoreVersion;
import org.springframework.util.Assert;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * An {@link org.springframework.security.core.Authentication} implementation that is
 * designed for simple presentation of a username and password.
 * <p>
 * The <code>principal</code> and <code>credentials</code> should be set with an
 * <code>Object</code> that provides the respective property via its
 * <code>Object.toString()</code> method. The simplest such <code>Object</code> to use is
 * <code>String</code>.
 *
 * @author Ben Alex
 * @author Norbert Nowak
 */
public class EmailPasswordAuthenticationToken extends AbstractAuthenticationToken {

	private static final long serialVersionUID = SpringSecurityCoreVersion.SERIAL_VERSION_UID;

	private final Object principal;

	private Object credentials;

	public String getUserPid() {
		return userPid;
	}

	public void setUserPid(String userPid) {
		this.userPid = userPid;
	}

	// 添加用户ID字段

	private String userPid;
	
	// 添加额外信息字段，可以存储更多用户相关信息
	private Map<String, Object> additionalInfo = new HashMap<>();

	/**
	 * This constructor can be safely used by any code that wishes to create a
	 * <code>UsernamePasswordAuthenticationToken</code>, as the {@link #isAuthenticated()}
	 * will return <code>false</code>.
	 *
	 */
	public EmailPasswordAuthenticationToken(Object principal, Object credentials) {
		super(null);
		this.principal = principal;
		this.credentials = credentials;
		setAuthenticated(false);
	}

	/**
	 * This constructor should only be used by <code>AuthenticationManager</code> or
	 * <code>AuthenticationProvider</code> implementations that are satisfied with
	 * producing a trusted (i.e. {@link #isAuthenticated()} = <code>true</code>)
	 * authentication token.
	 * @param principal
	 * @param credentials
	 * @param authorities
	 */
	public EmailPasswordAuthenticationToken(Object principal, Object credentials,
											Collection<? extends GrantedAuthority> authorities) {
		super(authorities);
		this.principal = principal;
		this.credentials = credentials;
		super.setAuthenticated(true); // must use super, as we override
	}
	
	/**
	 * 带用户ID的构造函数
	 */
	public EmailPasswordAuthenticationToken(Object principal, Object credentials,
											Collection<? extends GrantedAuthority> authorities, String userPid) {
		super(authorities);
		this.principal = principal;
		this.credentials = credentials;
		this.userPid = userPid;
		super.setAuthenticated(true);
	}

	/**
	 * This factory method can be safely used by any code that wishes to create a
	 * unauthenticated <code>UsernamePasswordAuthenticationToken</code>.
	 * @param principal
	 * @param credentials
	 * @return UsernamePasswordAuthenticationToken with false isAuthenticated() result
	 *
	 * @since 5.7
	 */
	public static EmailPasswordAuthenticationToken unauthenticated(Object principal, Object credentials) {
		return new EmailPasswordAuthenticationToken(principal, credentials);
	}

	/**
	 * This factory method can be safely used by any code that wishes to create a
	 * authenticated <code>UsernamePasswordAuthenticationToken</code>.
	 * @param principal
	 * @param credentials
	 * @return UsernamePasswordAuthenticationToken with true isAuthenticated() result
	 *
	 * @since 5.7
	 */
	public static EmailPasswordAuthenticationToken authenticated(Object principal, Object credentials,
																 Collection<? extends GrantedAuthority> authorities) {
		return new EmailPasswordAuthenticationToken(principal, credentials, authorities);
	}
	
	/**
	 * 带用户ID的认证工厂方法
	 */
	public static EmailPasswordAuthenticationToken authenticated(Object principal, Object credentials,
																 Collection<? extends GrantedAuthority> authorities, String userPid) {
		return new EmailPasswordAuthenticationToken(principal, credentials, authorities, userPid);
	}

	@Override
	public Object getCredentials() {
		return this.credentials;
	}

	@Override
	public Object getPrincipal() {
		return this.principal;
	}
	

	
	/**
	 * 添加额外信息
	 */
	public void addAdditionalInfo(String code, Object value) {
		this.additionalInfo.put(code, value);
	}
	
	/**
	 * 获取额外信息
	 */
	public Object getAdditionalInfo(String code) {
		return this.additionalInfo.get(code);
	}
	
	/**
	 * 获取所有额外信息
	 */
	public Map<String, Object> getAllAdditionalInfo() {
		return new HashMap<>(this.additionalInfo);
	}

	@Override
	public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
		Assert.isTrue(!isAuthenticated,
				"Cannot set this token to trusted - use constructor which takes a GrantedAuthority list instead");
		super.setAuthenticated(false);
	}

	@Override
	public void eraseCredentials() {
		super.eraseCredentials();
		this.credentials = null;
	}
}
