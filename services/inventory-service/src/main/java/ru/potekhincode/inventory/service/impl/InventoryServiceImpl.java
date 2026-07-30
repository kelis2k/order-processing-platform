package ru.potekhincode.inventory.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.potekhincode.inventory.dto.StockResponse;
import ru.potekhincode.inventory.exception.StockNotFoundException;
import ru.potekhincode.inventory.model.Inventory;
import ru.potekhincode.inventory.repository.InventoryRepository;
import ru.potekhincode.inventory.service.InventoryService;
import ru.potekhincode.inventory.service.InventoryTxOperations;
import ru.potekhincode.inventory.service.ReservationItem;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private static final int MAX_RETRIES = 3;

    private final InventoryRepository inventoryRepository;
    private final InventoryTxOperations txOperations;

    @Override
    @Transactional
    public StockResponse setStock(String productId, int available) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseGet(() -> {
                    Inventory created = new Inventory();
                    created.setProductId(productId);
                    created.setReserved(0);
                    return created;
                });
        inventory.setAvailable(available);
        Inventory saved = inventoryRepository.save(inventory);
        return new StockResponse(saved.getProductId(), saved.getAvailable(), saved.getReserved());
    }

    @Override
    @Transactional(readOnly = true)
    public StockResponse getStock(String productId) {
        return inventoryRepository.findByProductId(productId)
                .map(inv -> new StockResponse(inv.getProductId(), inv.getAvailable(), inv.getReserved()))
                .orElseThrow(() -> new StockNotFoundException(productId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean checkAvailability(String productId, int quantity) {
        return inventoryRepository.findByProductId(productId)
                .map(inv -> inv.getAvailable() >= quantity)
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public int getAvailableQuantity(String productId) {
        return inventoryRepository.findByProductId(productId)
                .map(Inventory::getAvailable)
                .orElse(0);
    }

    @Override
    public void reserve(String orderId, String productId, int quantity) {
        executeWithRetry(() -> txOperations.reserveOnce(orderId, productId, quantity), "reserve");
    }

    @Override
    public void reserve(String orderId, List<ReservationItem> items) {
        executeWithRetry(() -> txOperations.reserveAllOnce(orderId, items), "reserve");
    }

    @Override
    public void release(String orderId, String productId, int quantity) {
        executeWithRetry(() -> txOperations.releaseOnce(orderId, productId, quantity), "release");
    }

    /**
     * Запускает операцию с повтором при конфликте оптимистичной блокировки.
     * Каждая попытка — отдельный вызов транзакционного бина через прокси,
     * то есть новая транзакция и свежее чтение сущности.
     */
    private void executeWithRetry(Runnable action, String operation) {
        int attempts = 0;
        while (true) {
            try {
                action.run();
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                attempts++;
                if (attempts >= MAX_RETRIES) {
                    throw new IllegalStateException(
                            "Failed to " + operation + " after " + MAX_RETRIES + " attempts", e);
                }
            }
        }
    }
}
