package ru.potekhincode.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.potekhincode.user.model.UserProfile;

import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

}
