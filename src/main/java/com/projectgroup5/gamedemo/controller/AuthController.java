package com.projectgroup5.gamedemo.controller;

import com.projectgroup5.gamedemo.service.AuthService;
import com.projectgroup5.gamedemo.dto.LoginRequest;
import com.projectgroup5.gamedemo.dto.LoginResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request.getUsername() == null || request.getPassword() == null) {
            return ResponseEntity.badRequest().body("Missing username or password");
        }
        Optional<LoginResponse> resp = authService.login(request.getUsername(), request.getPassword());
        if (resp.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid username or password");
        }
        return ResponseEntity.ok(resp.get());
    }

    @GetMapping("/auth/me")
    public ResponseEntity<?> me(@RequestHeader(name = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing token");
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        Optional<LoginResponse.UserDto> userOpt = authService.validateToken(token);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid token");
        }
        return ResponseEntity.ok(userOpt.get());
    }
}
