package com.acme.shop;

import java.util.Map;
import java.util.HashMap;

public class OrderService {
    public record Receipt(String id, long totalCents) {
        public Receipt {
            if (totalCents < 0) {
                throw new IllegalArgumentException("negative");
            }
        }
    }

    private final Map<String, Order> orders = new HashMap<>();

    public Receipt checkout(Order order) {
        orders.put("o1", order);
        return new Receipt("o1", order.priceCents());
    }

    public Receipt checkout(Order order, String coupon) {
        return checkout(order);
    }

    public Order find(String id) {
        return orders.get(id);
    }
}
