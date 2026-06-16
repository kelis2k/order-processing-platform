package ru.potekhincode.product.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.potekhincode.product.dto.request.CreateProductRequest;
import ru.potekhincode.product.dto.response.ProductResponse;
import ru.potekhincode.product.repository.ProductRepository;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ProductControllerIT {

    private static final String MONGO_IMAGE = "mongo:7.0";
    private static final String PRODUCTS_URL = "/products";
    private static final String UNKNOWN_ID = "000000000000000000000000";

    private static final String IPHONE = "iPhone 15";
    private static final String MACBOOK = "MacBook";
    private static final String IPAD = "iPad";
    private static final String AIRPODS = "AirPods";

    private static final String PHONES = "phones";
    private static final String LAPTOPS = "laptops";
    private static final String TABLETS = "tablets";
    private static final String AUDIO = "audio";

    private static final double IPHONE_PRICE = 999.99;
    private static final double MACBOOK_PRICE = 1999.99;
    private static final double IPAD_PRICE = 799.99;
    private static final double AIRPODS_PRICE = 249.99;

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(MONGO_IMAGE);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> mongo.getConnectionString() + "/product_db");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.data.redis.host", () -> "localhost");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void cleanUp() {
        productRepository.deleteAll();
    }

    private CreateProductRequest buildRequest(String name, String category, double price) {
        CreateProductRequest request = new CreateProductRequest();
        request.setName(name);
        request.setCategory(category);
        request.setPrice(BigDecimal.valueOf(price));
        request.setPublished(true);
        return request;
    }

    private String createProductAndGetId(String name, String category, double price) {
        ResponseEntity<ProductResponse> resp = restTemplate.postForEntity(
                PRODUCTS_URL, buildRequest(name, category, price), ProductResponse.class);
        return Objects.requireNonNull(resp.getBody()).getId();
    }

    @Test
    void shouldReturn201WhenCreatingWithValidRequest() {
        ResponseEntity<ProductResponse> response = restTemplate.postForEntity(
                PRODUCTS_URL, buildRequest(IPHONE, PHONES, IPHONE_PRICE), ProductResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isNotBlank();
        assertThat(response.getBody().getName()).isEqualTo(IPHONE);
    }

    @Test
    void shouldReturn400WhenCreatingWithInvalidRequest() {
        CreateProductRequest invalid = new CreateProductRequest();

        ResponseEntity<Map> response = restTemplate.postForEntity(PRODUCTS_URL, invalid, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReturn200WhenFindingExistingProduct() {
        String id = createProductAndGetId(MACBOOK, LAPTOPS, MACBOOK_PRICE);

        ResponseEntity<ProductResponse> response = restTemplate.getForEntity(
                PRODUCTS_URL + "/" + id, ProductResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getName()).isEqualTo(MACBOOK);
    }

    @Test
    void shouldReturn404WhenFindingByUnknownId() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                PRODUCTS_URL + "/" + UNKNOWN_ID, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldReturn204WhenDeletingExistingProduct() {
        String id = createProductAndGetId(IPAD, TABLETS, IPAD_PRICE);

        ResponseEntity<Void> response = restTemplate.exchange(
                PRODUCTS_URL + "/" + id, HttpMethod.DELETE, null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(productRepository.findById(id)).isEmpty();
    }

    @Test
    void shouldTogglePublishedFlagForUnpublishedProduct() {
        CreateProductRequest request = buildRequest(AIRPODS, AUDIO, AIRPODS_PRICE);
        request.setPublished(false);
        ResponseEntity<ProductResponse> created = restTemplate.postForEntity(
                PRODUCTS_URL, request, ProductResponse.class);
        String id = Objects.requireNonNull(created.getBody()).getId();

        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                PRODUCTS_URL + "/" + id + "/publish?published=true",
                HttpMethod.PATCH, null, ProductResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().isPublished()).isTrue();
    }
}
