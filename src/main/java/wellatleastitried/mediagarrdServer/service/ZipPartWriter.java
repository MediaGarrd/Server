package wellatleastitried.mediagarrdServer.service;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import wellatleastitried.mediagarrdServer.service.ZipFormat.Entry;

final class ZipPartWriter implements Closeable {

    static final String PART_SUFFIX = ".part";
    static final String INDEX_SUFFIX = ".idx";
    static final String PARTIAL_SUFFIX = ".partial";

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int COMMIT_INTERVAL = 10_000;
    private static final String COLUMNS = "name,lfh_offset,data_offset,method,crc,csize,usize,mode,uid,gid,mtime,zip64";

    private final Path part;
    private final Path partial;
    private final Path index;
    private final ZipCompression compression;
    private final FileChannel channel;
    private final Connection connection;
    private final PreparedStatement insert;
    private final PreparedStatement exists;
    private final PreparedStatement lookup;
    private final PreparedStatement skip;
    private final PreparedStatement skipped;
    private final Deflater deflater;
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private final byte[] chunk = new byte[BUFFER_SIZE];
    private final byte[] compressed = new byte[BUFFER_SIZE];
    private int buffered;
    private long position;
    private int entries;
    private int pendingRows;
    private boolean completed;
    private boolean closed;

    ZipPartWriter(Path part, ZipCompression compression) throws IOException {
        this.part = part;
        this.partial = partialOf(part);
        this.index = indexOf(part);
        this.compression = compression;
        this.deflater = new Deflater(Math.max(compression.level(), 1), true);
        FileChannel opened = null;
        Connection connected = null;
        try {
            Files.deleteIfExists(index);
            opened = FileChannel.open(partial, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ, StandardOpenOption.WRITE);
            connected = ZipPartIndex.open(index);
            try (Statement statement = connected.createStatement()) {
                statement.execute("CREATE TABLE entries(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, lfh_offset INTEGER NOT NULL, data_offset INTEGER NOT NULL, "
                    + "method INTEGER NOT NULL, crc INTEGER NOT NULL, csize INTEGER NOT NULL, usize INTEGER NOT NULL, mode INTEGER NOT NULL, "
                    + "uid INTEGER NOT NULL, gid INTEGER NOT NULL, mtime INTEGER NOT NULL, zip64 INTEGER NOT NULL)");
                statement.execute("CREATE TABLE skipped(name TEXT PRIMARY KEY) WITHOUT ROWID");
            }
            connected.setAutoCommit(false);
            this.insert = connected.prepareStatement("INSERT INTO entries(" + COLUMNS + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?)");
            this.exists = connected.prepareStatement("SELECT 1 FROM entries WHERE name=?");
            this.lookup = connected.prepareStatement("SELECT " + COLUMNS + " FROM entries WHERE name=?");
            this.skip = connected.prepareStatement("INSERT OR IGNORE INTO skipped(name) VALUES(?)");
            this.skipped = connected.prepareStatement("SELECT 1 FROM skipped WHERE name=?");
            this.channel = opened;
            this.connection = connected;
        } catch (SQLException | IOException | RuntimeException e) {
            deflater.end();
            closeQuietly(connected);
            closeQuietly(opened);
            deleteQuietly(partial);
            deleteQuietly(index);
            throw e instanceof IOException io ? io : new IOException("Unable to create archive part " + part, e);
        }
    }

    static Path partialOf(Path part) {
        return part.resolveSibling(part.getFileName() + PARTIAL_SUFFIX);
    }

    static Path indexOf(Path part) {
        return part.resolveSibling(part.getFileName() + INDEX_SUFFIX);
    }

    static void discard(Path part) throws IOException {
        Files.deleteIfExists(part);
        Files.deleteIfExists(partialOf(part));
        Files.deleteIfExists(indexOf(part));
    }

    int entryCount() {
        return entries;
    }

