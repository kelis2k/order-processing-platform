package ru.potekhincode.notification.model;

/** Статусы заказа приезжают строками из чужого домена — держим константами, а не enum'ом. */
public final class OrderStatuses {

    public static final String NEW = "NEW";

    private OrderStatuses() {
    }
}
