package ru.potekhincode.user.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.potekhincode.user.dto.UpdateProfileRequest;
import ru.potekhincode.user.dto.UserProfileResponse;
import ru.potekhincode.user.model.Role;

import java.util.UUID;

public interface UserProfileService {

    void createFromEvent(UUID id, String email, String username);

    UserProfileResponse findById(UUID id);

    Page<UserProfileResponse> list(Pageable pageable);

    UserProfileResponse update(UUID id, UpdateProfileRequest request);

    UserProfileResponse changeRole(UUID id, Role newRole);
}
