package app.dispofold.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import app.dispofold.api.TestData;

/** The folded view of whole-area restrictions as area states, and their rows again. */
class AreaStatesTest {

    static Restriction r(String person, String shift, int weekday, RestrictionKind kind) {
        return new Restriction(person, shift, null, weekday, kind, null, null);
    }

    /** TestData: AN has AN-T; DR has DR-T and DR-N. P0002 has AN and DR. */
    static Dataset with(List<Restriction> restrictions) {
        Dataset d = TestData.dataset(YearMonth.of(2033, 3));
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(), d.holidays(),
                d.absenceTypes(), d.people(), restrictions, d.history(), d.periods());
    }

    static Person person(Dataset d, String id) {
        return d.people().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void wholeAreaSetsBecomeStatesTheRestStaysAnException() {
        Restriction dated = new Restriction("P0002", "AN-T", null, 3, RestrictionKind.NEVER, LocalDate.of(2033, 3, 1),
                null);
        Restriction night = new Restriction("P0002", null, ShiftCategory.NIGHT, 0, RestrictionKind.NEVER, null, null);
        Dataset d = with(List.of(r("P0002", "DR-T", 0, RestrictionKind.ON_REQUEST),
                r("P0002", "DR-N", 0, RestrictionKind.ON_REQUEST), r("P0002", "DR-N", 5, RestrictionKind.NEVER),
                r("P0002", "AN-T", 0, RestrictionKind.NEVER), dated, night,
                // half an area: stays rows
                r("P0004", "DR-T", 0, RestrictionKind.NEVER),
                // an area with both kinds on every day: "nie", the other row stays an exception
                r("P0006", "AN-T", 0, RestrictionKind.NEVER), r("P0006", "AN-T", 0, RestrictionKind.ON_REQUEST)));
        Dataset folded = AreaStates.fold(d);
        assertThat(person(folded, "P0002").areaStates()).containsExactly(
                Map.entry("AN", RestrictionKind.NEVER), Map.entry("DR", RestrictionKind.ON_REQUEST));
        // the exceptions: a weekday, a date, a category
        assertThat(folded.restrictions()).containsExactlyInAnyOrder(r("P0002", "DR-N", 5, RestrictionKind.NEVER),
                dated, night, r("P0004", "DR-T", 0, RestrictionKind.NEVER),
                r("P0006", "AN-T", 0, RestrictionKind.ON_REQUEST));
        assertThat(person(folded, "P0004").areaStates()).isNull();
        assertThat(person(folded, "P0006").areaStates()).containsExactly(Map.entry("AN", RestrictionKind.NEVER));
        // the same meaning back: the rows of before (as a set), and folding twice changes nothing
        Dataset back = AreaStates.expand(folded);
        assertThat(new HashSet<>(back.restrictions())).isEqualTo(new HashSet<>(d.restrictions()));
        assertThat(back.people()).allSatisfy(p -> assertThat(p.areaStates()).isNull());
        assertThat(AreaStates.fold(folded)).isEqualTo(folded);
        // nothing to fold: the same object
        Dataset plain = with(List.of(r("P0004", "DR-T", 0, RestrictionKind.NEVER)));
        assertThat(AreaStates.fold(plain)).isSameAs(plain);
    }

    @Test
    void aWholeDayExceptionOfTheOtherKindDoesNotHideTheState() {
        // every shift of DR "nur von Hand", DR-T also "nie": the state stays, the stricter row an exception
        Dataset d = with(List.of(r("P0002", "DR-T", 0, RestrictionKind.ON_REQUEST),
                r("P0002", "DR-N", 0, RestrictionKind.ON_REQUEST), r("P0002", "DR-T", 0, RestrictionKind.NEVER)));
        Dataset folded = AreaStates.fold(d);
        assertThat(person(folded, "P0002").areaStates()).containsExactly(Map.entry("DR", RestrictionKind.ON_REQUEST));
        assertThat(folded.restrictions()).containsExactly(r("P0002", "DR-T", 0, RestrictionKind.NEVER));
        // "nie" on every shift wins over "nur von Hand" on every shift
        Dataset both = with(List.of(r("P0002", "DR-T", 0, RestrictionKind.ON_REQUEST), r("P0002", "DR-N", 0,
                RestrictionKind.ON_REQUEST), r("P0002", "DR-T", 0, RestrictionKind.NEVER), r("P0002", "DR-N", 0,
                        RestrictionKind.NEVER)));
        assertThat(person(AreaStates.fold(both), "P0002").areaStates()).containsExactly(
                Map.entry("DR", RestrictionKind.NEVER));
        assertThat(new HashSet<>(AreaStates.expand(AreaStates.fold(both)).restrictions()))
                .isEqualTo(new HashSet<>(both.restrictions()));
    }

    @Test
    void aStateOfAnAreaThePersonDoesNotHaveIsNoState() {
        // P0001 has only AN: rows for DR are exceptions, a state for DR is dropped
        Dataset d = with(List.of(r("P0001", "DR-T", 0, RestrictionKind.NEVER), r("P0001", "DR-N", 0,
                RestrictionKind.NEVER)));
        assertThat(person(AreaStates.fold(d), "P0001").areaStates()).isNull();
        Person p = person(d, "P0001").withAreaStates(Map.of("DR", RestrictionKind.NEVER));
        Dataset stated = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), d.people().stream().map(x -> x.id().equals("P0001") ? p : x).toList(),
                List.of(), d.history(), d.periods());
        assertThat(AreaStates.expanded(stated)).isEmpty();
    }
}
