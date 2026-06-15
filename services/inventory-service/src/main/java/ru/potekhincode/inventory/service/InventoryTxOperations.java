package ru.potekhincode.inventory.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.potekhincode.inventory.exception.InsufficientStockException;
import ru.potekhincode.inventory.model.Inventory;
import ru.potekhincode.inventory.repository.InventoryRepository;

/**
 * Транзакционные операции над запасами: одна попытка = одна транзакция.
 * <p>
 * Вынесены в отдельный бин намеренно. Retry на {@code ObjectOptimisticLockingFailureException}
 * должен запускать каждую попытку в НОВОЙ транзакции (исключение выбрасывается на commit,
 * после чего текущая транзакция помечена rollback-only, а сущность в персист-контексте
 * «отравлена»). Вызов этих методов через прокси из {@link InventoryService} гарантирует
 * новую транзакцию на каждую попытку. Self-invocation внутри одного бина AOP не перехватывает.
 */
@Component
@RequiredArgsConstructor
public class InventoryTxOperations {

    private final InventoryRepository inventoryRepository;

    @Transactional
    public void reserveOnce(String orderId, String productId, int quantity) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InsufficientStockException(productId, quantity, 0));

        if (inventory.getAvailable() < quantity) {
            throw new InsufficientStockException(productId, quantity, inventory.getAvailable());
        }

        inventory.setAvailable(inventory.getAvailable() - quantity);
        inventory.setReserved(inventory.getReserved() + quantity);
        inventoryRepository.save(inventory);
    }

    @Transactional
    public void releaseOnce(String orderId, String productId, int quantity) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalStateException("Inventory not found for product: " + productId));

        inventory.setReserved(Math.max(0, inventory.getReserved() - quantity));
        inventory.setAvailable(inventory.getAvailable() + quantity);
        inventoryRepository.save(inventory);
    }
}
