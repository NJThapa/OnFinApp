package com.onfinapp.api.price;

import java.time.Instant;

public record PricePoint(Instant time, double close) {
}
