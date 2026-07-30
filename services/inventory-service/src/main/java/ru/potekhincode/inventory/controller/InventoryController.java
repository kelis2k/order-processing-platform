package ru.potekhincode.inventory.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.potekhincode.inventory.dto.SetStockRequest;
import ru.potekhincode.inventory.dto.StockResponse;
import ru.potekhincode.inventory.service.InventoryService;

@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PutMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Задать остаток товара на складе (идемпотентно)")
    public StockResponse setStock(@PathVariable String productId,
                                  @Valid @RequestBody SetStockRequest request) {
        return inventoryService.setStock(productId, request.available());
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Остаток и резерв по товару")
    public StockResponse getStock(@PathVariable String productId) {
        return inventoryService.getStock(productId);
    }
}
