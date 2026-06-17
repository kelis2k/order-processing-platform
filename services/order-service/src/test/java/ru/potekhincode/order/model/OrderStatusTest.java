package ru.potekhincode.order.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    void shouldAllowNewToReservedAndCancelled() {
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.RESERVED)).isTrue();
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
    }

    @Test
    void shouldForbidSkippingStatesFromNew() {
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.COMPLETED)).isFalse();
    }

    @Test
    void shouldFollowHappyPath() {
        assertThat(OrderStatus.RESERVED.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.SHIPPED)).isTrue();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.COMPLETED)).isTrue();
    }

    @Test
    void shouldAllowCancellationBeforeShipping() {
        assertThat(OrderStatus.RESERVED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
    }

    @Test
    void shouldForbidCancellationAfterShipping() {
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
    }

    @Test
    void shouldTreatCompletedAndCancelledAsTerminal() {
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(OrderStatus.COMPLETED.canTransitionTo(target)).isFalse();
            assertThat(OrderStatus.CANCELLED.canTransitionTo(target)).isFalse();
        }
    }
}
