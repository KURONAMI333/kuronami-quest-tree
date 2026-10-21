package com.kuronami.questtree.data;

import com.kuronami.questtree.Constants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Writes the mod's own {@link SavedData} files without vanilla's truncate-then-stream risk: the new file is written to
 * a temporary file, the previous file is kept as {@code <name>.dat.bak}, and the temporary file is moved over the
 * target atomically. A failed write keeps both the old file and the dirty flag, so the next save tries again.
 * <p>
 * The layout matches vanilla {@code SavedData.save(File, HolderLookup.Provider)} exactly: {@code {"data": {...},
 * "DataVersion": <current>}}. On load, a main file that no longer parses is moved to
 * {@code <name>.dat.corrupt-<millis>} and the backup is restored before vanilla reads it.
 */
public final class SavedDataFiles {

    private static final Map<MinecraftServer, Boolean> RECOVERED = Collections.synchronizedMap(new WeakHashMap<>());

    private SavedDataFiles() {
    }

    public static Path path(MinecraftServer server, String name) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat");
    }

    public static Path backupPath(Path dataFile) {
        return dataFile.resolveSibling(dataFile.getFileName() + ".bak");
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
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                forceFile(backup);
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

    /** Restores the backup once per server before the first store of this name is read. */
    public static void recoverOnce(MinecraftServer server, String name) {
        synchronized (RECOVERED) {
            if (RECOVERED.containsKey(server)) {
                return;
            }
            RECOVERED.put(server, Boolean.TRUE);
        }
        recover(path(server, name));
    }

    /** Returns true when the main file was unreadable and the backup replaced it. */
    public static boolean recover(Path dataFile) {
        if (!Files.isRegularFile(dataFile)) {
            return false;
        }
        try {
            NbtIo.readCompressed(dataFile, NbtAccounter.unlimitedHeap());
            return false;
        } catch (Exception ex) {
            Path backup = backupPath(dataFile);
            if (!Files.isRegularFile(backup)) {
                Constants.LOG.warn("Could not read {} and there is no backup to fall back to: {}", dataFile, ex.toString());
                return false;
            }
            if (!parses(backup)) {
                Constants.LOG.warn("Could not read {} and the backup {} does not parse either; leaving both alone",
                        dataFile, backup);
                return false;
            }
            Path corrupt = dataFile.resolveSibling(dataFile.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Path restored = dataFile.resolveSibling(dataFile.getFileName() + ".restore");
            try {
                Files.copy(dataFile, corrupt, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                Files.copy(backup, restored, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                moveReplacing(restored, dataFile);
                forceDirectory(dataFile.getParent());
                Constants.LOG.warn("Could not read {} ({}); restored {} and kept the broken file as {}",
                        dataFile, ex.toString(), backup, corrupt);
                return true;
            } catch (IOException io) {
                Constants.LOG.error("Could not restore {} from {}", dataFile, backup, io);
                try {
                    Files.deleteIfExists(restored);
                } catch (IOException ignored) {
                    // the target is still the broken file, so there is nothing to clean up for correctness
                }
                return false;
            }
        }
    }

    private static boolean parses(Path file) {
        try {
            NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            return true;
        } catch (Exception ex) {
            return false;
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
