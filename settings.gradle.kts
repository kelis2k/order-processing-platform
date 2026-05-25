rootProject.name = "order-processing-platform"

include(
    ":common",
    ":avro-schemas",
    ":proto-contracts",
    ":services:api-gateway",
    ":services:auth-service",
    ":services:user-service",
    ":services:product-service",
    ":services:inventory-service",
    ":services:order-service",
    ":services:notification-service"
)