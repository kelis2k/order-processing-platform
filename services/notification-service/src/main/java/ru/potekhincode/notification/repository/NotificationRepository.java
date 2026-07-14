package ru.potekhincode.notification.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ru.potekhincode.notification.model.Notification;

public interface NotificationRepository extends MongoRepository<Notification, String> {
}
