package bwj.stylehub.user.controller;

import bwj.stylehub.user.dto.request.UserLoginRequest;
import bwj.stylehub.user.dto.request.UserSignUpRequest;
import bwj.stylehub.user.dto.response.UserLoginResponse;
import bwj.stylehub.user.dto.response.UserSignUpResponse;
import bwj.stylehub.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/sign-up")
    public ResponseEntity<UserSignUpResponse> signUp(@Valid @RequestBody UserSignUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.signUp(request));
    }

    @PostMapping("/login")
    public ResponseEntity<UserLoginResponse> login(@Valid @RequestBody UserLoginRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }
}
