package com.kuronami.questtree.server;

import com.kuronami.questtree.data.SavedDataFiles;
import com.kuronami.questtree.progress.TeamStoreData;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.saveddata.SavedData;
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

    private static boolean hasCorruptFile(Path dataFile) throws IOException {
        String prefix = dataFile.getFileName() + ".corrupt-";
        try (Stream<Path> files = Files.list(dataFile.getParent())) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith(prefix));
        }
    }

    private static void writeRoot(Path file, String marker) throws IOException {
        CompoundTag data = new CompoundTag();
        if ("bad".equals(marker)) {
            data.putInt("bad", 1);
        } else {
            data.putString("marker", marker);
        }
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtIo.writeCompressed(root, file);
    }

    /** A store whose load fails on a record that parses as NBT but not as this store. */
    private static final class Dummy extends SavedData {
        private final String marker;

        private Dummy(String marker) {
            this.marker = marker;
        }

        private static Dummy load(CompoundTag tag, HolderLookup.Provider registries) {
            if (tag.contains("bad")) {
                throw new IllegalStateException("bad record");
            }
            return new Dummy(tag.getString("marker"));
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            return tag;
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
        assertTrue(hasCorruptFile(file), "the unreadable file is kept for inspection");
        assertEquals(Set.of("test:book/first"), read(file).team(TEAM).shared.completed,
                "the backup was restored as the main file");
    }

    @Test
    void aCorruptMainFileWithoutABackupIsMovedAside() throws IOException {
        Path file = dir.resolve("progress.dat");
        Files.write(file, new byte[] {1, 2, 3, 4});

        assertNull(read(file), "a caller starts with an empty store when nothing reads");
        assertFalse(Files.exists(file), "the broken file is not left where the next save would append it");
        assertTrue(hasCorruptFile(file));
    }

    @Test
    void aBrokenBackupDoesNotReplaceTheBrokenMainFile() throws IOException {
        Path file = dir.resolve("progress.dat");
        Files.write(file, new byte[] {1, 2, 3, 4});
        Files.write(SavedDataFiles.backupPath(file), new byte[] {5, 6, 7, 8});

        assertNull(read(file));
        assertFalse(Files.exists(file));
        assertTrue(hasCorruptFile(file));
        assertTrue(Files.isRegularFile(SavedDataFiles.backupPath(file)), "the backup is left for inspection too");
    }

    @Test
    void aGzipFileThatFailsToLoadFallsBackToTheBackup() throws IOException {
        Path file = dir.resolve("store.dat");
        writeRoot(file, "bad");
        writeRoot(SavedDataFiles.backupPath(file), "backup");

        Dummy recovered = SavedDataFiles.read(file, Dummy::load, null);

        assertEquals("backup", recovered.marker, "a readable gzip whose record does not load still uses the backup");
        assertTrue(hasCorruptFile(file), "the unloadable file is kept");
        assertEquals("backup", SavedDataFiles.read(file, Dummy::load, null).marker,
                "the backup was restored as the main file");
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
