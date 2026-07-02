package ru.potekhincode.auth.exception;

public class InvalidConfirmationTokenException extends RuntimeException {
    public InvalidConfirmationTokenException() {
        super("Invalid or expired confirmation token");
    }
}
