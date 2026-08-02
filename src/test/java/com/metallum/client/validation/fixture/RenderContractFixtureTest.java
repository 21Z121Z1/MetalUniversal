package com.metallum.client.validation.fixture;

import com.metallum.client.validation.reference.CapabilityStatus;
import com.metallum.client.validation.reference.IrisReferencePassRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.metallum.client.validation.storage.ValidationStorageBudget;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RenderContractFixtureTest {
    @Test
    void registryLoadsVersionedCasesAndRejectsUnknownCase() throws Exception {
        RenderContractCaseRegistry registry = RenderContractCaseRegistry.load(
                Path.of("validation/render-contract/cases.json")
        );

        assertEquals(1, registry.schemaVersion());
        assertEquals("synthetic-mrt-basic", registry.requireCase("synthetic-mrt-basic").name());
        assertThrows(IllegalArgumentException.class, () -> registry.requireCase("missing"));
    }

    @Test
    void namedSyntheticCaseRunsOnlyItsDeclaredScenario(@TempDir final Path output) throws Exception {
        RenderContractSyntheticValidation.main(new String[]{output.toString(), "synthetic-mrt-basic"});

        JsonObject summary = JsonParser.parseString(
                Files.readString(output.resolve("synthetic-validation.json"))
        ).getAsJsonObject();
        assertEquals("synthetic-mrt-basic", summary.get("case").getAsString());
        assertEquals("synthetic_mrt_basic", summary.get("scenario").getAsString());
        assertEquals(1, summary.get("metal3Passes").getAsInt());
        assertEquals(1, summary.get("metal4Passes").getAsInt());
    }

    @Test
    void syntheticBackendsShareOneRootArtifactBudget(@TempDir final Path output) {
        String previous = System.getProperty("metallum.renderContract.maxArtifactBytes");
        try {
            System.setProperty("metallum.renderContract.maxArtifactBytes", "1024");
            assertThrows(
                    Exception.class,
                    () -> RenderContractSyntheticValidation.main(
                            new String[]{output.toString(), "synthetic-mrt-basic"}
                    )
            );
            assertTrue(ValidationStorageBudget.shared(output).exceeded());
        } finally {
            if (previous == null) {
                System.clearProperty("metallum.renderContract.maxArtifactBytes");
            } else {
                System.setProperty("metallum.renderContract.maxArtifactBytes", previous);
            }
        }
    }

    @Test
    void irisRegistryIsNameIndependentAndExplicitAboutUnknownPasses() {
        IrisReferencePassRegistry registry = new IrisReferencePassRegistry();
        assertEquals(CapabilityStatus.SUPPORTED,
                registry.register("gbuffers_terrain", 0, "fragment", "iris/gbuffers/terrain"));
        assertEquals("iris/gbuffers/terrain", registry.resolve("gbuffers_terrain", 0, "fragment"));
        assertEquals(CapabilityStatus.UNCLASSIFIED,
                registry.statusFor("composite", 0, "fragment"));
    }
}
