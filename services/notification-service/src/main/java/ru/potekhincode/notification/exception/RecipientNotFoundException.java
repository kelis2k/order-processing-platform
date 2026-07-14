package ru.potekhincode.notification.exception;

public class RecipientNotFoundException extends RuntimeException {
    public RecipientNotFoundException(String userId) {
        super("No recipient for userId=" + userId + " (user.created not consumed yet?)");
    }
}
