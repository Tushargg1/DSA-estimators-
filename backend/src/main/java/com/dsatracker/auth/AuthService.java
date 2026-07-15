package com.dsatracker.auth;

import com.dsatracker.model.User;
import com.dsatracker.repository.UserRepository;
import com.dsatracker.security.TokenService;
import com.dsatracker.service.UserService;
import com.dsatracker.web.CreateUserRequest;
import com.dsatracker.web.UserResponse;
import com.dsatracker.web.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {
    private static final String INVALID_CREDENTIALS = "Invalid email or password";
    private static final String ACTIVATION_FAILED = "Account activation failed";

    private final UserRepository users;
    private final UserService userService;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final String setupSecret;
    private final String dummyHash;

    public AuthService(UserRepository users, UserService userService, PasswordEncoder passwords,
                       TokenService tokens,
                       @Value("${auth.legacy-setup-secret:}") String setupSecret) {
        this.users = users;
        this.userService = userService;
        this.passwords = passwords;
        this.tokens = tokens;
        this.setupSecret = setupSecret;
        this.dummyHash = passwords.encode("dummy-password-never-used");
    }

    public AuthResponse register(RegisterRequest request) {
        validatePassword(request == null ? null : request.password());
        CreateUserRequest profile = new CreateUserRequest(
                request.name(), normalizeEmail(request.email()), request.leetcodeUsername(),
                request.codeforcesUsername(), request.gfgUsername(), request.dailyTarget());
        User user = userService.createUser(profile, passwords.encode(request.password()), true);
        return response(user);
    }

    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request == null ? null : request.email());
        String rawPassword = request == null || request.password() == null ? "" : request.password();
        Optional<User> candidate = email == null ? Optional.empty() : users.findByEmail(email);
        String hash = candidate.filter(User::isCredentialsEnabled)
                .map(User::getPasswordHash).orElse(dummyHash);
        boolean valid = passwords.matches(rawPassword, hash);
        User user = candidate.filter(User::isCredentialsEnabled).filter(ignored -> valid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS));
        return response(user);
    }

    @Transactional
    public AuthResponse activateLegacy(LegacyActivationRequest request) {
        if (setupSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        }
        String supplied = request == null || request.setupCode() == null ? "" : request.setupCode();
        boolean codeMatches = MessageDigest.isEqual(
                setupSecret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
        try {
            validatePassword(request == null ? null : request.password());
        } catch (ValidationException ex) {
            throw activationFailure();
        }
        String email = normalizeEmail(request == null ? null : request.email());
        Optional<User> candidate = codeMatches && email != null
                ? users.findByEmailForUpdate(email) : Optional.empty();
        User user = candidate.filter(existing -> !existing.isCredentialsEnabled())
                .orElse(null);
        if (user == null) {
            passwords.matches(request.password(), dummyHash);
            throw activationFailure();
        }
        user.setPasswordHash(passwords.encode(request.password()));
        user.setCredentialsEnabled(true);
        users.save(user);
        return response(user);
    }

    private AuthResponse response(User user) {
        TokenService.IssuedToken issued = tokens.issue(user.getId());
        return new AuthResponse(issued.value(), issued.expiresAt(), UserResponse.from(user));
    }

    private static void validatePassword(String password) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (password == null || password.length() < 8) {
            errors.put("password", "Password must be at least 8 characters.");
        } else if (password.length() > 72) {
            errors.put("password", "Password must be at most 72 characters.");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException activationFailure() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, ACTIVATION_FAILED);
    }
}
