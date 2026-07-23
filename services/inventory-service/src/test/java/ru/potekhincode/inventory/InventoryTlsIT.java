package ru.potekhincode.inventory;

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
import ru.potekhincode.inventory.grpc.CheckAvailabilityRequest;
import ru.potekhincode.inventory.grpc.CheckAvailabilityResponse;
import ru.potekhincode.inventory.grpc.InventoryServiceGrpc;

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
 * TLS 1.3 + mTLS на РЕАЛЬНОМ сетевом порту.
 * <p>
 * Остальные gRPC-тесты используют in-process сервер, где TLS-настройки не применяются вовсе,
 * поэтому поломку конфигурации безопасности они не заметят. Здесь netty поднимается на
 * случайном порту с тем же PKI, что и в dev ({@code .secrets/dev/tls}, см. make tls-certs).
 */
@SpringBootTest
class InventoryTlsIT {

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

    @BeforeAll
    static void requireCertificates() {
        assertThat(TLS.resolve("ca.crt"))
                .as("PKI не найден в %s — сгенерируй: make tls-certs", TLS.toAbsolutePath())
                .exists();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractIntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", AbstractIntegrationTest.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.properties.schema.registry.url", () -> AbstractIntegrationTest.SCHEMA_REGISTRY_URL);
        registry.add("app.kafka.topic.replicas", () -> "1");

        registry.add("grpc.server.port", () -> "0");
        registry.add("grpc.server.security.enabled", () -> "true");
        registry.add("grpc.server.security.certificate-chain", () -> "file:" + TLS.resolve("inventory-server.crt"));
        registry.add("grpc.server.security.private-key", () -> "file:" + TLS.resolve("inventory-server.key"));
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
            CheckAvailabilityResponse response = InventoryServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .checkAvailability(CheckAvailabilityRequest.newBuilder()
                            .setProductId("prod-tls-it-unknown")
                            .setQuantity(1)
                            .build());

            // Товара нет — важен сам факт ответа: значит рукопожатие с mTLS прошло.
            assertThat(response.getAvailable()).isFalse();
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
            assertThatThrownBy(() -> InventoryServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .checkAvailability(CheckAvailabilityRequest.newBuilder()
                            .setProductId("prod-tls-it-unknown")
                            .setQuantity(1)
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

    /**
     * Сырое TLS-соединение с фиксированной версией протокола. Доверяем любому сертификату
     * намеренно: здесь проверяется только согласование версии, доверие проверяют тесты выше.
     */
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

    /** Порт назначается при старте (запрошен 0 = любой свободный) и приходит в событии. */
    @TestConfiguration
    static class GrpcPortListener {
        @Bean
        ApplicationListener<GrpcServerStartedEvent> capturePort() {
            return event -> PORT.set(event.getPort());
        }
    }
}
