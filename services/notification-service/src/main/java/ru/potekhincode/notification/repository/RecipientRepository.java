package ru.potekhincode.notification.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ru.potekhincode.notification.model.Recipient;

public interface RecipientRepository extends MongoRepository<Recipient, String> {
}
