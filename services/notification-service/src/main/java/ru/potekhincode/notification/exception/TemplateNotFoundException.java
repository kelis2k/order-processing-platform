package ru.potekhincode.notification.exception;

import ru.potekhincode.notification.model.NotificationType;

public class TemplateNotFoundException extends RuntimeException {
    public TemplateNotFoundException(NotificationType type) {
        super("No email template for type=" + type);
    }
}
