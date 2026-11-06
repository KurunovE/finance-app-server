package com.financetracker.controller.auth;

import java.util.Map;

import com.financetracker.model.constants.JsonFields;
import com.financetracker.model.constants.StatusValues;
import com.financetracker.model.dto.auth.ActionTokenRequestDto;
import com.financetracker.model.dto.auth.EmailRequestDto;
import com.financetracker.model.dto.auth.PasswordResetRequestDto;
import com.financetracker.service.auth.EmailAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public final class EmailAccountController implements EmailAccountControllerApi {

    private final EmailAccountService accounts;

    @Override
    public ResponseEntity<Map<String, String>> request(EmailRequestDto email) {
        accounts.requestVerification(email.email());

        return ResponseEntity.accepted().body(Map.of(JsonFields.STATUS, StatusValues.ACCEPTED));
    }

    @Override
    public ResponseEntity<Void> verify(ActionTokenRequestDto token) {
        accounts.verify(token.token());

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Map<String, String>> forgot(EmailRequestDto email) {
        accounts.forgotPassword(email.email());

        return ResponseEntity.accepted().body(Map.of(JsonFields.STATUS, StatusValues.ACCEPTED));
    }

    @Override
    public ResponseEntity<Void> reset(PasswordResetRequestDto reset) {
        accounts.reset(reset.token(), reset.password());

        return ResponseEntity.noContent().build();
    }
}
