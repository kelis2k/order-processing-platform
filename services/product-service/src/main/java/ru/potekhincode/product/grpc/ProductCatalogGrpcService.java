package ru.potekhincode.product.grpc;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.potekhincode.product.model.Product;
import ru.potekhincode.product.repository.ProductRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class ProductCatalogGrpcService extends ProductCatalogServiceGrpc.ProductCatalogServiceImplBase {

    private final ProductRepository productRepository;

    @Override
    public void getPrices(GetPricesRequest request, StreamObserver<GetPricesResponse> responseObserver) {
        List<String> requestedIds = request.getProductIdsList();

        Map<String, Product> found = productRepository.findAllById(requestedIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        GetPricesResponse.Builder response = GetPricesResponse.newBuilder();
        for (String productId : requestedIds) {
            Product product = found.get(productId);
            response.addPrices(ProductPrice.newBuilder()
                    .setProductId(productId)
                    .setPrice(product == null ? "" : product.getPrice().toPlainString())
                    .setFound(product != null)
                    .build());
        }

        log.debug("GetPrices: запрошено {}, найдено {}", requestedIds.size(), found.size());
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }
}
