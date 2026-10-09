package com.banking.auth.service;

import com.banking.auth.dto.AuthResponse;
import com.banking.auth.dto.LoginRequest;
import com.banking.auth.dto.RefreshTokenRequest;
import com.banking.auth.dto.RegisterRequest;
import com.banking.auth.entity.Role;
import com.banking.auth.entity.User;
import com.banking.auth.exception.InvalidRefreshTokenException;
import com.banking.auth.repository.UserRepository;
import com.banking.auth.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthService authService;

    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;
    private User existingUser;

    @BeforeEach
    void setUp() {
        registerRequest = new RegisterRequest();
        registerRequest.setUsername("testuser");
        registerRequest.setEmail("test@example.com");
        registerRequest.setPassword("sifre123");

        loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("sifre123");

        existingUser = new User();
        existingUser.setId(1L);
        existingUser.setUsername("testuser");
        existingUser.setEmail("test@example.com");
        existingUser.setPassword("hashedPassword");
        existingUser.setRole(Role.CUSTOMER);
    }

    @Test
    void register_successfulRegistration_returnsCorrectResponse() {

        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("sifre123")).thenReturn("hashedPassword");

        AuthResponse response = authService.register(registerRequest);

        assertEquals("testuser", response.getUsername());
        assertEquals("test@example.com", response.getEmail());
        assertEquals("Kayıt başarılı", response.getMessage());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void register_usernameAlreadyExists_throwsException() {

        when(userRepository.existsByUsername("testuser")).thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.register(registerRequest)
        );

        assertEquals("Bu kullanıcı adı zaten alınmış", exception.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_emailAlreadyExists_throwsException() {

        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.register(registerRequest)
        );

        assertEquals("Bu email zaten kayıtlı", exception.getMessage());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void login_successfulLogin_returnsTokenAndResponse() {

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("sifre123", "hashedPassword")).thenReturn(true);
        when(jwtTokenProvider.generateToken(1L, "testuser", "CUSTOMER")).thenReturn("sahte.jwt.token");
        when(refreshTokenService.create(1L)).thenReturn("sahte-refresh-token");

        AuthResponse response = authService.login(loginRequest);

        assertEquals("testuser", response.getUsername());
        assertEquals("test@example.com", response.getEmail());
        assertEquals("Giriş başarılı", response.getMessage());
        assertEquals("sahte.jwt.token", response.getToken());
        assertEquals("sahte-refresh-token", response.getRefreshToken());
    }

    @Test
    void login_userNotFound_throwsException() {

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.login(loginRequest)
        );

        assertEquals("Kullanıcı adı veya şifre hatalı", exception.getMessage());
        verify(jwtTokenProvider, never()).generateToken(anyLong(), anyString(), anyString());
        verify(refreshTokenService, never()).create(anyLong());
    }

    @Test
    void login_wrongPassword_throwsException() {

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("sifre123", "hashedPassword")).thenReturn(false);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.login(loginRequest)
        );

        assertEquals("Kullanıcı adı veya şifre hatalı", exception.getMessage());
        verify(jwtTokenProvider, never()).generateToken(anyLong(), anyString(), anyString());
        verify(refreshTokenService, never()).create(anyLong());
    }

    @Test
    void refresh_validToken_returnsNewAccessAndRefreshToken() {

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("eski-refresh-token");

        when(refreshTokenService.rotate("eski-refresh-token"))
                .thenReturn(new RefreshTokenService.RotatedToken(1L, "yeni-refresh-token"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(jwtTokenProvider.generateToken(1L, "testuser", "CUSTOMER")).thenReturn("yeni.jwt.token");

        AuthResponse response = authService.refresh(request);

        assertEquals("testuser", response.getUsername());
        assertEquals("Token yenilendi", response.getMessage());
        assertEquals("yeni.jwt.token", response.getToken());
        assertEquals("yeni-refresh-token", response.getRefreshToken());
    }

    @Test
    void refresh_invalidToken_throwsAndIssuesNoAccessToken() {

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("gecersiz");

        when(refreshTokenService.rotate("gecersiz"))
                .thenThrow(new InvalidRefreshTokenException("Geçersiz refresh token"));

        assertThrows(InvalidRefreshTokenException.class, () -> authService.refresh(request));

        verify(jwtTokenProvider, never()).generateToken(anyLong(), anyString(), anyString());
    }

    @Test
    void refresh_userNoLongerExists_throwsInvalidRefreshToken() {

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("eski-refresh-token");

        when(refreshTokenService.rotate("eski-refresh-token"))
                .thenReturn(new RefreshTokenService.RotatedToken(99L, "yeni-refresh-token"));
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(InvalidRefreshTokenException.class, () -> authService.refresh(request));

        verify(jwtTokenProvider, never()).generateToken(anyLong(), anyString(), anyString());
    }

    @Test
    void logout_revokesGivenRefreshToken() {

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("cikis-token");

        authService.logout(request);

        verify(refreshTokenService, times(1)).revoke("cikis-token");
    }
}