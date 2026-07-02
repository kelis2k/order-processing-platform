package ru.potekhincode.auth.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EmailAlreadyUsedException.class)
    public ResponseEntity<ProblemDetail> handleEmailInUse(EmailAlreadyUsedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Email already in use"));
    }

    @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
    public ResponseEntity<ProblemDetail> handleUnauthorized(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, details));
    }

    @ExceptionHandler(InvalidConfirmationTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidConfirmation(InvalidConfirmationTokenException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(EmailNotConfirmedException.class)
    public ResponseEntity<ProblemDetail> handleEmailNotConfirmed(EmailNotConfirmedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage()));
    }
}