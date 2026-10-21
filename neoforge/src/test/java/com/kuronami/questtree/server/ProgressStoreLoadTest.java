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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mod reads its own save file: vanilla's {@code DimensionDataStorage} needs a data-fix type and calls it without
 * a null check, so on Fabric a null type throws and the store loads empty. These checks go through the read the
 * loaders use instead, without a server or a {@code DataFixTypes}.
 */
class ProgressStoreLoadTest {

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

    private static ProgressStore read(Path file) {
        return SavedDataFiles.read(file, ProgressStore::load, null);
    }

    private static boolean hasCorruptFile(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith("progress.dat.corrupt-"));
        }
    }

    @Test
    void aSaveIsReadWithoutVanillasDataFixerPath() throws IOException {
        Path file = dir.resolve("progress.dat");
        storeWith("test:book/node_0").save(file.toFile(), null);

        assertEquals(Set.of("test:book/node_0"), read(file).team(TEAM).shared.completed);
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        assertTrue(root.contains("DataVersion"), "the file is the same shape vanilla writes");
        assertTrue(root.contains("data"));
    }

    @Test
    void aCorruptMainFileFallsBackToTheBackupAndIsKept() throws IOException {
        Path file = dir.resolve("progress.dat");
        storeWith("test:book/first").save(file.toFile(), null);
        Files.copy(file, SavedDataFiles.backupPath(file));
        storeWith("test:book/second").save(file.toFile(), null);
        Files.write(file, new byte[] {1, 2, 3, 4});

        ProgressStore recovered = read(file);

        assertEquals(Set.of("test:book/first"), recovered.team(TEAM).shared.completed,
                "the backup of the previous save is read");
        assertTrue(hasCorruptFile(dir), "the unreadable file is kept for inspection");
        assertEquals(Set.of("test:book/first"), read(file).team(TEAM).shared.completed,
                "the backup was restored as the main file");
    }

    @Test
    void aCorruptMainFileWithoutABackupIsMovedAside() throws IOException {
        Path file = dir.resolve("progress.dat");
        Files.write(file, new byte[] {1, 2, 3, 4});

        assertNull(read(file), "a caller starts with an empty store when nothing reads");
        assertFalse(Files.exists(file), "the broken file is not left where the next save would append it");
        assertTrue(hasCorruptFile(dir));
    }

    @Test
    void aMissingFileDoesNotResurrectTheBackup() throws IOException {
        Path file = dir.resolve("progress.dat");
        storeWith("test:book/first").save(file.toFile(), null);
        Files.copy(file, SavedDataFiles.backupPath(file));
        Files.delete(file);

        assertNull(read(file));
        assertFalse(Files.exists(file), "the backup only replaces a main file that exists but does not read");
    }
}
