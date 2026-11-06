package com.financetracker.controller.auth;

import java.util.Map;

import com.financetracker.model.dto.auth.ActionTokenRequestDto;
import com.financetracker.model.dto.auth.EmailRequestDto;
import com.financetracker.model.dto.auth.PasswordResetRequestDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/v1/auth")
public interface EmailAccountControllerApi {

    @PostMapping("/email/request")
    ResponseEntity<Map<String, String>> request(@Valid @RequestBody EmailRequestDto email);

    @PostMapping("/email/verify")
    ResponseEntity<Void> verify(@Valid @RequestBody ActionTokenRequestDto token);

    @PostMapping("/password/forgot")
    ResponseEntity<Map<String, String>> forgot(@Valid @RequestBody EmailRequestDto email);

    @PostMapping("/password/reset")
    ResponseEntity<Void> reset(@Valid @RequestBody PasswordResetRequestDto reset);
}
