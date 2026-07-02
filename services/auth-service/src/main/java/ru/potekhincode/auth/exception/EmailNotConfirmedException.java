package ru.potekhincode.auth.exception;

public class EmailNotConfirmedException extends RuntimeException {
    public EmailNotConfirmedException() {
        super("Email is not confirmed");
    }
}
