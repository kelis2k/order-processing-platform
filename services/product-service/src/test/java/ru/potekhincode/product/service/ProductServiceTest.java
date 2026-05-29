package ru.potekhincode.product.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import ru.potekhincode.product.dto.request.CreateProductRequest;
import ru.potekhincode.product.dto.request.ProductSearchParams;
import ru.potekhincode.product.dto.request.UpdateProductRequest;
import ru.potekhincode.product.dto.response.ProductResponse;
import ru.potekhincode.product.exception.ProductNotFoundException;
import ru.potekhincode.product.mapper.ProductMapper;
import ru.potekhincode.product.model.Product;
import ru.potekhincode.product.repository.ProductRepository;
import ru.potekhincode.product.service.impl.ProductServiceImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final String PRODUCT_ID              = "1";
    private static final String UNKNOWN_ID              = "unknown";
    private static final String PRODUCT_NAME            = "Phone";
    private static final String CATEGORY_PHONES         = "phones";
    private static final int    SEARCH_PAGE_NUMBER      = 0;
    private static final int    SEARCH_PAGE_SIZE        = 20;
    private static final long   EXPECTED_TOTAL_ELEMENTS = 1L;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private ProductServiceImpl productService;

    private Product product;

    @BeforeEach
    void setUp() {
        product = new Product();
    }

    @Test
    void shouldSaveAndReturnResponseWhenCreatingProduct() {
        CreateProductRequest request = new CreateProductRequest();
        ProductResponse response = ProductResponse.builder().id(PRODUCT_ID).name(PRODUCT_NAME).build();

        when(productMapper.toEntity(request)).thenReturn(product);
        when(productRepository.save(product)).thenReturn(product);
        when(productMapper.toResponse(product)).thenReturn(response);

        ProductResponse result = productService.create(request);

        assertThat(result.getId()).isEqualTo(PRODUCT_ID);
        verify(productRepository).save(product);
    }

    @Test
    void shouldReturnProductResponseWhenFindingByExistingId() {
        ProductResponse response = ProductResponse.builder().id(PRODUCT_ID).build();

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productMapper.toResponse(product)).thenReturn(response);

        assertThat(productService.findById(PRODUCT_ID).getId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    void shouldThrowNotFoundExceptionWhenFindingByUnknownId() {
        when(productRepository.findById(UNKNOWN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.findById(UNKNOWN_ID))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining(UNKNOWN_ID);
    }

    @Test
    void shouldUpdateAndReturnResponseWhenProductExists() {
        UpdateProductRequest request = new UpdateProductRequest();
        ProductResponse response = ProductResponse.builder().id(PRODUCT_ID).build();

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productRepository.save(product)).thenReturn(product);
        when(productMapper.toResponse(product)).thenReturn(response);

        assertThat(productService.update(PRODUCT_ID, request).getId()).isEqualTo(PRODUCT_ID);
        verify(productMapper).updateEntity(eq(request), eq(product));
    }

    @Test
    void shouldDeleteProductWhenProductExists() {
        when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);

        productService.delete(PRODUCT_ID);

        verify(productRepository).deleteById(PRODUCT_ID);
    }

    @Test
    void shouldThrowNotFoundExceptionWhenDeletingByUnknownId() {
        when(productRepository.existsById(UNKNOWN_ID)).thenReturn(false);

        assertThatThrownBy(() -> productService.delete(UNKNOWN_ID))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void shouldTogglePublishedFlagWhenProductExists() {
        product = Product.builder().id(PRODUCT_ID).published(false).build();
        ProductResponse response = ProductResponse.builder().id(PRODUCT_ID).published(true).build();

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productRepository.save(product)).thenReturn(product);
        when(productMapper.toResponse(product)).thenReturn(response);

        ProductResponse result = productService.setPublished(PRODUCT_ID, true);

        assertThat(result.isPublished()).isTrue();
        assertThat(product.isPublished()).isTrue();
    }

    @Test
    void shouldReturnFilteredPageWhenCategoryFilterProvided() {
        ProductSearchParams params = new ProductSearchParams();
        params.setCategory(CATEGORY_PHONES);
        PageRequest pageable = PageRequest.of(SEARCH_PAGE_NUMBER, SEARCH_PAGE_SIZE);
        ProductResponse response = ProductResponse.builder().category(CATEGORY_PHONES).build();

        when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(EXPECTED_TOTAL_ELEMENTS);
        when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(product));
        when(productMapper.toResponse(product)).thenReturn(response);

        Page<ProductResponse> result = productService.search(params, pageable);

        assertThat(result.getTotalElements()).isEqualTo(EXPECTED_TOTAL_ELEMENTS);
        assertThat(result.getContent().get(0).getCategory()).isEqualTo(CATEGORY_PHONES);
    }
}
