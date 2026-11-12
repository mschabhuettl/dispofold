package app.dispofold.model;

import java.time.LocalDate;

public record Wish(String personId, LocalDate date, WishKind kind, String shiftId) {
}
