package ru.potekhincode.user.bootstrap;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.potekhincode.user.model.Role;
import ru.potekhincode.user.repository.UserProfileRepository;
import ru.potekhincode.user.service.UserProfileService;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    @Value("${app.bootstrap.admin-email:}")
    private String adminEmail;

    private final UserProfileRepository repository;
    private final UserProfileService service;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!StringUtils.hasText(adminEmail)) {
            log.debug("app.bootstrap.admin-email не задан — seed ADMIN пропущен");
            return;
        }

        repository.findByEmail(adminEmail).ifPresentOrElse(
                profile -> service.changeRole(profile.getId(), Role.ROLE_ADMIN),
                () -> log.warn("Bootstrap: профиль {} не найден — сначала зарегистрируй+подтверди, потом перезапусти user-service",
                        adminEmail)
        );
    }
}
