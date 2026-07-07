package ru.potekhincode.user.mapper;

import org.mapstruct.Mapper;
import ru.potekhincode.user.dto.UserProfileResponse;
import ru.potekhincode.user.model.UserProfile;

@Mapper(componentModel = "spring")
public interface UserProfileMapper {

    UserProfileResponse toResponse(UserProfile profile);
}
