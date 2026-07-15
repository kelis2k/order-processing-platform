package ru.potekhincode.notification.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import ru.potekhincode.notification.exception.TemplateNotFoundException;
import ru.potekhincode.notification.model.EmailTemplate;
import ru.potekhincode.notification.model.NotificationType;
import ru.potekhincode.notification.repository.EmailTemplateRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Рендеринг шаблона на НАСТОЯЩЕМ Thymeleaf-движке (StringTemplateResolver, как в проде),
 * репозиторий шаблонов замокан. Проверяем подстановку переменных и условный блок th:if.
 */
class EmailTemplateRendererTest {

    private static final String ORDER_ID = "order-42";

    private EmailTemplateRepository templateRepository;
    private EmailTemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        templateRepository = mock(EmailTemplateRepository.class);
        renderer = new EmailTemplateRenderer(templateRepository, realEngine());
    }

    /** Копия ThymeleafConfig.emailTemplateEngine() — движок должен вести себя как в проде. */
    private SpringTemplateEngine realEngine() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false);
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private void stubTemplate(NotificationType type, String subject, String body) {
        when(templateRepository.findById(type))
                .thenReturn(Optional.of(new EmailTemplate(type, subject, body)));
    }

    @Test
    void shouldSubstituteVariablesInSubjectAndBody() {
        stubTemplate(NotificationType.ORDER_ACCEPTED,
                "Заказ [[${orderId}]] принят",
                "<p>Здравствуйте, [[${username}]]! Заказ [[${orderId}]].</p>");

        RenderedEmail email = renderer.render(NotificationType.ORDER_ACCEPTED,
                Map.of("orderId", ORDER_ID, "username", "tester"));

        assertThat(email.subject()).isEqualTo("Заказ order-42 принят");
        assertThat(email.body()).contains("Здравствуйте, tester!").contains("Заказ order-42.");
    }

    /** th:if показывает причину только когда reason != null (ветка отмены). */
    @Test
    void shouldRenderReasonBlockWhenReasonPresent() {
        stubTemplate(NotificationType.ORDER_STATUS_CHANGED,
                "Заказ [[${orderId}]] — [[${status}]]",
                "<p th:if=\"${reason != null}\">Причина: [[${reason}]]</p>");

        Map<String, Object> vars = new HashMap<>();
        vars.put("orderId", ORDER_ID);
        vars.put("status", "CANCELLED");
        vars.put("reason", "нет на складе");

        RenderedEmail email = renderer.render(NotificationType.ORDER_STATUS_CHANGED, vars);

        assertThat(email.subject()).isEqualTo("Заказ order-42 — CANCELLED");
        assertThat(email.body()).contains("Причина: нет на складе");
    }

    /** Тот же шаблон при reason == null (например, RESERVED) не показывает блок причины. */
    @Test
    void shouldOmitReasonBlockWhenReasonNull() {
        stubTemplate(NotificationType.ORDER_STATUS_CHANGED,
                "Заказ [[${orderId}]] — [[${status}]]",
                "<p th:if=\"${reason != null}\">Причина: [[${reason}]]</p>");

        Map<String, Object> vars = new HashMap<>();
        vars.put("orderId", ORDER_ID);
        vars.put("status", "RESERVED");
        vars.put("reason", null);

        RenderedEmail email = renderer.render(NotificationType.ORDER_STATUS_CHANGED, vars);

        assertThat(email.body()).doesNotContain("Причина");
    }

    @Test
    void shouldThrowWhenTemplateMissing() {
        when(templateRepository.findById(NotificationType.ORDER_ACCEPTED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> renderer.render(NotificationType.ORDER_ACCEPTED, Map.of()))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessageContaining("ORDER_ACCEPTED");
    }
}
