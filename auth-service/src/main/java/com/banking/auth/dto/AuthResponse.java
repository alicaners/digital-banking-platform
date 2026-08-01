package com.banking.auth.dto;

public class AuthResponse {

    private String username;
    private String email;
    private String message;
    private String token;

    public AuthResponse(String username, String email, String message) {
        this.username = username;
        this.email = email;
        this.message = message;
    }

    public AuthResponse(String username, String email, String message, String token) {
        this.username = username;
        this.email = email;
        this.message = message;
        this.token = token;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}