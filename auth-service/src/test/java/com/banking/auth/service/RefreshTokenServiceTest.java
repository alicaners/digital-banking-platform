package com.banking.auth.service;

import com.banking.auth.entity.RefreshToken;
import com.banking.auth.exception.InvalidRefreshTokenException;
import com.banking.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final long REFRESH_EXPIRATION_MS = 60_000L;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        refreshTokenService = new RefreshTokenService(refreshTokenRepository, REFRESH_EXPIRATION_MS);
    }

    private RefreshToken storedToken(String rawToken, Long userId, boolean revoked, LocalDateTime expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setTokenHash(RefreshTokenService.hash(rawToken));
        token.setUserId(userId);
        token.setRevoked(revoked);
        token.setExpiresAt(expiresAt);
        return token;
    }

    @Test
    void create_storesOnlyTheHashNotTheRawToken() {

        String rawToken = refreshTokenService.create(7L);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken saved = captor.getValue();

        assertNotNull(rawToken);
        assertNotEquals(rawToken, saved.getTokenHash());
        assertEquals(RefreshTokenService.hash(rawToken), saved.getTokenHash());
        assertEquals(64, saved.getTokenHash().length());
        assertEquals(7L, saved.getUserId());
        assertFalse(saved.isRevoked());
        assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void create_generatesDifferentTokenEachTime() {

        String first = refreshTokenService.create(7L);
        String second = refreshTokenService.create(7L);

        assertNotEquals(first, second);
    }

    @Test
    void rotate_validToken_revokesOldAndIssuesNewOne() {

        RefreshToken stored = storedToken("eski", 5L, false, LocalDateTime.now().plusMinutes(10));
        when(refreshTokenRepository.findByTokenHash(RefreshTokenService.hash("eski")))
                .thenReturn(Optional.of(stored));

        RefreshTokenService.RotatedToken result = refreshTokenService.rotate("eski");

        assertTrue(stored.isRevoked());
        assertEquals(5L, result.userId());
        assertNotNull(result.refreshToken());
        assertNotEquals("eski", result.refreshToken());
        // biri eski kaydın iptali, biri yeni kaydın oluşturulması
        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong());
    }

    @Test
    void rotate_unknownToken_throwsException() {

        when(refreshTokenRepository.findByTokenHash(RefreshTokenService.hash("bilinmeyen")))
                .thenReturn(Optional.empty());

        assertThrows(InvalidRefreshTokenException.class, () -> refreshTokenService.rotate("bilinmeyen"));

        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong());
    }

    @Test
    void rotate_alreadyRevokedToken_revokesAllUserTokensAndThrows() {

        RefreshToken stored = storedToken("kullanilmis", 5L, true, LocalDateTime.now().plusMinutes(10));
        when(refreshTokenRepository.findByTokenHash(RefreshTokenService.hash("kullanilmis")))
                .thenReturn(Optional.of(stored));

        assertThrows(InvalidRefreshTokenException.class, () -> refreshTokenService.rotate("kullanilmis"));

        verify(refreshTokenRepository, times(1)).revokeAllByUserId(5L);
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void rotate_expiredToken_revokesItAndThrows() {

        RefreshToken stored = storedToken("suresi-dolmus", 5L, false, LocalDateTime.now().minusMinutes(1));
        when(refreshTokenRepository.findByTokenHash(RefreshTokenService.hash("suresi-dolmus")))
                .thenReturn(Optional.of(stored));

        assertThrows(InvalidRefreshTokenException.class, () -> refreshTokenService.rotate("suresi-dolmus"));

        assertTrue(stored.isRevoked());
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong());
    }

    @Test
    void revoke_activeToken_marksItRevoked() {

        RefreshToken stored = storedToken("aktif", 5L, false, LocalDateTime.now().plusMinutes(10));
        when(refreshTokenRepository.findByTokenHash(RefreshTokenService.hash("aktif")))
                .thenReturn(Optional.of(stored));

        refreshTokenService.revoke("aktif");

        assertTrue(stored.isRevoked());
        verify(refreshTokenRepository, times(1)).save(stored);
    }

    @Test
    void revoke_unknownToken_doesNothingAndDoesNotThrow() {

        when(refreshTokenRepository.findByTokenHash(RefreshTokenService.hash("bilinmeyen")))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> refreshTokenService.revoke("bilinmeyen"));

        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void revoke_alreadyRevokedToken_doesNotSaveAgain() {

        RefreshToken stored = storedToken("iptal", 5L, true, LocalDateTime.now().plusMinutes(10));
        when(refreshTokenRepository.findByTokenHash(RefreshTokenService.hash("iptal")))
                .thenReturn(Optional.of(stored));

        refreshTokenService.revoke("iptal");

        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }
}