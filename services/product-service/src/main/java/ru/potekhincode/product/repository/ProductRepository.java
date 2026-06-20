package ru.potekhincode.product.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import ru.potekhincode.product.model.Product;

import java.math.BigDecimal;

public interface ProductRepository extends MongoRepository<Product, String> {

    Page<Product> findByPublished(boolean published, Pageable pageable);
    Page<Product> findByCategory(String category, Pageable pageable);
    Page<Product> findByPriceBetween(BigDecimal from, BigDecimal to, Pageable pageable);
}
