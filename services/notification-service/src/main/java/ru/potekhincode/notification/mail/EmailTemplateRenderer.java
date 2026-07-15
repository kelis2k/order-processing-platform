package ru.potekhincode.notification.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import ru.potekhincode.notification.exception.TemplateNotFoundException;
import ru.potekhincode.notification.model.EmailTemplate;
import ru.potekhincode.notification.model.NotificationType;
import ru.potekhincode.notification.repository.EmailTemplateRepository;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class EmailTemplateRenderer {

    private final EmailTemplateRepository templateRepository;
    private final SpringTemplateEngine emailTemplateEngine;

    public RenderedEmail render(NotificationType type, Map<String, Object> variables) {
        EmailTemplate template = templateRepository.findById(type)
                .orElseThrow(() -> new TemplateNotFoundException(type));

        Context context = new Context();
        context.setVariables(variables);

        String subject = emailTemplateEngine.process(template.getSubject(), context).strip();
        String body = emailTemplateEngine.process(template.getBody(), context);
        return new RenderedEmail(subject, body);
    }
}
