package ru.potekhincode.product.grpc;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TLS 1.3 + mTLS каталога цен на РЕАЛЬНОМ сетевом порту.
 * <p>
 * Остальные gRPC-тесты работают с сервисом напрямую или через in-process сервер, где TLS-настройки
 * не применяются вовсе — поломку конфигурации безопасности они не заметят. Здесь netty поднимается
 * на случайном порту с тем же PKI, что и в dev ({@code .secrets/dev/tls}, см. make tls-certs).
 */
@SpringBootTest
@Testcontainers
class ProductCatalogTlsIT {

    private static final Path TLS = Path.of(System.getProperty("tls.dir", ".secrets/dev/tls"));

    private static final AtomicInteger PORT = new AtomicInteger();

    private static final X509TrustManager TRUST_ANYTHING = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) { }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) { }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @BeforeAll
    static void requireCertificates() {
        assertThat(TLS.resolve("ca.crt"))
                .as("PKI не найден в %s — сгенерируй: make tls-certs", TLS.toAbsolutePath())
                .exists();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> mongo.getConnectionString() + "/product_db");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.data.redis.host", () -> "localhost");

        registry.add("grpc.server.port", () -> "0");
        registry.add("grpc.server.security.enabled", () -> "true");
        registry.add("grpc.server.security.certificate-chain", () -> "file:" + TLS.resolve("product-server.crt"));
        registry.add("grpc.server.security.private-key", () -> "file:" + TLS.resolve("product-server.key"));
        registry.add("grpc.server.security.trust-cert-collection", () -> "file:" + TLS.resolve("ca.crt"));
        registry.add("grpc.server.security.client-auth", () -> "REQUIRE");
        registry.add("grpc.server.security.protocols", () -> "TLSv1.3");
    }

    @Test
    void clientWithCertificateIsServed() throws Exception {
        ChannelCredentials credentials = TlsChannelCredentials.newBuilder()
                .trustManager(TLS.resolve("ca.crt").toFile())
                .keyManager(TLS.resolve("order-client.crt").toFile(),
                        TLS.resolve("order-client.key").toFile())
                .build();

        ManagedChannel channel = Grpc.newChannelBuilder("localhost:" + PORT.get(), credentials).build();
        try {
            GetPricesResponse response = ProductCatalogServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .getPrices(GetPricesRequest.newBuilder()
                            .addProductIds("prod-tls-it-unknown")
                            .build());

            // Товара нет — важен сам факт ответа: значит рукопожатие с mTLS прошло.
            assertThat(response.getPricesCount()).isEqualTo(1);
            assertThat(response.getPrices(0).getFound()).isFalse();
        } finally {
            shutdown(channel);
        }
    }

    @Test
    void clientWithoutCertificateIsRejected() throws Exception {
        ChannelCredentials credentials = TlsChannelCredentials.newBuilder()
                .trustManager(TLS.resolve("ca.crt").toFile())
                .build();

        ManagedChannel channel = Grpc.newChannelBuilder("localhost:" + PORT.get(), credentials).build();
        try {
            // В TLS 1.3 сервер отвергает клиента уже после рукопожатия, поэтому вызов не падает
            // мгновенно, а виснет — ограничиваем ожидание deadline'ом.
            assertThatThrownBy(() -> ProductCatalogServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getPrices(GetPricesRequest.newBuilder()
                            .addProductIds("prod-tls-it-unknown")
                            .build()))
                    .isInstanceOf(StatusRuntimeException.class);
        } finally {
            shutdown(channel);
        }
    }

    @Test
    void serverNegotiatesTls13() throws Exception {
        try (SSLSocket socket = socketWith("TLSv1.3")) {
            socket.startHandshake();
            assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.3");
        }
    }

    @Test
    void serverRejectsTls12() throws Exception {
        try (SSLSocket socket = socketWith("TLSv1.2")) {
            assertThatThrownBy(socket::startHandshake).isInstanceOf(SSLHandshakeException.class);
        }
    }

    private SSLSocket socketWith(String protocol) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{TRUST_ANYTHING}, null);

        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("localhost", PORT.get());
        socket.setEnabledProtocols(new String[]{protocol});
        return socket;
    }

    private static void shutdown(ManagedChannel channel) throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @TestConfiguration
    static class GrpcPortListener {
        @Bean
        ApplicationListener<GrpcServerStartedEvent> capturePort() {
            return event -> PORT.set(event.getPort());
        }
    }
}
