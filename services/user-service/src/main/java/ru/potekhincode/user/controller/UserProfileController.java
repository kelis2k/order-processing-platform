package ru.potekhincode.user.controller;


import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;
import ru.potekhincode.user.dto.UpdateProfileRequest;
import ru.potekhincode.user.dto.UpdateRoleRequest;
import ru.potekhincode.user.dto.UserProfileResponse;
import ru.potekhincode.user.service.UserProfileService;

import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserProfileController {
    private final UserProfileService service;

    @GetMapping("/{id}")
    public UserProfileResponse findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    public Page<UserProfileResponse> list(@PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return service.list(pageable);
    }

    @PatchMapping("/{id}")
    public UserProfileResponse update(@PathVariable UUID id,
                                      @Valid @RequestBody UpdateProfileRequest request) {
        return service.update(id, request);
    }

    @PutMapping("/{id}/role")
    public UserProfileResponse changeRole(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateRoleRequest request) {
        return service.changeRole(id, request.role());
    }
}
