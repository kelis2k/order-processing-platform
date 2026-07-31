package ru.potekhincode.product.grpc;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.potekhincode.product.model.Product;
import ru.potekhincode.product.repository.ProductRepository;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCatalogGrpcServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private StreamObserver<GetPricesResponse> responseObserver;

    @InjectMocks
    private ProductCatalogGrpcService grpcService;

    @Test
    void returnsPricesForFoundProducts() {
        when(productRepository.findAllById(List.of("p1", "p2")))
                .thenReturn(List.of(product("p1", "149.50"), product("p2", "10.00")));

        grpcService.getPrices(request("p1", "p2"), responseObserver);

        GetPricesResponse response = captureResponse();
        assertThat(response.getPricesList()).hasSize(2);
        assertThat(response.getPrices(0).getProductId()).isEqualTo("p1");
        assertThat(response.getPrices(0).getPrice()).isEqualTo("149.50");
        assertThat(response.getPrices(0).getFound()).isTrue();
        assertThat(response.getPrices(1).getPrice()).isEqualTo("10.00");
    }

    @Test
    void marksMissingProductAsNotFound() {
        when(productRepository.findAllById(List.of("p1", "missing")))
                .thenReturn(List.of(product("p1", "149.50")));

        grpcService.getPrices(request("p1", "missing"), responseObserver);

        GetPricesResponse response = captureResponse();
        assertThat(response.getPricesList()).hasSize(2);
        assertThat(response.getPrices(1).getProductId()).isEqualTo("missing");
        assertThat(response.getPrices(1).getFound()).isFalse();
        assertThat(response.getPrices(1).getPrice()).isEmpty();
    }

    @Test
    void keepsRequestedOrderAndCompletesStream() {
        when(productRepository.findAllById(List.of("p2", "p1")))
                .thenReturn(List.of(product("p1", "1.00"), product("p2", "2.00")));

        grpcService.getPrices(request("p2", "p1"), responseObserver);

        GetPricesResponse response = captureResponse();
        assertThat(response.getPrices(0).getProductId()).isEqualTo("p2");
        assertThat(response.getPrices(1).getProductId()).isEqualTo("p1");
        verify(responseObserver).onCompleted();
    }

    @Test
    void preservesScaleOfPrice() {
        when(productRepository.findAllById(List.of("p1")))
                .thenReturn(List.of(product("p1", "0.10")));

        grpcService.getPrices(request("p1"), responseObserver);

        assertThat(captureResponse().getPrices(0).getPrice()).isEqualTo("0.10");
    }

    private GetPricesResponse captureResponse() {
        ArgumentCaptor<GetPricesResponse> captor = ArgumentCaptor.forClass(GetPricesResponse.class);
        verify(responseObserver).onNext(captor.capture());
        return captor.getValue();
    }

    private GetPricesRequest request(String... ids) {
        return GetPricesRequest.newBuilder().addAllProductIds(List.of(ids)).build();
    }

    private Product product(String id, String price) {
        Product product = new Product();
        product.setId(id);
        product.setPrice(new BigDecimal(price));
        return product;
    }
}
