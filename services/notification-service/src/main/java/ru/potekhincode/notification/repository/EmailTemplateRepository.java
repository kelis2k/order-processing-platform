package ru.potekhincode.notification.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ru.potekhincode.notification.model.EmailTemplate;
import ru.potekhincode.notification.model.NotificationType;

public interface EmailTemplateRepository extends MongoRepository<EmailTemplate, NotificationType> {

}
