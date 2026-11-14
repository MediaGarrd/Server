package wellatleastitried.mediagarrdServer.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Iterator;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;

final class GeneratedTar extends InputStream {

    record Spec(String name, long size, byte[] pattern) {

        static Spec directory(String name) {
            return new Spec(name, -1, null);
        }

        static Spec file(String name, long size, byte[] pattern) {
            return new Spec(name, size, pattern);
        }
    }

    private static final int BLOCK = 512;

    private final Iterator<Spec> specs;
    private final byte[] header = new byte[BLOCK];
    private int headerPosition = BLOCK;
    private long contentRemaining;
    private long contentOffset;
    private byte[] pattern;
    private int padding;
    private int trailer = 2 * BLOCK;

    GeneratedTar(Iterator<Spec> specs) {
        this.specs = specs;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int read = read(one, 0, 1);
        return read < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        while (true) {
            if (headerPosition < BLOCK) {
                int count = Math.min(length, BLOCK - headerPosition);
                System.arraycopy(header, headerPosition, target, offset, count);
                headerPosition += count;
                return count;
            }
            if (contentRemaining > 0) {
                int count = (int) Math.min(length, contentRemaining);
                for (int i = 0; i < count; i++) {
                    target[offset + i] = pattern[(int) ((contentOffset + i) % pattern.length)];
                }
                contentOffset += count;
                contentRemaining -= count;
                if (contentRemaining == 0) {
                    padding = (int) ((BLOCK - contentOffset % BLOCK) % BLOCK);
                }
                return count;
            }
            if (padding > 0) {
                int count = Math.min(length, padding);
                java.util.Arrays.fill(target, offset, offset + count, (byte) 0);
                padding -= count;
                return count;
            }
            if (specs.hasNext()) {
                begin(specs.next());
                continue;
            }
            if (trailer > 0) {
                int count = Math.min(length, trailer);
                java.util.Arrays.fill(target, offset, offset + count, (byte) 0);
                trailer -= count;
                return count;
            }
            return -1;
        }
    }

    private void begin(Spec spec) {
        TarArchiveEntry entry = new TarArchiveEntry(spec.name());
        entry.setModTime(new Date(1_700_000_000_000L));
        entry.setUserId(1000);
        entry.setGroupId(1000);
        if (spec.size() < 0) {
            entry.setMode(040755);
        } else {
            entry.setMode(0100644);
            entry.setSize(spec.size());
        }
        java.util.Arrays.fill(header, (byte) 0);
        entry.writeEntryHeader(header);
        headerPosition = 0;
        contentRemaining = Math.max(spec.size(), 0);
        contentOffset = 0;
        pattern = spec.pattern();
    }
}
