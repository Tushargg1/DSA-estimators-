package com.dsatracker.auth;

import com.dsatracker.web.UserResponse;

import java.time.Instant;

record RegisterRequest(String name, String email, String password,
                       String leetcodeUsername, String codeforcesUsername,
                       String gfgUsername, Integer dailyTarget) {
}

record LoginRequest(String email, String password) {
}

record GoogleLoginRequest(String credential) {
}

record LegacyActivationRequest(String email, String password, String setupCode) {
}

record AuthResponse(String token, Instant expiresAt, UserResponse user) {
}
