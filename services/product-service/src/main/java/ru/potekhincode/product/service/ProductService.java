package ru.potekhincode.product.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.potekhincode.product.dto.request.CreateProductRequest;
import ru.potekhincode.product.dto.request.ProductSearchParams;
import ru.potekhincode.product.dto.request.UpdateProductRequest;
import ru.potekhincode.product.dto.response.ProductResponse;

public interface ProductService {

    ProductResponse create(CreateProductRequest request);

    ProductResponse findById(String id);

    Page<ProductResponse> search(ProductSearchParams params, Pageable pageable);

    ProductResponse update(String id, UpdateProductRequest request);

    void delete(String id);

    ProductResponse setPublished(String id, boolean published);
}
