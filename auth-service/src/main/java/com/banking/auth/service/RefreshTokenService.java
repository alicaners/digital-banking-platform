package com.banking.auth.service;

import com.banking.auth.entity.RefreshToken;
import com.banking.auth.exception.InvalidRefreshTokenException;
import com.banking.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Yenileme sonucu: hangi kullanıcı için, hangi yeni refresh token verildi. */
    public record RotatedToken(Long userId, String refreshToken) {}

    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshExpirationMs;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               @Value("${jwt.refresh-expiration}") long refreshExpirationMs) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    /** Yeni bir refresh token üretir, hash'ini kaydeder, düz halini döner. */
    @Transactional
    public String create(Long userId) {
        String rawToken = generateRawToken();

        RefreshToken entity = new RefreshToken();
        entity.setTokenHash(hash(rawToken));
        entity.setUserId(userId);
        entity.setExpiresAt(LocalDateTime.now().plusNanos(refreshExpirationMs * 1_000_000L));
        refreshTokenRepository.save(entity);

        return rawToken;
    }

    /**
     * Rotation: eski token iptal edilir, yenisi verilir. Daha önce iptal edilmiş bir token
     * tekrar gelirse (reuse) kullanıcının tüm token'ları iptal edilir.
     * noRollbackFor: reuse durumunda hata fırlatsak bile "hepsini iptal et" işlemi geri alınmamalı.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public RotatedToken rotate(String rawToken) {
        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Geçersiz refresh token"));

        if (existing.isRevoked()) {
            int revoked = refreshTokenRepository.revokeAllByUserId(existing.getUserId());
            log.warn("İptal edilmiş refresh token tekrar kullanıldı (reuse). userId={}, iptal edilen aktif token sayısı={}",
                    existing.getUserId(), revoked);
            throw new InvalidRefreshTokenException("Geçersiz refresh token");
        }

        if (existing.getExpiresAt().isBefore(LocalDateTime.now())) {
            existing.setRevoked(true);
            refreshTokenRepository.save(existing);
            throw new InvalidRefreshTokenException("Refresh token süresi dolmuş");
        }

        existing.setRevoked(true);
        refreshTokenRepository.save(existing);

        String newRawToken = create(existing.getUserId());
        return new RotatedToken(existing.getUserId(), newRawToken);
    }

    /**
     * Logout: token iptal edilir. Bilinmeyen/zaten iptal edilmiş token için hata dönmez,
     * böylece logout tekrarlanabilir ve token'ın var olup olmadığı dışarıya sızmaz.
     */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.setRevoked(true);
                refreshTokenRepository.save(token);
            }
        });
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 bulunamadı", e);
        }
    }
}