    boolean contains(String name) throws IOException {
        try {
            exists.setString(1, name);
            try (ResultSet rows = exists.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new IOException("Unable to query the archive part index", e);
        }
    }

    void markSkipped(String name) throws IOException {
        try {
            skip.setString(1, name);
            skip.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Unable to update the archive part index", e);
        }
    }

    boolean wasSkipped(String name) throws IOException {
        try {
            skipped.setString(1, name);
            try (ResultSet rows = skipped.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new IOException("Unable to query the archive part index", e);
        }
    }

    void addEntry(String name, int mode, long uid, long gid, long modified, long size, InputStream content) throws IOException {
        int method = compression.stores(name) ? ZipFormat.STORED : ZipFormat.DEFLATED;
        boolean zip64 = size >= ZipFormat.ZIP64_LOCAL_THRESHOLD;
        byte[] nameBytes = ZipFormat.nameBytes(name);
        long localOffset = position;
        byte[] header = ZipFormat.localHeader(nameBytes, method, modified, uid, gid, zip64, 0, 0, 0);
        write(header, 0, header.length);
        long dataOffset = localOffset + header.length;

        CRC32 crc = new CRC32();
        long total = 0;
        long compressedSize = 0;
        if (method == ZipFormat.STORED) {
            int read;
            while ((read = content.read(chunk)) != -1) {
                crc.update(chunk, 0, read);
                write(chunk, 0, read);
                total += read;
            }
            compressedSize = total;
        } else {
            deflater.reset();
            int read;
            while ((read = content.read(chunk)) != -1) {
                crc.update(chunk, 0, read);
                total += read;
                deflater.setInput(chunk, 0, read);
                compressedSize += drain(false);
            }
            deflater.finish();
            compressedSize += drain(true);
        }
        if (total != size) {
            throw new EOFException("Entry " + name + " has " + total + " bytes but its header says " + size);
        }
        if (!zip64 && (compressedSize >= ZipFormat.ZIP64_LIMIT || total >= ZipFormat.ZIP64_LIMIT)) {
            throw new IOException("Entry " + name + " outgrew its header, size " + total + ", compressed " + compressedSize);
        }
        patchSizes(localOffset, nameBytes.length, zip64, crc.getValue(), compressedSize, total);
        record(new Entry(name, localOffset, dataOffset, method, crc.getValue(), compressedSize, total, mode, uid, gid, modified, zip64));
    }

    boolean addCopy(String name, String targetName) throws IOException {
        Entry target = find(targetName);
        if (target == null) {
            if (wasSkipped(targetName)) {
                return false;
            }
            throw new IOException("Hard link " + name + " points to a missing entry " + targetName);
        }
        byte[] nameBytes = ZipFormat.nameBytes(name);
        long localOffset = position;
        byte[] header = ZipFormat.localHeader(nameBytes, target.method(), target.modified(), target.uid(), target.gid(), target.localZip64(),
            target.crc(), target.compressedSize(), target.size());
        flush();
        write(header, 0, header.length);
        long remaining = target.compressedSize();
        long from = target.dataOffset();
        while (remaining > 0) {
            int length = (int) Math.min(chunk.length, remaining);
            ByteBuffer slice = ByteBuffer.wrap(chunk, 0, length);
            while (slice.hasRemaining()) {
                int read = channel.read(slice, from + slice.position());
                if (read < 0) {
                    throw new EOFException("Archive part ended while copying " + targetName);
                }
            }
            write(chunk, 0, length);
            from += length;
            remaining -= length;
        }
        record(new Entry(name, localOffset, localOffset + header.length, target.method(), target.crc(), target.compressedSize(), target.size(),
            target.mode(), target.uid(), target.gid(), target.modified(), target.localZip64()));
        return true;
    }

    void complete() throws IOException {
        flush();
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new IOException("Unable to save the archive part index", e);
        }
        release();
        Files.move(partial, part, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        completed = true;
    }

    @Override
    public void close() {
        release();
        if (!completed) {
            deleteQuietly(partial);
            deleteQuietly(index);
        }
    }

    private Entry find(String name) throws IOException {
        try {
            lookup.setString(1, name);
            try (ResultSet rows = lookup.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new Entry(rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getInt(4), rows.getLong(5), rows.getLong(6), rows.getLong(7),
                    rows.getInt(8), rows.getLong(9), rows.getLong(10), rows.getLong(11), rows.getInt(12) != 0);
            }
        } catch (SQLException e) {
            throw new IOException("Unable to query the archive part index", e);
        }
    }

    private void record(Entry entry) throws IOException {
        try {
            insert.setString(1, entry.name());
            insert.setLong(2, entry.localOffset());
            insert.setLong(3, entry.dataOffset());
            insert.setInt(4, entry.method());
            insert.setLong(5, entry.crc());
            insert.setLong(6, entry.compressedSize());
            insert.setLong(7, entry.size());
            insert.setInt(8, entry.mode());
            insert.setLong(9, entry.uid());
            insert.setLong(10, entry.gid());
            insert.setLong(11, entry.modified());
            insert.setInt(12, entry.localZip64() ? 1 : 0);
            insert.executeUpdate();
            entries++;
            if (++pendingRows >= COMMIT_INTERVAL) {
                connection.commit();
                pendingRows = 0;
            }
        } catch (SQLException e) {
            throw new IOException("Unable to record " + entry.name() + " in the archive part index", e);
        }
    }

    private long drain(boolean finish) throws IOException {
        long produced = 0;
        while (finish ? !deflater.finished() : !deflater.needsInput()) {
            int length = deflater.deflate(compressed);
            if (length > 0) {
                write(compressed, 0, length);
                produced += length;
            }
        }
        return produced;
    }

    private void patchSizes(long localOffset, int nameLength, boolean zip64, long crc, long compressedSize, long size) throws IOException {
        flush();
        writeAt(localOffset + ZipFormat.CRC_OFFSET, ZipFormat.localSizes(zip64, crc, compressedSize, size));
        if (zip64) {
            writeAt(localOffset + ZipFormat.LOCAL_HEADER_SIZE + nameLength, ZipFormat.zip64LocalExtra(compressedSize, size));
        }
    }

    private void writeAt(long at, byte[] data) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(data);
        long written = at;
        while (source.hasRemaining()) {
            written += channel.write(source, written);
        }
    }

    private void write(byte[] data, int offset, int length) throws IOException {
        if (length >= buffer.length) {
            flush();
            ByteBuffer source = ByteBuffer.wrap(data, offset, length);
            while (source.hasRemaining()) {
                channel.write(source);
            }
        } else {
            if (length > buffer.length - buffered) {
                flush();
            }
            System.arraycopy(data, offset, buffer, buffered, length);
            buffered += length;
        }
        position += length;
    }

    private void flush() throws IOException {
        if (buffered > 0) {
            ByteBuffer source = ByteBuffer.wrap(buffer, 0, buffered);
            while (source.hasRemaining()) {
                channel.write(source);
            }
            buffered = 0;
        }
    }

    private void release() {
        if (closed) {
            return;
        }
        closed = true;
        deflater.end();
        closeQuietly(skipped);
        closeQuietly(skip);
        closeQuietly(lookup);
        closeQuietly(exists);
        closeQuietly(insert);
        closeQuietly(connection);
        closeQuietly(channel);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                return;
            }
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            return;
        }
    }
}
