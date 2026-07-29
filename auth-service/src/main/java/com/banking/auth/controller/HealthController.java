package com.banking.auth.controller;

import com.banking.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/api/auth/ping")
    public String ping() {
        long count = userRepository.count();
        return "Auth service çalışıyor. Kayıtlı kullanıcı sayısı: " + count;
    }
}