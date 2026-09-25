package com.cris.customerportal.controller;

import com.cris.customerportal.dto.AuthUserData;
import com.cris.customerportal.dto.LoginRequest;
import com.cris.customerportal.dto.RegisterRequest;
import com.cris.customerportal.entity.MemUser;
import com.cris.customerportal.repository.MemUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * AuthController -- ported from Node backend/src/controllers/authController.ts
 *
 * POST /api/auth/register  -- create a new account in MEMUSERS
 * POST /api/auth/login     -- validate credentials and return user data
 *
 * No JWT/session: the frontend holds the returned user object in-memory,
 * matching the exact behaviour of the original Node implementation.
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin("*")
public class AuthController {

    // Validation constants (mirror Node implementation exactly)
    private static final int     USERNAME_MIN      = 3;
    private static final int     USERNAME_MAX      = 50;
    private static final Pattern EMAIL_PATTERN     = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern USERNAME_PATTERN  = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_\\-]{2,49}$");

    private final MemUserRepository userRepo;
    private final PasswordEncoder   passwordEncoder;

    public AuthController(MemUserRepository userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo        = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    // POST /api/auth/register
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        // 1. Required fields
        if (isBlank(req.getEmail()) || isBlank(req.getUsername())
                || isBlank(req.getPassword()) || isBlank(req.getConfirmPassword())) {
            return bad("All fields are required.");
        }

        String email    = req.getEmail().trim().toLowerCase();
        String username = req.getUsername().trim();
        String password = req.getPassword();

        // 2. Email format
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return bad("Please enter a valid email address.");
        }

        // 3. Username format
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return bad("Username must be 3-50 characters, start with a letter, and contain only letters, numbers, underscores, or hyphens.");
        }

        // 4. Password strength
        String pwError = checkPassword(password);
        if (pwError != null) {
            return bad(pwError);
        }

        // 5. Passwords match
        if (!password.equals(req.getConfirmPassword())) {
            return bad("Passwords do not match.");
        }

        // 6. Duplicate checks
        if (userRepo.existsByEmailIgnoreCase(email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "message", "An account with this email already exists."));
        }
        if (userRepo.existsByUsernameIgnoreCase(username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "message", "Username is already taken."));
        }

        // 7. Hash + save
        String hash = passwordEncoder.encode(password);
        MemUser user = new MemUser(email, username, hash);
        userRepo.save(user);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("success", true, "message", "Account created successfully. Please sign in."));
    }

    // POST /api/auth/login
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (isBlank(req.getUsername()) || isBlank(req.getPassword())) {
            return bad("Username and password are required.");
        }

        String username = req.getUsername().trim();

        Optional<MemUser> maybeUser = userRepo.findByUsernameIgnoreCase(username);

        if (maybeUser.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Invalid username or password."));
        }

        MemUser user = maybeUser.get();

        // Check active flag
        if (!"Y".equalsIgnoreCase(user.getActiveFlag())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "This account has been deactivated."));
        }

        // Validate password
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Invalid username or password."));
        }

        AuthUserData userData = new AuthUserData(user.getUserId(), user.getEmail(), user.getUsername());
        return ResponseEntity.ok(Map.of("success", true, "message", "Login successful.", "data", userData));
    }

    // Helpers

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Returns an error message if the password does not meet strength requirements,
     * or null if the password is valid. Mirrors Node checkPassword() exactly.
     */
    private String checkPassword(String password) {
        if (password.length() < 8)
            return "Password must be at least 8 characters long.";
        if (!password.chars().anyMatch(Character::isUpperCase))
            return "Password must contain at least one uppercase letter.";
        if (!password.chars().anyMatch(Character::isLowerCase))
            return "Password must contain at least one lowercase letter.";
        if (!password.chars().anyMatch(Character::isDigit))
            return "Password must contain at least one digit.";
        if (!password.chars().anyMatch(c -> !Character.isLetterOrDigit(c)))
            return "Password must contain at least one special character.";
        return null;
    }

    private ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", message));
    }
}