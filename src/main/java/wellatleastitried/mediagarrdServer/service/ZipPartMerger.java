package wellatleastitried.mediagarrdServer.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import wellatleastitried.mediagarrdServer.service.ZipFormat.Entry;

final class ZipPartMerger {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZipPartMerger.class);

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int COMMIT_INTERVAL = 10_000;
    private static final List<String> DATABASE_SIDECAR_SUFFIXES = List.of("", "-journal", "-wal", "-shm");

    record Result(String checksum, long entries, long bytes) {}

    private ZipPartMerger() {}

    static Result merge(List<Path> snapshotParts, List<Path> parts, Path output, Path scratchDirectory) throws IOException {
        Path names = scratchDirectory.resolve("merge-names" + ZipPartWriter.INDEX_SUFFIX);
        MessageDigest digest = sha256();
        try (OutputStream file = Files.newOutputStream(output); Sink sink = new Sink(file, digest); NameIndex seen = new NameIndex(names)) {
            List<Path> ordered = new ArrayList<>(snapshotParts);
            ordered.addAll(parts);
            long[] bases = new long[ordered.size()];
            for (int i = 0; i < ordered.size(); i++) {
                bases[i] = sink.written();
                copyData(ordered.get(i), sink);
            }

            Set<String> reserved = new HashSet<>();
            long centralStart = sink.written();
            long entries = 0;
            for (int i = 0; i < ordered.size(); i++) {
                boolean snapshot = i < snapshotParts.size();
                entries += writeCentral(ordered.get(i), bases[i], snapshot, reserved, seen, sink);
            }
            long centralSize = sink.written() - centralStart;
            sink.write(ZipFormat.endOfArchive(entries, centralSize, centralStart));
            sink.flush();
            LOGGER.debug("Merged {} part(s) into {} entries", ordered.size(), entries);
            return new Result(HexFormat.of().formatHex(digest.digest()), entries, sink.written());
        } finally {
            Files.deleteIfExists(names);
        }
    }

    private static void copyData(Path part, Sink sink) throws IOException {
        byte[] chunk = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(part)) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                sink.write(chunk, 0, read);
            }
        }
    }

    private static long writeCentral(Path part, long base, boolean snapshot, Set<String> reserved, NameIndex seen, Sink sink) throws IOException {
        Path index = ZipPartWriter.indexOf(part);
        if (!Files.isRegularFile(index)) {
            throw new IOException("Archive part " + part + " has no index");
        }
        long count = 0;
        try (Connection connection = ZipPartIndex.open(index);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name,lfh_offset,method,crc,csize,usize,mode,uid,gid,mtime,zip64 FROM entries ORDER BY id")) {
            while (rows.next()) {
                String name = rows.getString(1);
                if (snapshot) {
                    if (!name.endsWith("/")) {
                        DATABASE_SIDECAR_SUFFIXES.forEach(suffix -> reserved.add(name + suffix));
                    }
                } else if (reserved.contains(name)) {
                    throw new IOException("Archive part " + part + " contains " + name + ", which the database snapshot owns");
                }
                seen.add(name, part);
                Entry entry = new Entry(name, rows.getLong(2), 0, rows.getInt(3), rows.getLong(4), rows.getLong(5), rows.getLong(6),
                    rows.getInt(7), rows.getLong(8), rows.getLong(9), rows.getLong(10), rows.getInt(11) != 0);
                sink.write(ZipFormat.centralHeader(entry, base + entry.localOffset()));
                count++;
            }
        } catch (SQLException e) {
            throw new IOException("Unable to read the index of archive part " + part, e);
        }
        return count;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static final class NameIndex implements AutoCloseable {

        private final Connection connection;
        private final PreparedStatement insert;
        private int pending;

        NameIndex(Path file) throws IOException {
            Connection opened = null;
            try {
                Files.deleteIfExists(file);
                opened = ZipPartIndex.open(file);
                try (Statement statement = opened.createStatement()) {
                    statement.execute("CREATE TABLE names(name TEXT PRIMARY KEY) WITHOUT ROWID");
                }
                opened.setAutoCommit(false);
                insert = opened.prepareStatement("INSERT INTO names(name) VALUES(?)");
                connection = opened;
            } catch (SQLException | IOException e) {
                if (opened != null) {
                    try {
                        opened.close();
                    } catch (SQLException ignored) {
                        LOGGER.debug("Unable to close the name index", ignored);
                    }
                }
                throw e instanceof IOException io ? io : new IOException("Unable to create the merge name index", e);
            }
        }

        void add(String name, Path part) throws IOException {
            try {
                insert.setString(1, name);
                insert.executeUpdate();
                if (++pending >= COMMIT_INTERVAL) {
                    connection.commit();
                    pending = 0;
                }
            } catch (SQLException e) {
                throw new IOException("Duplicate or unrecordable entry " + name + " in archive part " + part, e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                insert.close();
                connection.close();
            } catch (SQLException e) {
                throw new IOException("Unable to close the merge name index", e);
            }
        }
    }

    private static final class Sink extends OutputStream {

        private final OutputStream out;
        private final MessageDigest digest;
        private final byte[] buffer = new byte[BUFFER_SIZE];
        private int buffered;
        private long written;

        Sink(OutputStream out, MessageDigest digest) {
            this.out = out;
            this.digest = digest;
        }

        long written() {
            return written;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            if (length >= buffer.length) {
                flush();
                digest.update(data, offset, length);
                out.write(data, offset, length);
            } else {
                if (length > buffer.length - buffered) {
                    flush();
                }
                System.arraycopy(data, offset, buffer, buffered, length);
                buffered += length;
            }
            written += length;
        }

        @Override
        public void flush() throws IOException {
            if (buffered > 0) {
                digest.update(buffer, 0, buffered);
                out.write(buffer, 0, buffered);
                buffered = 0;
            }
        }

        @Override
        public void close() throws IOException {
            flush();
            out.flush();
        }
    }
}
