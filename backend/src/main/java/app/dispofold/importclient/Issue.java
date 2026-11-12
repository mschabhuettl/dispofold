package app.dispofold.importclient;

import java.util.Map;

/**
 * One finding of the dataset validator. The user interface renders it from {@code code} (see the list in
 * docs/datenformat.md).
 *
 * @param code dotted text key, e.g. {@code import.unknownPerson}
 * @param params values for the text: ids, numbers, ISO dates and months; never names
 */
public record Issue(String code, Map<String, Object> params) {
}
