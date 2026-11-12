package com.onfinapp.api.price;

import java.time.Instant;

public record Quote(double price, double change, double changePercent, double previousClose, Instant asOf) {
}
