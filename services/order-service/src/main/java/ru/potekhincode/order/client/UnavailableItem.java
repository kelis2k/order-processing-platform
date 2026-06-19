package ru.potekhincode.order.client;

public record UnavailableItem(String productId, int requested, int available) { }
