package com.metallum.e2e;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorldSnapshotTest {
    @TempDir Path temporary;

    private Path capture() throws Exception {
        Path world = temporary.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "initial-level");
        Files.writeString(world.resolve("region/r.0.0.mca"), "initial-chunks");
        Files.writeString(world.resolve("session.lock"), "not-content");
        Path snapshot = temporary.resolve("initial-world");
        WorldSnapshot.capture(world, snapshot, new JsonObject());
        return snapshot;
    }

    @Test void savedContentCopiesWithoutSessionLockAndHasVerifiableIdentity() throws Exception {
        Path snapshot = capture();
        String identity = WorldSnapshot.verify(snapshot);
        assertEquals(64, identity.length());
        assertFalse(Files.exists(snapshot.resolve("session.lock")));
        Path replay = temporary.resolve("replay");
        WorldSnapshot.copyVerified(snapshot, replay);
        assertEquals("initial-chunks", Files.readString(replay.resolve("region/r.0.0.mca")));
        assertEquals(identity, WorldSnapshot.verify(snapshot));
    }

    @Test void tamperedOrAdditionalContentFailsClosedBeforeReplay() throws Exception {
        Path snapshot = capture();
        Files.writeString(snapshot.resolve("level.dat"), "changed");
        assertThrows(IllegalStateException.class, () -> WorldSnapshot.copyVerified(snapshot, temporary.resolve("replay")));
        assertFalse(Files.exists(temporary.resolve("replay")));
        Files.writeString(snapshot.resolve("level.dat"), "initial-level");
        Files.writeString(snapshot.resolve("unlisted.dat"), "extra");
        assertThrows(IllegalStateException.class, () -> WorldSnapshot.verify(snapshot));
    }

    @Test void manifestCannotEscapeSnapshotDirectory() throws Exception {
        Path snapshot = capture();
        Path manifest = temporary.resolve("initial-world-manifest.json");
        Files.writeString(manifest, Files.readString(manifest).replace("level.dat", "../world/level.dat"));
        assertThrows(IllegalStateException.class, () -> WorldSnapshot.verify(snapshot));
    }
    @Test void parentSymlinksAndExistingOutputFailBeforeCopy() throws Exception {
        Path snapshot = capture();
        Path outside = temporary.resolve("outside");
        Files.move(snapshot.resolve("region"), outside);
        Files.createSymbolicLink(snapshot.resolve("region"), outside);
        assertThrows(IllegalStateException.class, () -> WorldSnapshot.verify(snapshot));
        assertThrows(IllegalStateException.class, () -> WorldSnapshot.copyVerified(snapshot, temporary.resolve("replay")));
        assertFalse(Files.exists(temporary.resolve("replay")));
        assertThrows(IllegalStateException.class, () -> WorldSnapshot.capture(temporary.resolve("world"), snapshot, new JsonObject()));
    }
}
