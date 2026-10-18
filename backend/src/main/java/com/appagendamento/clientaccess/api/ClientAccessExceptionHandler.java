package com.appagendamento.clientaccess.api;

import com.appagendamento.clientaccess.application.AccessChallengeInvalidException;
import com.appagendamento.clientaccess.application.IdempotencyKeyConflictException;
import com.appagendamento.clientaccess.application.InvalidAccessPhoneException;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ClientAccessExceptionHandler {

  @ExceptionHandler(AccessChallengeInvalidException.class)
  ResponseEntity<?> invalidChallenge() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of(
                "error",
                Map.of(
                    "code",
                    "ACCESS_CHALLENGE_INVALID",
                    "message",
                    "Código inválido ou expirado.")));
  }

  @ExceptionHandler(IdempotencyKeyConflictException.class)
  ResponseEntity<?> reusedKey() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of(
                "error",
                Map.of(
                    "code",
                    "IDEMPOTENCY_KEY_REUSED",
                    "message",
                    "Chave de operação já utilizada.")));
  }

  @ExceptionHandler(InvalidAccessPhoneException.class)
  ResponseEntity<?> invalidPhone() {
    return ResponseEntity.badRequest()
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of(
                "error",
                Map.of(
                    "code", "INVALID_PHONE", "message", "Informe telefone internacional válido.")));
  }
}
