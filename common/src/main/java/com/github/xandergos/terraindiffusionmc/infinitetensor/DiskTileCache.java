package com.github.xandergos.terraindiffusionmc.infinitetensor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

// Persists decoded tensor windows so a region survives a restart
public final class DiskTileCache implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DiskTileCache.class);

    private static final int MAGIC = 0x54444331;
    private static final int FORMAT_VERSION = 1;
    private static final String MANIFEST = "manifest.txt";
    private static final int WRITE_QUEUE_DEPTH = 512;

    private final Path root;
    private final String fingerprint;
    private final boolean readOnly;
    private final AtomicLong bytesOnDisk = new AtomicLong();
    private final AtomicLong windowsOnDisk = new AtomicLong();
    private final BlockingQueue<Runnable> writes = new ArrayBlockingQueue<>(WRITE_QUEUE_DEPTH);
    private final Thread writer;
    private volatile boolean healthy = true;
    private volatile boolean closed;
    private volatile boolean manifestWritten;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private DiskTileCache(Path root, String fingerprint, boolean readOnly) {
        this.root = root;
        this.fingerprint = fingerprint;
        this.readOnly = readOnly;
        if (readOnly) {
            this.writer = null;
            return;
        }
        this.writer = new Thread(this::drain, "terrain-diffusion-cache-writer");
        this.writer.setDaemon(true);
        this.writer.setPriority(Thread.MIN_PRIORITY);
        this.writer.start();
    }

    // Opens an existing cache for reading; returns null if there is none for this fingerprint
    public static DiskTileCache openReadOnly(Path root, String fingerprint) {
        try {
            Path manifest = root.resolve(MANIFEST);
            if (!Files.isRegularFile(manifest)
                    || !fingerprint.equals(Files.readString(manifest, StandardCharsets.UTF_8).trim())) {
                return null;
            }
            LOG.info("Terrain cache opened read-only at {}", root);
            return new DiskTileCache(root, fingerprint, true);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // Opens the cache for fingerprint, discarding anything written under a different one
    public static DiskTileCache open(Path root, String fingerprint) {
        try {
            Path manifest = root.resolve(MANIFEST);
            String existing = Files.isRegularFile(manifest)
                    ? Files.readString(manifest, StandardCharsets.UTF_8).trim()
                    : null;
            boolean usable = fingerprint.equals(existing);
            if (!usable && existing != null) {
                LOG.info("Terrain cache fingerprint changed, discarding {}", root);
                deleteTree(root);
            }
            DiskTileCache cache = new DiskTileCache(root, fingerprint, false);
            if (usable) {
                cache.manifestWritten = true;
                long[] found = cache.measure();
                cache.bytesOnDisk.set(found[0]);
                cache.windowsOnDisk.set(found[1]);
                LOG.info("Terrain cache opened at {} with {} windows ({} MB)",
                        root, found[1], found[0] / (1024 * 1024));
            }
            return cache;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Terrain cache unavailable at {}: {}", root, e.toString());
            return null;
        }
    }

    public FloatTensor load(String stageId, int[] windowIndex) {
        if (!healthy) {
            return null;
        }
        Path file = pathFor(stageId, windowIndex);
        try {
            if (!Files.isRegularFile(file)) {
                misses.incrementAndGet();
                return null;
            }
            byte[] raw = Files.readAllBytes(file);
            FloatTensor tensor = decode(raw);
            if (tensor == null) {
                if (!readOnly) {
                    Files.deleteIfExists(file);
                }
                misses.incrementAndGet();
                return null;
            }
            hits.incrementAndGet();
            return tensor;
        } catch (IOException | RuntimeException e) {
            misses.incrementAndGet();
            return null;
        }
    }

    public void store(String stageId, int[] windowIndex, FloatTensor tensor) {
        if (readOnly || !healthy || closed) {
            return;
        }
        byte[] payload;
        try {
            payload = encode(tensor);
        } catch (RuntimeException e) {
            return;
        }
        Path file = pathFor(stageId, windowIndex);
        if (!enqueue(() -> write(file, payload))) {
            dropped.incrementAndGet();
        }
    }

    private boolean enqueue(Runnable task) {
        return writes.offer(task);
    }

    private void drain() {
        while (!closed) {
            try {
                writes.take().run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
            }
        }
    }

    private void write(Path file, byte[] payload) {
        try {
            ensureManifest();
            Files.createDirectories(file.getParent());
            boolean fresh = !Files.exists(file);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, payload);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            if (fresh) {
                windowsOnDisk.incrementAndGet();
            }
            bytesOnDisk.addAndGet(payload.length);
        } catch (IOException e) {
            if (healthy) {
                healthy = false;
                LOG.warn("Terrain cache disabled after write failure: {}", e.toString());
            }
        }
    }

    private void ensureManifest() throws IOException {
        if (manifestWritten) {
            return;
        }
        Files.createDirectories(root);
        Files.writeString(root.resolve(MANIFEST), fingerprint, StandardCharsets.UTF_8);
        manifestWritten = true;
    }

    private byte[] encode(FloatTensor tensor) {
        int[] shape = tensor.shape;
        float[] data = tensor.data;
        int header = 4 * (4 + shape.length);
        ByteBuffer buf = ByteBuffer.allocate(header + data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(MAGIC);
        buf.putInt(FORMAT_VERSION);
        buf.putInt(shape.length);
        for (int d : shape) {
            buf.putInt(d);
        }
        int crcAt = buf.position();
        buf.putInt(0);
        buf.asFloatBuffer().put(data);
        buf.position(buf.limit());
        byte[] out = buf.array();
        CRC32 crc = new CRC32();
        crc.update(out, crcAt + 4, data.length * 4);
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(crcAt, (int) crc.getValue());
        return out;
    }

    private FloatTensor decode(byte[] raw) {
        if (raw.length < 16) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt() != MAGIC || buf.getInt() != FORMAT_VERSION) {
            return null;
        }
        int ndim = buf.getInt();
        if (ndim <= 0 || ndim > 8) {
            return null;
        }
        int[] shape = new int[ndim];
        long count = 1;
        for (int d = 0; d < ndim; d++) {
            shape[d] = buf.getInt();
            if (shape[d] <= 0) {
                return null;
            }
            count *= shape[d];
            if (count > Integer.MAX_VALUE / 4) {
                return null;
            }
        }
        int expected = buf.getInt();
        int dataAt = buf.position();
        if (raw.length - dataAt != count * 4) {
            return null;
        }
        CRC32 crc = new CRC32();
        crc.update(raw, dataAt, (int) count * 4);
        if ((int) crc.getValue() != expected) {
            return null;
        }
        float[] data = new float[(int) count];
        buf.asFloatBuffer().get(data);
        return new FloatTensor(shape, data);
    }

    private Path pathFor(String stageId, int[] windowIndex) {
        StringBuilder name = new StringBuilder();
        for (int v : windowIndex) {
            if (name.length() > 0) {
                name.append('_');
            }
            name.append(v);
        }
        int bucket = (name.toString().hashCode() >>> 16) & 0xFF;
        return root.resolve(sanitise(stageId))
                .resolve(String.format("%02x", bucket))
                .resolve(name + ".bin");
    }

    private static String sanitise(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private long[] measure() {
        long[] total = {0, 0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    total[0] += a.size();
                    if (f.getFileName().toString().endsWith(".bin")) {
                        total[1]++;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return new long[]{0, 0};
        }
        return total;
    }


    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.deleteIfExists(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        if (readOnly) {
            if (hits.get() + misses.get() > 0) {
                LOG.info("Terrain cache (read-only): {} hits, {} misses", hits.get(), misses.get());
            }
            return;
        }
        writer.interrupt();
        if (hits.get() + misses.get() + dropped.get() > 0 || bytesOnDisk.get() > 0) {
            LOG.info("Terrain cache: {} hits, {} misses, {} dropped, {} windows / {} MB on disk",
                    hits.get(), misses.get(), dropped.get(), windowsOnDisk.get(),
                    bytesOnDisk.get() / (1024 * 1024));
        }
    }
}
