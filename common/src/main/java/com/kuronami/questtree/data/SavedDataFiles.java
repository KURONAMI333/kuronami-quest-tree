package com.kuronami.questtree.data;

import com.kuronami.questtree.Constants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.FastBufferedInputStream;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Writes the mod's own {@link SavedData} files without vanilla's truncate-then-stream risk: the new file is written to
 * a temporary file, the previous file is kept as {@code <name>.dat.bak}, and the temporary file is moved over the
 * target atomically. A failed write keeps both the old file and the dirty flag, so the next save tries again.
 * <p>
 * Loading also stays in this class. Vanilla's {@code DimensionDataStorage} takes the data-fix type from
 * {@code SavedData.Factory} and calls {@code type.update} without a null check, so a factory with a null type works on
 * NeoForge (patched) but throws on Fabric and the store silently loads empty. Both loaders therefore read the file
 * here: the same gzip NBT read, the same {@code SavedData.load}, no data fixer (the format is this mod's own).
 * <p>
 * The layout matches vanilla {@code SavedData.save(File, HolderLookup.Provider)} exactly: {@code {"data": {...},
 * "DataVersion": <current>}}. A main file that no longer reads is moved to {@code <name>.dat.corrupt-<millis>} and the
 * backup is read and restored in its place.
 */
public final class SavedDataFiles {

    /** The instance of a store per data storage, so a second call returns it instead of reading the file again. */
    private static final Map<DimensionDataStorage, Map<String, SavedData>> INSTANCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SavedDataFiles() {
    }

    public static Path path(MinecraftServer server, String name) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat");
    }

    public static Path backupPath(Path dataFile) {
        return dataFile.resolveSibling(dataFile.getFileName() + ".bak");
    }

    /**
     * Returns the store of this name, reading it with {@link #read} on the first call. Never goes through
     * {@code DimensionDataStorage.computeIfAbsent}: that path asks vanilla to read the file, which needs a data-fix
     * type that does not exist for this mod's data and is fatal to the load on vanilla (Fabric). The loaded store is
     * put into the storage's cache so vanilla's own save pass still writes it.
     */
    @SuppressWarnings("unchecked")
    public static <T extends SavedData> T get(MinecraftServer server, String name,
                                              BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer,
                                              Supplier<T> constructor) {
        DimensionDataStorage storage = server.overworld().getDataStorage();
        synchronized (INSTANCES) {
            Map<String, SavedData> stores = INSTANCES.computeIfAbsent(storage, s -> new HashMap<>());
            SavedData known = stores.get(name);
            if (known != null) {
                return (T) known;
            }
            T loaded = read(path(server, name), deserializer, server.registryAccess());
            T store = loaded != null ? loaded : constructor.get();
            storage.set(name, store);
            stores.put(name, store);
            return store;
        }
    }

    /**
     * Reads {@code data}, falling back to the backup when the main file no longer parses. The broken main file is kept
     * as {@code .corrupt-<millis>}; when the backup reads, it is also copied back over the main file so a later start
     * finds it without going through this fallback again. Returns null when neither file reads.
     */
    public static <T extends SavedData> T read(Path dataFile,
                                               BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer,
                                               HolderLookup.Provider registries) {
        if (!Files.isRegularFile(dataFile)) {
            // A missing main file is not the same as a new world: a crash between quarantine and restore leaves the
            // backup behind, and starting empty here would overwrite it on the next save (2026-10-25 review).
            Path backup = backupPath(dataFile);
            T recovered = Files.isRegularFile(backup) ? readFile(backup, deserializer, registries) : null;
            if (recovered != null && restore(backup, dataFile)) {
                Constants.LOG.warn("{} was missing; restored it from {}", dataFile, backup);
            }
            return recovered;
        }
        T store = readFile(dataFile, deserializer, registries);
        if (store != null) {
            return store;
        }
        Path backup = backupPath(dataFile);
        T recovered = Files.isRegularFile(backup) ? readFile(backup, deserializer, registries) : null;
        quarantine(dataFile);
        if (recovered != null) {
            if (restore(backup, dataFile)) {
                Constants.LOG.warn("Could not read {}; restored it from {}", dataFile, backup);
            }
        } else {
            Constants.LOG.warn("Could not read {} and no usable backup was found; starting with an empty store",
                    dataFile);
        }
        return recovered;
    }

    private static <T extends SavedData> T readFile(Path file,
                                                    BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer,
                                                    HolderLookup.Provider registries) {
        try {
            CompoundTag root = readRoot(file);
            // The load is part of the check: a gzip that opens but throws while it is decoded is just as unreadable
            // as a truncated one, and must reach the backup too (2026-10-22 release review).
            return deserializer.apply(root.getCompound("data"), registries);
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("Could not read {}: {}", file, ex.toString());
            return null;
        }
    }

    private static boolean readable(Path file) {
        try {
            readRoot(file);
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static CompoundTag readRoot(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             PushbackInputStream pushback = new PushbackInputStream(new FastBufferedInputStream(in), 2)) {
            // Vanilla saves are gzip, but NbtIo also writes plain NBT and an editor or a tool can leave one here;
            // reading both is what vanilla's own readTagFromDisk does (2026-10-22 release review).
            if (isGzip(pushback)) {
                return NbtIo.readCompressed(pushback, NbtAccounter.unlimitedHeap());
            }
            try (DataInputStream data = new DataInputStream(pushback)) {
                return NbtIo.read(data);
            }
        }
    }

    private static boolean isGzip(PushbackInputStream pushback) throws IOException {
        byte[] head = new byte[2];
        int read = pushback.read(head, 0, 2);
        if (read > 0) {
            pushback.unread(head, 0, read);
        }
        return read == 2 && (head[0] & 255) == 0x1f && (head[1] & 255) == 0x8b;
    }

    private static void quarantine(Path dataFile) {
        Path corrupt = dataFile.resolveSibling(dataFile.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            moveReplacing(dataFile, corrupt);
            forceDirectory(dataFile.getParent());
            Constants.LOG.warn("Kept the unreadable file as {}", corrupt);
        } catch (IOException ex) {
            Constants.LOG.error("Could not move the unreadable {} aside", dataFile, ex);
        }
    }

    private static boolean restore(Path backup, Path dataFile) {
        Path restored = dataFile.resolveSibling(dataFile.getFileName() + ".restore");
        try {
            Files.copy(backup, restored, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            moveReplacing(restored, dataFile);
            forceDirectory(dataFile.getParent());
            return true;
        } catch (IOException io) {
            Constants.LOG.error("Could not restore {} from {}", dataFile, backup, io);
            try {
                Files.deleteIfExists(restored);
            } catch (IOException ignored) {
                // the main file stays missing, so the next save writes a fresh one
            }
            return false;
        }
    }

    /** Replaces vanilla's {@code SavedData.save(File, HolderLookup.Provider)} body for one store. */
    public static void saveAtomically(SavedData data, File file, HolderLookup.Provider registries) {
        if (!data.isDirty()) {
            return;
        }
        Path target = file.toPath();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Path backup = backupPath(target);
        try {
            CompoundTag root = new CompoundTag();
            root.put("data", data.save(new CompoundTag(), registries));
            NbtUtils.addCurrentDataVersion(root);

            Path dir = target.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            NbtIo.writeCompressed(root, temp);
            forceFile(temp);
            if (Files.isRegularFile(target)) {
                // Keep the last file that reads: copying an unreadable main file over the backup would destroy the
                // only copy of the previous save (2026-10-22 release review).
                if (readable(target)) {
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    forceFile(backup);
                } else {
                    Constants.LOG.warn("Not replacing {}: {} does not read", backup, target);
                }
            }
            moveReplacing(temp, target);
            forceDirectory(dir);
            data.setDirty(false);
        } catch (Exception ex) {
            Constants.LOG.error("Could not save {}; the previous file is kept and the data stays dirty", target, ex);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // the temporary file is only a leftover; the target was never touched
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // directories cannot be opened for sync on every platform (Windows); the file itself is synced
        }
    }
}
