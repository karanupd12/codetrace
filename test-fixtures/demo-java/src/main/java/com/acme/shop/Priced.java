package com.acme.shop;

public interface Priced {
    long priceCents();

    default String formatted() {
        return priceCents() / 100 + "." + priceCents() % 100;
    }
}
