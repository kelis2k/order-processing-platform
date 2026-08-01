package ru.potekhincode.inventory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import ru.potekhincode.inventory.exception.InsufficientStockException;
import ru.potekhincode.inventory.dto.StockResponse;
import ru.potekhincode.inventory.exception.StockNotFoundException;
import ru.potekhincode.inventory.model.Inventory;
import ru.potekhincode.inventory.repository.InventoryRepository;
import ru.potekhincode.inventory.repository.ReservationRepository;
import ru.potekhincode.inventory.service.impl.InventoryServiceImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    private static final String ORDER_ID = "order-1";
    private static final String PRODUCT_ID = "000000000000000000000001";
    private static final int QUANTITY = 3;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private InventoryTxOperations txOperations;

    @InjectMocks
    private InventoryServiceImpl inventoryService;

    @Test
    void setStockCreatesPositionWhenAbsent() {
        when(inventoryRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.empty());
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(inv -> inv.getArgument(0));

        StockResponse response = inventoryService.setStock(PRODUCT_ID, 25);

        assertThat(response.productId()).isEqualTo(PRODUCT_ID);
        assertThat(response.available()).isEqualTo(25);
        assertThat(response.reserved()).isZero();
    }

    @Test
    void setStockUpdatesExistingPositionKeepingReserved() {
        Inventory existing = inventory(5);
        existing.setReserved(2);
        when(inventoryRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(existing));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(inv -> inv.getArgument(0));

        StockResponse response = inventoryService.setStock(PRODUCT_ID, 40);

        assertThat(response.available()).isEqualTo(40);
        assertThat(response.reserved()).isEqualTo(2);
    }

    @Test
    void setStockIsIdempotent() {
        Inventory existing = inventory(7);
        when(inventoryRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(existing));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(inv -> inv.getArgument(0));

        StockResponse first = inventoryService.setStock(PRODUCT_ID, 12);
        StockResponse second = inventoryService.setStock(PRODUCT_ID, 12);

        assertThat(first.available()).isEqualTo(second.available()).isEqualTo(12);
    }

    @Test
    void getStockThrowsWhenPositionAbsent() {
        when(inventoryRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.getStock(PRODUCT_ID))
                .isInstanceOf(StockNotFoundException.class)
                .hasMessageContaining(PRODUCT_ID);
    }

    private Inventory inventory(int available) {
        return Inventory.builder()
                .id(1L)
                .productId(PRODUCT_ID)
                .available(available)
                .reserved(0)
                .version(0)
                .build();
    }

    @Test
    void checkAvailabilityReturnsTrueWhenEnoughStock() {
        when(inventoryRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(inventory(10)));

        assertThat(inventoryService.checkAvailability(PRODUCT_ID, QUANTITY)).isTrue();
    }

    @Test
    void checkAvailabilityReturnsFalseWhenNotEnoughStock() {
        when(inventoryRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(inventory(1)));

        assertThat(inventoryService.checkAvailability(PRODUCT_ID, QUANTITY)).isFalse();
    }

    @Test
    void checkAvailabilityReturnsFalseWhenProductMissing() {
        when(inventoryRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThat(inventoryService.checkAvailability(PRODUCT_ID, QUANTITY)).isFalse();
    }

    @Test
    void getAvailableQuantityReturnsZeroWhenProductMissing() {
        when(inventoryRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThat(inventoryService.getAvailableQuantity(PRODUCT_ID)).isZero();
    }

    @Test
    void getAvailableQuantityReturnsValueWhenPresent() {
        when(inventoryRepository.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(inventory(7)));

        assertThat(inventoryService.getAvailableQuantity(PRODUCT_ID)).isEqualTo(7);
    }

    @Test
    void reserveDelegatesToTransactionalOpsOnce() {
        doNothing().when(txOperations).reserveOnce(ORDER_ID, PRODUCT_ID, QUANTITY);

        inventoryService.reserve(ORDER_ID, PRODUCT_ID, QUANTITY);

        verify(txOperations, times(1)).reserveOnce(ORDER_ID, PRODUCT_ID, QUANTITY);
    }

    @Test
    void reservePropagatesInsufficientStockWithoutRetry() {
        doThrow(new InsufficientStockException(PRODUCT_ID, QUANTITY, 0))
                .when(txOperations).reserveOnce(ORDER_ID, PRODUCT_ID, QUANTITY);

        assertThatThrownBy(() -> inventoryService.reserve(ORDER_ID, PRODUCT_ID, QUANTITY))
                .isInstanceOf(InsufficientStockException.class);

        verify(txOperations, times(1)).reserveOnce(ORDER_ID, PRODUCT_ID, QUANTITY);
    }

    @Test
    void reserveRetriesOnOptimisticLockThenSucceeds() {
        doThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L))
                .doThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L))
                .doNothing()
                .when(txOperations).reserveOnce(ORDER_ID, PRODUCT_ID, QUANTITY);

        inventoryService.reserve(ORDER_ID, PRODUCT_ID, QUANTITY);

        verify(txOperations, times(3)).reserveOnce(ORDER_ID, PRODUCT_ID, QUANTITY);
    }

    @Test
    void reserveFailsAfterExhaustingRetries() {
        doThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L))
                .when(txOperations).reserveOnce(anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> inventoryService.reserve(ORDER_ID, PRODUCT_ID, QUANTITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserve");

        verify(txOperations, times(3)).reserveOnce(ORDER_ID, PRODUCT_ID, QUANTITY);
    }

    @Test
    void releaseRetriesOnOptimisticLockThenSucceeds() {
        doThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L))
                .doNothing()
                .when(txOperations).releaseOnce(ORDER_ID, PRODUCT_ID, QUANTITY);

        inventoryService.release(ORDER_ID, PRODUCT_ID, QUANTITY);

        verify(txOperations, times(2)).releaseOnce(ORDER_ID, PRODUCT_ID, QUANTITY);
    }

    @Test
    void releaseFailsAfterExhaustingRetries() {
        doThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L))
                .when(txOperations).releaseOnce(anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> inventoryService.release(ORDER_ID, PRODUCT_ID, QUANTITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("release");

        verify(txOperations, times(3)).releaseOnce(ORDER_ID, PRODUCT_ID, QUANTITY);
    }

    @Test
    void reserveAllSkipsSilentlyOnDuplicateOrder() {
        List<ReservationItem> items = List.of(new ReservationItem(PRODUCT_ID, QUANTITY));
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .when(txOperations).reserveAllOnce(ORDER_ID, items);
        when(reservationRepository.existsByOrderId(ORDER_ID)).thenReturn(true);

        inventoryService.reserve(ORDER_ID, items);

        verify(txOperations, times(1)).reserveAllOnce(ORDER_ID, items);
    }

    @Test
    void reserveAllDelegatesOnceWhenOrderIsNew() {
        List<ReservationItem> items = List.of(new ReservationItem(PRODUCT_ID, QUANTITY));
        doNothing().when(txOperations).reserveAllOnce(ORDER_ID, items);

        inventoryService.reserve(ORDER_ID, items);

        verify(txOperations, times(1)).reserveAllOnce(ORDER_ID, items);
    }

    @Test
    void reserveAllStillPropagatesInsufficientStock() {
        List<ReservationItem> items = List.of(new ReservationItem(PRODUCT_ID, QUANTITY));
        doThrow(new InsufficientStockException(PRODUCT_ID, QUANTITY, 1))
                .when(txOperations).reserveAllOnce(ORDER_ID, items);

        assertThatThrownBy(() -> inventoryService.reserve(ORDER_ID, items))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void reserveAllRethrowsIntegrityErrorThatIsNotDuplicate() {
        List<ReservationItem> items = List.of(new ReservationItem(PRODUCT_ID, QUANTITY));
        doThrow(new DataIntegrityViolationException("value too long for type character varying(36)"))
                .when(txOperations).reserveAllOnce(ORDER_ID, items);
        when(reservationRepository.existsByOrderId(ORDER_ID)).thenReturn(false);

        assertThatThrownBy(() -> inventoryService.reserve(ORDER_ID, items))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
