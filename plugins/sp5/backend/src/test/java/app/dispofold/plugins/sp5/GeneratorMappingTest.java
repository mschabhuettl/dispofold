package app.dispofold.plugins.sp5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import app.dispofold.plugins.sp5.port.PyJson;

/**
 * The proposals for the folder of the synthetic generator against the generator's own mapping (mapping.example.json
 * of the export program). Needs the checkout at the pinned commit in {@code EXPORT_CHECKOUT} (the plugin-compare job
 * sets it) and its Python ({@code EXPORT_PYTHON}); skipped without. Every difference is listed with its reason in
 * plugins/sp5/docs/zuordnung.md.
 */
class GeneratorMappingTest {

    @ParameterizedTest
    @ValueSource(ints = { 150, 40 })
    void theProposalsGiveTheGeneratorsMappingExceptWhatTheFolderCannotKnow(int people, @TempDir Path dir)
            throws Exception {
        String checkout = System.getenv("EXPORT_CHECKOUT");
        assumeTrue(checkout != null && !checkout.isBlank(), "EXPORT_CHECKOUT: checkout of the export program");
        Path program = Path.of(checkout).toAbsolutePath();
        String python = System.getenv().getOrDefault("EXPORT_PYTHON", "python3");
        Path folder = dir.resolve("folder");
        LoadedCorrectionsCompareTest.run(program, python, "-m", "tools.sp5writer", "--month", "2027-01", "--people",
                String.valueOf(people), "--out", folder.toString());
        Object own = PyJson.load(Files.readAllBytes(program.resolve("mapping.example.json")));
        // the defaults of new settings: shifts without a time range and without demand are proposed to be ignored
        Sp5Proposals.Settings defaults = new Sp5Proposals.Settings(80, 3, 0, List.of("Ersatzruhe"), java.util.Set.of(),
                70, 2, 50, true);
        // its groups separate the shifts: the proposals stay those from the groups
        assertThat(Sp5Families.propose(folder, java.util.Map.of(), defaults, null))
                .containsEntry("basis", "groups");
        List<Sp5ProposalCheck.Detail> details = Sp5ProposalCheck.details(folder, own, defaults, null);
        List<String> differences = details.stream().filter(d -> !d.outcome().equals("matched"))
                .map(d -> d.section() + " " + d.code() + ": " + d.outcome() + " (" + d.own() + " / " + d.proposed() + ")")
                .toList();
        // the folder cannot know that the service line and the volunteers are outside the plan: their groups have
        // members, duties and (the service line) demand of their own; the administration's shift X4 has neither a
        // time range nor demand and is proposed to be ignored, its group then has no area
        List<String> expected = new java.util.ArrayList<>(List.of("areas SE: extra (null / SE)",
                "shifts X1-T: different (ignoriert / Bereich SE)", "shifts X1-N: different (ignoriert / Bereich SE)",
                "shifts X2: different (ignoriert / Bereich SE)", "shifts X3: different (ignoriert / Bereich SE)"));
        if (people == 40) {
            // the generator's codes have no time range; with 40 people R2 and R3 also have no demand and are taken
            // for codes that are no desk duty (the weakness of that rule, see zuordnung.md)
            expected.addAll(List.of("shifts R2: different (Bereich DR / ignoriert)",
                    "shifts R3: different (Bereich DR / ignoriert)"));
        }
        expected.addAll(List.of("groups Servicetelefon: different (ausgeschlossen / Bereiche SE)",
                "groups Verwaltung: missing (ausgeschlossen / null)",
                "groups Ehrenamt: different (ausgeschlossen / Bereiche TV)"));
        assertThat(differences).containsExactlyInAnyOrderElementsOf(expected);
    }
}
