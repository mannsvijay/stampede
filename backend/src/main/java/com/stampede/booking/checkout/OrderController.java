package com.stampede.booking.checkout;

import com.stampede.shared.error.ApiException;
import com.stampede.shared.error.ErrorCode;
import com.stampede.shared.error.ErrorResponses;
import com.stampede.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /orders and GET /orders/{id} (PRD 10, D-017). Both need an access token: they aren't in
 * SecurityConfig's public list, so deny-by-default answers 401 without one.
 * <p>
 * PRD 10 requires an {@code Idempotency-Key} on POST /orders. That arrives with M3.1 (D-017, 4A);
 * until then a retry is recognised by its hold, which has at most one order.
 */
@Tag(name = "orders")
@RestController
class OrderController {

    record PlaceOrderRequest(@NotNull UUID holdId) {
    }

    record OrderItemResponse(UUID seatId, long pricePaise) {
    }

    record OrderResponse(UUID orderId, UUID eventId, UUID holdId, OrderStatus status, long totalPaise,
            List<OrderItemResponse> items, Instant createdAt) {
    }

    private final CheckoutService checkout;

    OrderController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    /**
     * 201 with a Location header when this request created the order (it is PAID); 200 with the
     * existing order when the hold already has one (a retry or a concurrent request), whatever its
     * status is now.
     */
    @PostMapping("/orders")
    @ApiResponse(responseCode = "201", description = "Ordered and paid; Location is /orders/{orderId}",
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ApiResponse(responseCode = "200", description = "This hold already has an order; that order is returned",
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ErrorResponses({ErrorCode.VALIDATION_FAILED, ErrorCode.UNAUTHENTICATED, ErrorCode.HOLD_NOT_FOUND,
            ErrorCode.HOLD_EXPIRED, ErrorCode.PAYMENT_FAILED, ErrorCode.SEAT_TAKEN})
    ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request,
                                        @AuthenticationPrincipal CurrentUser user) {
        return switch (checkout.placeOrder(user.userId(), request.holdId())) {
            case PlaceOrderResult.Placed placed -> ResponseEntity
                    .created(URI.create("/orders/" + placed.order().orderId()))
                    .body(response(placed.order()));
            case PlaceOrderResult.Existing existing -> ResponseEntity.ok(response(existing.order()));
            case PlaceOrderResult.HoldNotFound notFound ->
                    throw new ApiException(ErrorCode.HOLD_NOT_FOUND, "No hold with id " + request.holdId() + ".");
            case PlaceOrderResult.HoldExpired expired -> throw new ApiException(ErrorCode.HOLD_EXPIRED,
                    "This hold has expired or was released, so nothing was sold. Any payment taken for it "
                            + "has been refunded. Pick your seats again.");
            case PlaceOrderResult.PaymentDeclined declined -> throw new ApiException(ErrorCode.PAYMENT_FAILED,
                    "The payment was declined. Your seats have been released.");
            case PlaceOrderResult.SeatTaken taken -> throw new ApiException(ErrorCode.SEAT_TAKEN,
                    "Some of these seats are still part of another order that hasn't finished. Try again shortly.",
                    Map.of("seatIds", taken.seatIds()));
        };
    }

    /** The caller's own order. Someone else's order is 404, exactly like an unknown id. */
    @GetMapping("/orders/{id}")
    @ApiResponse(responseCode = "200", description = "The order",
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ErrorResponses({ErrorCode.VALIDATION_FAILED, ErrorCode.UNAUTHENTICATED, ErrorCode.ORDER_NOT_FOUND})
    ResponseEntity<OrderResponse> getOrder(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) {
        return checkout.order(user.userId(), id)
                .map(order -> ResponseEntity.ok(response(order)))
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "No order with id " + id + "."));
    }

    private static OrderResponse response(OrderView order) {
        return new OrderResponse(order.orderId(), order.eventId(), order.holdId(), order.status(),
                order.totalPaise(), order.items().stream()
                        .map(item -> new OrderItemResponse(item.seatId(), item.pricePaise()))
                        .toList(),
                order.createdAt());
    }
}
