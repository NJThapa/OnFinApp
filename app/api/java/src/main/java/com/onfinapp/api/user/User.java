package com.onfinapp.api.user;

import java.util.UUID;

public record User(UUID id, String email, String name) {
}
