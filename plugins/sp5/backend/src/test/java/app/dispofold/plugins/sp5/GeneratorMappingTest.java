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
        // its groups separate the shifts: the proposals stay those from the groups
        assertThat(Sp5Families.propose(folder, java.util.Map.of(), Sp5ProposalsTest.DEFAULTS, null))
                .containsEntry("basis", "groups");
        List<Sp5ProposalCheck.Detail> details = Sp5ProposalCheck.details(folder, own, Sp5ProposalsTest.DEFAULTS, null);
        List<String> differences = details.stream().filter(d -> !d.outcome().equals("matched"))
                .map(d -> d.section() + " " + d.code() + ": " + d.outcome() + " (" + d.own() + " / " + d.proposed() + ")")
                .toList();
        // the folder cannot know that the service line, the administration and the volunteers are outside the plan:
        // their groups have members, duties and (the service line) demand of their own
        assertThat(differences).containsExactly("areas SE: extra (null / SE)", "areas VE: extra (null / VE)",
                "shifts X1-T: different (ignoriert / Bereich SE)", "shifts X1-N: different (ignoriert / Bereich SE)",
                "shifts X2: different (ignoriert / Bereich SE)", "shifts X3: different (ignoriert / Bereich SE)",
                "shifts X4: different (ignoriert / Bereich VE)",
                "groups Servicetelefon: different (ausgeschlossen / Bereiche SE)",
                "groups Verwaltung: different (ausgeschlossen / Bereiche VE)",
                "groups Ehrenamt: different (ausgeschlossen / Bereiche TV)");
        assertThat(details).filteredOn(d -> d.outcome().equals("matched")).hasSize(30);
    }
}
