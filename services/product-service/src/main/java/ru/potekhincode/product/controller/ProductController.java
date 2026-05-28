package ru.potekhincode.product.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.potekhincode.product.dto.request.CreateProductRequest;
import ru.potekhincode.product.dto.request.ProductSearchParams;
import ru.potekhincode.product.dto.request.UpdateProductRequest;
import ru.potekhincode.product.dto.response.ProductResponse;
import ru.potekhincode.product.service.ProductService;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody CreateProductRequest request) {
        return productService.create(request);
    }

    @GetMapping("/{id}")
    public ProductResponse findById(@PathVariable String id) {
        return productService.findById(id);
    }

    @GetMapping
    public Page<ProductResponse> search(
            ProductSearchParams params,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return productService.search(params, pageable);
    }

    @PutMapping("/{id}")
    public ProductResponse update(
            @PathVariable String id,
            @Valid @RequestBody UpdateProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        productService.delete(id);
    }

    @PatchMapping("/{id}/publish")
    public ProductResponse setPublished(
            @PathVariable String id,
            @RequestParam boolean published) {
        return productService.setPublished(id, published);
    }
}
