package com.kuronami.questtree.server;

import com.kuronami.questtree.data.SavedDataFiles;
import com.kuronami.questtree.progress.TeamStoreData;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vanilla {@code SavedData.save(File, Provider)} truncates the file before streaming, so a failure leaves a broken
 * save. These checks hold the mod's atomic write, its one-generation backup and the load fallback.
 */
class ProgressStoreAtomicSaveTest {

    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @BeforeAll
    static void gameVersion() {
        SharedConstants.tryDetectVersion();
    }

    @TempDir
    Path dir;

    private static ProgressStore storeWith(String node) {
        ProgressStore store = new ProgressStore();
        TeamStoreData data = new TeamStoreData();
        data.team(TEAM).shared.completed.add(node);
        store.replaceData(data);
        return store;
    }

    private static CompoundTag read(Path file) throws IOException {
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }

    private static ProgressStore load(Path file) throws IOException {
        return ProgressStore.load(read(file).getCompound("data"), null);
    }

    @Test
    void aSaveRoundTripsThroughTheAtomicWrite() throws IOException {
        Path file = dir.resolve("progress.dat");
        ProgressStore store = storeWith("test:book/node_0");

        store.save(file.toFile(), null);

        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(SavedDataFiles.backupPath(file)), "the first save has no previous file to keep");
        assertFalse(Files.exists(dir.resolve("progress.dat.tmp")), "the temporary file is moved, not left behind");
        assertFalse(store.isDirty());

        CompoundTag root = read(file);
        assertTrue(root.contains("DataVersion"), "vanilla writes the DataVersion next to the data");
        assertEquals(Set.of("test:book/node_0"), load(file).team(TEAM).shared.completed);
    }

    @Test
    void thePreviousSaveIsKeptAsOneGenerationOfBackup() throws IOException {
        Path file = dir.resolve("progress.dat");
        storeWith("test:book/first").save(file.toFile(), null);
        storeWith("test:book/second").save(file.toFile(), null);
        storeWith("test:book/third").save(file.toFile(), null);

        Path backup = SavedDataFiles.backupPath(file);
        assertTrue(Files.isRegularFile(backup));
        assertEquals(Set.of("test:book/third"), load(file).team(TEAM).shared.completed);
        assertEquals(Set.of("test:book/second"), load(backup).team(TEAM).shared.completed,
                "the backup is the save before the last one, not the first one");
    }

    @Test
    void aFailedWriteKeepsThePreviousFileAndTheDirtyFlag() throws IOException {
        Path file = dir.resolve("progress.dat");
        storeWith("test:book/first").save(file.toFile(), null);
        Files.createDirectory(dir.resolve("progress.dat.tmp"));

        ProgressStore second = storeWith("test:book/second");
        second.save(file.toFile(), null);

        assertTrue(second.isDirty(), "a failed save must be retried");
        assertEquals(Set.of("test:book/first"), load(file).team(TEAM).shared.completed,
                "the old file is untouched");
    }

    @Test
    void aCorruptMainFileFallsBackToTheBackup() throws IOException {
        Path file = dir.resolve("progress.dat");
        storeWith("test:book/first").save(file.toFile(), null);
        storeWith("test:book/second").save(file.toFile(), null);
        Files.write(file, new byte[] {1, 2, 3, 4});

        assertTrue(SavedDataFiles.recover(file));

        assertEquals(Set.of("test:book/first"), load(file).team(TEAM).shared.completed,
                "the backup of the previous save is restored");
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("progress.dat.corrupt-")),
                    "the broken file is kept for inspection");
        }
    }

    @Test
    void aCorruptFileWithoutABackupIsLeftAlone() throws IOException {
        Path file = dir.resolve("progress.dat");
        Files.write(file, new byte[] {1, 2, 3, 4});

        assertFalse(SavedDataFiles.recover(file));
        assertEquals(4L, Files.size(file));
    }

    @Test
    void aBrokenBackupDoesNotReplaceTheMainFile() throws IOException {
        Path file = dir.resolve("progress.dat");
        Files.write(file, new byte[] {1, 2, 3, 4});
        Files.write(SavedDataFiles.backupPath(file), new byte[] {5, 6, 7, 8});

        assertFalse(SavedDataFiles.recover(file));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(file));
    }
}
