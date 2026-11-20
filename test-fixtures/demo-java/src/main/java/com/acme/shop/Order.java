package com.acme.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.*;
import static java.util.Objects.requireNonNull;

/** Demo order with nested types and overloaded methods. */
public class Order implements Priced {
    public enum Status { OPEN, PAID, SHIPPED }

    public static class Line {
        private final String sku;
        private final int quantity;

        public Line(String sku, int quantity) {
            this.sku = requireNonNull(sku);
            this.quantity = quantity;
        }

        public String sku() {
            return sku;
        }

        public class Detail {
            public String describe() {
                return sku + " x" + quantity;
            }
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private Status status = Status.OPEN;

    public Order() {
    }

    public void addLine(String sku) {
        addLine(sku, 1);
    }

    public void addLine(String sku, int quantity) {
        lines.add(new Line(sku, quantity));
    }

    public void addLine(Line line) {
        lines.add(line);
    }

    public void addAll(String... skus) {
        for (String sku : skus) {
            addLine(sku);
        }
    }

    public Status status() {
        return status;
    }

    @Override
    public long priceCents() {
        return lines.size() * 100L;
    }
}
