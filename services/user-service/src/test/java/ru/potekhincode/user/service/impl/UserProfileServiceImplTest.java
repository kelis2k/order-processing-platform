package ru.potekhincode.user.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ru.potekhincode.user.dto.UpdateProfileRequest;
import ru.potekhincode.user.dto.UserProfileResponse;
import ru.potekhincode.user.exception.UserProfileNotFoundException;
import ru.potekhincode.user.mapper.UserProfileMapper;
import ru.potekhincode.user.model.OutboxEvent;
import ru.potekhincode.user.model.Role;
import ru.potekhincode.user.model.UserProfile;
import ru.potekhincode.user.outbox.OutboxEventFactory;
import ru.potekhincode.user.repository.OutboxRepository;
import ru.potekhincode.user.repository.UserProfileRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты сервисного слоя user-service на Mockito: репозитории, маппер и Outbox-коллабораторы
 * замоканы, проверяется только бизнес-логика (идемпотентное создание профиля из события,
 * чтение/обновление, смена роли с публикацией через Outbox и no-op при совпадении).
 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceImplTest {

    private static final UUID ID = UUID.randomUUID();
    private static final String EMAIL = "alice@example.com";
    private static final String USERNAME = "alice";

    @Mock
    private UserProfileRepository repository;
    @Mock
    private UserProfileMapper mapper;
    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private OutboxEventFactory outboxEventFactory;

    @InjectMocks
    private UserProfileServiceImpl service;

    // ---- createFromEvent (consumer user.created) ----

    @Test
    void createFromEventShouldSaveNewProfileWithRoleUser() {
        when(repository.existsById(ID)).thenReturn(false);

        service.createFromEvent(ID, EMAIL, USERNAME);

        ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
        verify(repository).save(captor.capture());
        UserProfile saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(ID);
        assertThat(saved.getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getUsername()).isEqualTo(USERNAME);
        assertThat(saved.getRole()).isEqualTo(Role.ROLE_USER);
    }

    @Test
    void createFromEventShouldBeIdempotentWhenProfileExists() {
        when(repository.existsById(ID)).thenReturn(true);

        service.createFromEvent(ID, EMAIL, USERNAME);

        verify(repository, never()).save(any());
    }

    // ---- findById ----

    @Test
    void findByIdShouldReturnMappedResponse() {
        UserProfile profile = profile(Role.ROLE_USER);
        UserProfileResponse response = response(Role.ROLE_USER);
        when(repository.findById(ID)).thenReturn(Optional.of(profile));
        when(mapper.toResponse(profile)).thenReturn(response);

        assertThat(service.findById(ID)).isEqualTo(response);
    }

    @Test
    void findByIdShouldThrowWhenMissing() {
        when(repository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(ID))
                .isInstanceOf(UserProfileNotFoundException.class);
    }

    // ---- list ----

    @Test
    void listShouldReturnMappedPage() {
        UserProfile profile = profile(Role.ROLE_USER);
        UserProfileResponse response = response(Role.ROLE_USER);
        Page<UserProfile> page = new PageImpl<>(List.of(profile));
        when(repository.findAll(any(Pageable.class))).thenReturn(page);
        when(mapper.toResponse(profile)).thenReturn(response);

        Page<UserProfileResponse> result = service.list(Pageable.unpaged());

        assertThat(result.getContent()).containsExactly(response);
    }

    // ---- update ----

    @Test
    void updateShouldChangeUsername() {
        UserProfile profile = profile(Role.ROLE_USER);
        when(repository.findById(ID)).thenReturn(Optional.of(profile));
        when(mapper.toResponse(profile)).thenReturn(response(Role.ROLE_USER));

        service.update(ID, new UpdateProfileRequest("renamed"));

        assertThat(profile.getUsername()).isEqualTo("renamed");
    }

    @Test
    void updateShouldThrowWhenMissing() {
        when(repository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(ID, new UpdateProfileRequest("renamed")))
                .isInstanceOf(UserProfileNotFoundException.class);
    }

    // ---- changeRole (RBAC + Outbox) ----

    @Test
    void changeRoleShouldSetRoleAndQueueOutboxEvent() {
        UserProfile profile = profile(Role.ROLE_USER);
        OutboxEvent event = new OutboxEvent();
        when(repository.findById(ID)).thenReturn(Optional.of(profile));
        when(outboxEventFactory.userRoleChanged(profile)).thenReturn(event);
        when(mapper.toResponse(profile)).thenReturn(response(Role.ROLE_MANAGER));

        service.changeRole(ID, Role.ROLE_MANAGER);

        assertThat(profile.getRole()).isEqualTo(Role.ROLE_MANAGER);
        verify(outboxRepository).save(event);
    }

    @Test
    void changeRoleShouldBeNoOpWhenSameRole() {
        UserProfile profile = profile(Role.ROLE_MANAGER);
        when(repository.findById(ID)).thenReturn(Optional.of(profile));
        when(mapper.toResponse(profile)).thenReturn(response(Role.ROLE_MANAGER));

        service.changeRole(ID, Role.ROLE_MANAGER);

        assertThat(profile.getRole()).isEqualTo(Role.ROLE_MANAGER);
        verify(outboxRepository, never()).save(any());
        verify(outboxEventFactory, never()).userRoleChanged(any());
    }

    @Test
    void changeRoleShouldThrowWhenMissing() {
        when(repository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeRole(ID, Role.ROLE_MANAGER))
                .isInstanceOf(UserProfileNotFoundException.class);
    }

    private UserProfile profile(Role role) {
        UserProfile profile = new UserProfile();
        profile.setId(ID);
        profile.setEmail(EMAIL);
        profile.setUsername(USERNAME);
        profile.setRole(role);
        return profile;
    }

    private UserProfileResponse response(Role role) {
        return new UserProfileResponse(ID, EMAIL, USERNAME, role, null, null);
    }
}
