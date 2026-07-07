package ru.potekhincode.user.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.potekhincode.user.dto.UpdateProfileRequest;
import ru.potekhincode.user.dto.UserProfileResponse;
import ru.potekhincode.user.exception.UserProfileNotFoundException;
import ru.potekhincode.user.mapper.UserProfileMapper;
import ru.potekhincode.user.model.Role;
import ru.potekhincode.user.model.UserProfile;
import ru.potekhincode.user.outbox.OutboxEventFactory;
import ru.potekhincode.user.repository.OutboxRepository;
import ru.potekhincode.user.repository.UserProfileRepository;
import ru.potekhincode.user.service.UserProfileService;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private final UserProfileRepository repository;
    private final UserProfileMapper mapper;
    private final OutboxRepository outboxRepository;
    private final OutboxEventFactory outboxEventFactory;

    @Override
    @Transactional
    public void createFromEvent(UUID id, String email, String username) {
        if (repository.existsById(id)) {
            log.info("Profile already exists for userId={}, skipping (idempotent)", id);
            return;
        }

        UserProfile profile = new UserProfile();
        profile.setId(id);
        profile.setEmail(email);
        profile.setUsername(username);
        repository.save(profile);
        log.info("Created user profile from user.created: id={}, email={}", id, email);
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse findById(UUID id) {
        UserProfile profile = repository.findById(id)
                .orElseThrow(() -> new UserProfileNotFoundException(id));
        return mapper.toResponse(profile);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> list(Pageable pageable) {
        return repository.findAll(pageable).map(mapper::toResponse);
    }

    @Override
    @Transactional
    public UserProfileResponse update(UUID id, UpdateProfileRequest request) {
        UserProfile profile = repository.findById(id)
                .orElseThrow(() -> new UserProfileNotFoundException(id));

        profile.setUsername(request.username());
        return mapper.toResponse(profile);
    }

    @Override
    @Transactional
    public UserProfileResponse changeRole(UUID id, Role newRole) {
        UserProfile profile = repository.findById(id)
                .orElseThrow(() -> new UserProfileNotFoundException(id));

        if (profile.getRole() == newRole) {
            log.info("Role unchanged for userId={} ({}), no event emitted", id, newRole);
            return mapper.toResponse(profile);
        }

        Role old = profile.getRole();
        profile.setRole(newRole);
        outboxRepository.save(outboxEventFactory.userRoleChanged(profile));
        log.info("Changed role for userId={}: {} -> {}, user.role-changed queued", id, old, newRole);
        return mapper.toResponse(profile);
    }
}
