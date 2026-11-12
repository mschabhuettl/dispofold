package app.dispofold.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import app.dispofold.importclient.DatasetValidator;
import app.dispofold.model.Dataset;
import app.dispofold.model.Period;
import app.dispofold.model.Rules;
import app.dispofold.model.ShiftType;
import app.dispofold.model.Wish;
import app.dispofold.model.WishKind;

class InputValidationTest {

    @Test
    void incompleteEntriesAreRejected() {
        Dataset d = TestData.dataset(YearMonth.of(2031, 3));
        List<ShiftType> shifts = new ArrayList<>(d.shifts());
        shifts.add(new ShiftType("BROKEN", "AN", "X", "ohne Ende", LocalTime.of(8, 0), null, null, null, false));
        Period p = d.periods().get(0);
        List<Wish> wishes = new ArrayList<>(p.wishes());
        wishes.add(new Wish("P0001", null, WishKind.OFF, null));
        Dataset broken = new Dataset(d.format(), d.version(), d.source(), d.areas(), shifts, d.demand(),
                d.holidays(), d.absenceTypes(), d.people(), d.restrictions(), d.history(),
                List.of(new Period(p.month(), p.absences(), wishes, p.fixed(), p.previousDuties())));

        DatasetValidator.Result result = DatasetValidator.validate(broken, Rules.defaults());

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(DatasetValidator.validate(d, Rules.defaults()).ok()).isTrue();
    }

    @Test
    void unusableRulesAreRejected() {
        Rules ok = Rules.defaults();
        assertThat(RulesResource.problem(ok)).isNull();
        // Planning never goes below 11 h daily rest.
        assertThat(RulesResource.problem(new Rules(9, 36, 12, 60, 46.16, 2, 4, 6, true, ok.nightStart(),
                ok.nightEnd(), 2, 0.25, 3, Map.of(), Rules.DEFAULT_TIME_ZONE, 52))).isEqualTo("rules.minDailyRest");
        assertThat(RulesResource.problem(new Rules(11, 36, 12, 60, 46.16, 2, 4, 6, true, ok.nightStart(),
                ok.nightEnd(), 2, 0.25, 3, Map.of(), "Mars/Base", 52))).isEqualTo("rules.timeZone");
        assertThat(RulesResource.problem(new Rules(11, 36, 12, 60, 46.16, 2, 4, 6, true, null, LocalTime.of(6, 0),
                2, 0.25, 3, Map.of(), Rules.DEFAULT_TIME_ZONE, 52))).isNotNull();
        assertThat(RulesResource.problem(new Rules(11, 36, 12, 60, 46.16, 2, 4, 6, true, ok.nightStart(),
                ok.nightEnd(), 2, 0.25, 3, Map.of("wishes", -1L), Rules.DEFAULT_TIME_ZONE, 52))).isNotNull();
        assertThat(RulesResource.problem(new Rules(11, 36, 0, 60, 46.16, 2, 4, 6, true, ok.nightStart(),
                ok.nightEnd(), 2, 0.25, 3, Map.of(), Rules.DEFAULT_TIME_ZONE, 52))).isNotNull();
    }
}
