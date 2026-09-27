package dev.p2p.security;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Caller must flush saves and keep world mutations stopped throughout snapshot creation. */
public final class BackupService {
    public static final long MAX_BYTES = 128L * 1024 * 1024;
    public static Path create(Path world, Path destination) throws IOException {
        world = world.toRealPath();
        Files.createDirectories(destination);
        destination = destination.toRealPath();
        if (destination.startsWith(world)) throw new IOException("Backup must be outside world");
        Path temporary = Files.createTempFile(destination, "incomplete-", ".tmp");
        Path complete = destination.resolve("world-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".zip");
        long size = 0;
        int count = 0;
        long deadline = System.nanoTime() + 10_000_000_000L;
        try {
            try (var output = new ZipOutputStream(Files.newOutputStream(temporary)); var paths = Files.walk(world)) {
                for (var iterator = paths.iterator(); iterator.hasNext();) {
                    Path p = iterator.next();
                    if (Files.isSymbolicLink(p)) throw new IOException("Symbolic links are unsupported in backups");
                    if (!Files.isRegularFile(p)) continue;
                    String name = world.relativize(p).toString().replace('\\', '/');
                    if (name.equals("session.lock")) continue;
                    if (++count > 10000) throw new IOException("Alpha backup file limit exceeded");
                    output.putNextEntry(new ZipEntry(name));
                    try (var input = Files.newInputStream(p)) {
                        byte[] buffer = new byte[32768]; int n;
                        while ((n = input.read(buffer)) != -1) {
                            size += n;
                            if (size > MAX_BYTES || System.nanoTime() > deadline) throw new IOException("Alpha backup size/time limit exceeded");
                            output.write(buffer, 0, n);
                        }
                    }
                    output.closeEntry();
                }
            }
            try (var zip = new ZipFile(temporary.toFile())) {
                if (zip.getEntry("level.dat") == null) throw new IOException("No level.dat in snapshot");
                for (var it = zip.entries(); it.hasMoreElements();) {
                    var entry = it.nextElement(); CRC32 crc = new CRC32();
                    try (var input = zip.getInputStream(entry)) {
                        byte[] b = new byte[32768]; int n;
                        while ((n = input.read(b)) != -1) crc.update(b, 0, n);
                    }
                    if (crc.getValue() != entry.getCrc()) throw new IOException("Backup checksum mismatch");
                }
            }
            Files.move(temporary, complete, StandardCopyOption.ATOMIC_MOVE);
            // Only remove older completed archives after a new valid archive exists.
            try (var paths = Files.list(destination)) {
                List<Path> archives = paths.filter(p -> p.getFileName().toString().startsWith("world-")
                        && p.toString().endsWith(".zip")).sorted(Comparator.comparing(Path::toString).reversed()).toList();
                for (int i = 5; i < archives.size(); i++) Files.delete(archives.get(i));
            }
            return complete;
        } finally { Files.deleteIfExists(temporary); }
    }
}
