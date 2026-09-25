package com.cris.customerportal.repository;

import com.cris.customerportal.entity.MemUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MemUserRepository extends JpaRepository<MemUser, Long> {
    Optional<MemUser> findByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByUsernameIgnoreCase(String username);
}
