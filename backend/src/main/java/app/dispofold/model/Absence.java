package app.dispofold.model;

import java.time.LocalDate;

public record Absence(String personId, LocalDate date, String typeCode) {
}
