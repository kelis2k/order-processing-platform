package ru.potekhincode.notification.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.potekhincode.notification.model.EmailTemplate;
import ru.potekhincode.notification.model.NotificationType;
import ru.potekhincode.notification.repository.EmailTemplateRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailTemplateSeeder implements ApplicationRunner {

    private final EmailTemplateRepository templateRepository;

    @Override
    public void run(ApplicationArguments args) {
        seed(NotificationType.USER_CONFIRMATION,
                "Подтвердите регистрацию",
                """
                        <p>Здравствуйте, [[${username}]]!</p>
                        <p>Для завершения регистрации подтвердите адрес:</p>
                        <p><a th:href="${confirmUrl}">[[${confirmUrl}]]</a></p>
                        <p>Ссылка действует до [[${expiresAt}]].</p>
                        """);

        seed(NotificationType.ORDER_ACCEPTED,
                "Заказ [[${orderId}]] принят",
                """
                        <p>Здравствуйте, [[${username}]]!</p>
                        <p>Ваш заказ <b>[[${orderId}]]</b> принят и обрабатывается.</p>
                        """);

        seed(NotificationType.ORDER_STATUS_CHANGED,
                "Заказ [[${orderId}]] — [[${status}]]",
                """
                <p>Здравствуйте, [[${username}]]!</p>
                <p>Статус заказа <b>[[${orderId}]]</b>: <b>[[${status}]]</b>.</p>
                <p th:if="${reason != null}">Причина: [[${reason}]]</p>
                """);
    }

    private void seed(NotificationType type, String subject, String body) {
        if (templateRepository.existsById(type)) {
            return;
        }
        templateRepository.save(new EmailTemplate(type, subject, body));
        log.info("Seeded email template: {}", type);
    }
}
