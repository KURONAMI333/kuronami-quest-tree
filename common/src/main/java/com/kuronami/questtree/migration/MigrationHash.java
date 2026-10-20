package com.kuronami.questtree.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Identifies the input files of a migration (2026-10-20): SHA-256 over each file's name and bytes, in the order given.
 * Stored with the applied migration so an admin can tell a repeat of the same input from a run on changed files.
 */
public final class MigrationHash {

    private MigrationHash() {
    }

    public static String of(List<Path> files) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Path f : files) {
                md.update(f.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(f));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
