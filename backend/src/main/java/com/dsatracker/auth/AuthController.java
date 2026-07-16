package com.dsatracker.auth;

import com.dsatracker.model.User;
import com.dsatracker.security.AccessService;
import com.dsatracker.service.UserService;
import com.dsatracker.web.ErrorResponse;
import com.dsatracker.web.UserResponse;
import com.dsatracker.web.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final UserService users;
    private final AccessService access;

    public AuthController(AuthService authService, UserService users, AccessService access) {
        this.authService = authService;
        this.users = users;
        this.access = access;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/google")
    public AuthResponse googleLogin(@RequestBody GoogleLoginRequest request) {
        return authService.googleLogin(request);
    }

    @PostMapping("/legacy-activate")
    public AuthResponse activateLegacy(@RequestBody LegacyActivationRequest request) {
        return authService.activateLegacy(request);
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        User user = users.getUser(access.userId(authentication));
        return UserResponse.from(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleValidation(ValidationException ex) {
        return new ErrorResponse(ex.getErrors());
    }
}
