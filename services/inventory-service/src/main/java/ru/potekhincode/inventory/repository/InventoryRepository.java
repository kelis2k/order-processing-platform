package ru.potekhincode.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.potekhincode.inventory.model.Inventory;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    Optional<Inventory> findByProductId(String productId);
}
