package ru.potekhincode.order.exception;

import ru.potekhincode.order.client.UnavailableItem;

import java.util.List;
import java.util.stream.Collectors;

public class InsufficientStockException extends RuntimeException {
    private final transient List<UnavailableItem> items;


    public InsufficientStockException(List<UnavailableItem> items) {
        super(buildMessage(items));
        this.items = items;
    }

    public List<UnavailableItem> getItems() {
        return items;
    }

    private static String buildMessage(List<UnavailableItem> items) {
        return items.stream()
                .map(i -> "%s: запрошено %d, доступно %d"
                        .formatted(i.productId(), i.requested(), i.available()))
                        .collect(Collectors.joining("; "));

    }
}
