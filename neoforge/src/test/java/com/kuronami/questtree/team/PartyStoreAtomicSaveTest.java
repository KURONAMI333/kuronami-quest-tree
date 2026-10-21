package com.kuronami.questtree.team;

import com.kuronami.questtree.data.SavedDataFiles;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The party file goes through the same atomic write and one-generation backup as the progress file. */
class PartyStoreAtomicSaveTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PARTY = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @BeforeAll
    static void gameVersion() {
        SharedConstants.tryDetectVersion();
    }

    @TempDir
    Path dir;

    private static PartyStore storeFor(UUID party) {
        PartyStore store = new PartyStore();
        store.data().create(OWNER, party, 1_000L);
        store.setDirty();
        return store;
    }

    private static PartyStore load(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        return PartyStore.load(root.getCompound("data"), null);
    }

    @Test
    void aPartySaveRoundTripsAndKeepsThePreviousFileAsBackup() throws IOException {
        Path file = dir.resolve("party.dat");
        storeFor(PARTY).save(file.toFile(), null);
        assertEquals(PARTY, load(file).data().teamIdOf(OWNER));

        UUID next = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
        storeFor(next).save(file.toFile(), null);

        assertTrue(Files.isRegularFile(SavedDataFiles.backupPath(file)));
        assertEquals(next, load(file).data().teamIdOf(OWNER));
        assertEquals(PARTY, load(SavedDataFiles.backupPath(file)).data().teamIdOf(OWNER));
    }
}
