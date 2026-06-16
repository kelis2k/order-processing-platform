package ru.potekhincode.product.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.stereotype.Service;
import ru.potekhincode.product.dto.request.CreateProductRequest;
import ru.potekhincode.product.dto.request.ProductSearchParams;
import ru.potekhincode.product.dto.request.UpdateProductRequest;
import ru.potekhincode.product.dto.response.ProductResponse;
import ru.potekhincode.product.exception.ProductNotFoundException;
import ru.potekhincode.product.mapper.ProductMapper;
import ru.potekhincode.product.model.Product;
import ru.potekhincode.product.repository.ProductRepository;
import ru.potekhincode.product.service.ProductService;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final MongoTemplate mongoTemplate;
    private final ProductMapper productMapper;

    @Override
    public ProductResponse create(CreateProductRequest request) {
        Product product = productMapper.toEntity(request);
        return productMapper.toResponse(productRepository.save(product));
    }

    @Override
    public ProductResponse findById(String id) {
        return productRepository.findById(id)
                .map(productMapper::toResponse)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Override
    public Page<ProductResponse> search(ProductSearchParams params, Pageable pageable) {
        List<Criteria> criteriaList = new ArrayList<>();

        if (params.getCategory() != null) {
            criteriaList.add(Criteria.where("category").is(params.getCategory()));
        }
        if (params.getPriceFrom() != null && params.getPriceTo() != null) {
            criteriaList.add(Criteria.where("price").gte(params.getPriceFrom()).lte(params.getPriceTo()));
        } else if (params.getPriceFrom() != null) {
            criteriaList.add(Criteria.where("price").gte(params.getPriceFrom()));
        } else if (params.getPriceTo() != null) {
            criteriaList.add(Criteria.where("price").lte(params.getPriceTo()));
        }
        if (params.getPublished() != null) {
            criteriaList.add(Criteria.where("published").is(params.getPublished()));
        }

        Query query = new Query();
        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }
        if (params.getText() != null && !params.getText().isBlank()) {
            query.addCriteria(TextCriteria.forDefaultLanguage().matching(params.getText()));
        }

        long total = mongoTemplate.count(query, Product.class);
        query.with(pageable);
        List<Product> products = mongoTemplate.find(query, Product.class);

        return new PageImpl<>(
                products.stream().map(productMapper::toResponse).toList(),
                pageable,
                total
        );
    }

    @Override
    public ProductResponse update(String id, UpdateProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        productMapper.updateEntity(request, product);
        return productMapper.toResponse(productRepository.save(product));
    }

    @Override
    public void delete(String id) {
        if (!productRepository.existsById(id)) {
            throw new ProductNotFoundException(id);
        }
        productRepository.deleteById(id);
    }

    @Override
    public ProductResponse setPublished(String id, boolean published) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        product.setPublished(published);
        return productMapper.toResponse(productRepository.save(product));
    }
}
