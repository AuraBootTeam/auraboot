package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.exception.UnSupportedException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnifiedUserDetailsServiceTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private UnifiedUserDetailsService service;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "userService", userService);
        user = new User();
        user.setId(42L);
        user.setPid("user-pid-42");
        user.setPassword("encoded-pw");
    }

    @Test
    void loadUserByUsername_emailIdentifier_resolvesViaEmail() {
        when(userService.findByEmail("foo@bar.com")).thenReturn(user);

        UserDetails details = service.loadUserByUsername("foo@bar.com");

        assertThat(details).isInstanceOf(CustomUserDetails.class);
        CustomUserDetails cud = (CustomUserDetails) details;
        assertThat(cud.getUsername()).isEqualTo("foo@bar.com");
        assertThat(cud.getUserId()).isEqualTo(42L);
        assertThat(cud.getUserPid()).isEqualTo("user-pid-42");
    }

    @Test
    void loadUserByUsername_pidIdentifier_resolvesViaPid() {
        when(userService.findByPid("user-pid-42")).thenReturn(user);

        UserDetails details = service.loadUserByUsername("user-pid-42");

        assertThat(details.getUsername()).isEqualTo("user-pid-42");
    }

    @Test
    void loadUserByUsername_phoneIdentifier_throwsUnsupported() {
        assertThatThrownBy(() -> service.loadUserByUsername("13800138000"))
                .isInstanceOf(UnSupportedException.class);
    }

    @Test
    void loadUserByUsername_userNotFound_throws() {
        when(userService.findByEmail("missing@x.com")).thenReturn(null);
        assertThatThrownBy(() -> service.loadUserByUsername("missing@x.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void loadUserById_existingUser_returnsDetails() {
        when(userService.findByUserId(42L)).thenReturn(user);
        UserDetails details = service.loadUserById(42L);
        assertThat(details.getUsername()).isEqualTo("user-pid-42");
        assertThat(((CustomUserDetails) details).getUserId()).isEqualTo(42L);
    }

    @Test
    void loadUserById_missingUser_throws() {
        when(userService.findByUserId(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.loadUserById(99L))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
