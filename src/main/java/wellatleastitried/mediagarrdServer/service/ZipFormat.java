package wellatleastitried.mediagarrdServer.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

final class ZipFormat {

    static final int STORED = 0;
    static final int DEFLATED = 8;
    static final long ZIP64_LIMIT = 0xFFFFFFFFL;
    static final long UINT32_MAX = 0xFFFFFFFFL;
    static final long ZIP64_LOCAL_THRESHOLD = 0xF0000000L;
    static final int LOCAL_HEADER_SIZE = 30;
    static final int CRC_OFFSET = 14;
    static final int ZIP64_FIELD_BYTES = 16;
    static final int MAX_NAME_BYTES = 0xFFFF;

    private static final int LOCAL_SIGNATURE = 0x04034b50;
    private static final int CENTRAL_SIGNATURE = 0x02014b50;
    private static final int END_SIGNATURE = 0x06054b50;
    private static final int ZIP64_END_SIGNATURE = 0x06064b50;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int UTF8_FLAG = 0x0800;
    private static final int VERSION_DEFAULT = 20;
    private static final int VERSION_ZIP64 = 45;
    private static final int MADE_BY_UNIX = 0x0314;
    private static final int MADE_BY_UNIX_ZIP64 = 0x032D;
    private static final int ZIP64_ID = 0x0001;
    private static final int TIMESTAMP_ID = 0x5455;
    private static final int OWNER_ID = 0x7875;
    private static final int DOS_BEFORE_1980 = 1 << 21 | 1 << 16;
    private static final int DOS_MAX_YEAR = 2107;

    private ZipFormat() {}

    record Entry(String name, long localOffset, long dataOffset, int method, long crc, long compressedSize, long size,
                 int mode, long uid, long gid, long modified, boolean localZip64) {}

    static byte[] nameBytes(String name) throws IOException {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_NAME_BYTES) {
            throw new IOException("Entry name is too long for a zip archive: " + name.substring(0, 64) + "...");
        }
        return bytes;
    }

    static byte[] localHeader(byte[] name, int method, long modified, long uid, long gid, boolean zip64, long crc, long compressedSize, long size) {
        byte[] extra = localExtra(modified, uid, gid, zip64, compressedSize, size);
        byte[] header = new byte[LOCAL_HEADER_SIZE + name.length + extra.length];
        put32(header, 0, LOCAL_SIGNATURE);
        put16(header, 4, zip64 ? VERSION_ZIP64 : VERSION_DEFAULT);
        put16(header, 6, UTF8_FLAG);
        put16(header, 8, method);
        put32(header, 10, dosTime(modified));
        System.arraycopy(localSizes(zip64, crc, compressedSize, size), 0, header, CRC_OFFSET, 12);
        put16(header, 26, name.length);
        put16(header, 28, extra.length);
        System.arraycopy(name, 0, header, LOCAL_HEADER_SIZE, name.length);
        System.arraycopy(extra, 0, header, LOCAL_HEADER_SIZE + name.length, extra.length);
        return header;
    }

    static byte[] localSizes(boolean zip64, long crc, long compressedSize, long size) {
        byte[] sizes = new byte[12];
        put32(sizes, 0, crc);
        put32(sizes, 4, zip64 ? ZIP64_LIMIT : compressedSize);
        put32(sizes, 8, zip64 ? ZIP64_LIMIT : size);
        return sizes;
    }

    static byte[] zip64LocalExtra(long compressedSize, long size) {
        byte[] extra = new byte[4 + ZIP64_FIELD_BYTES];
        put16(extra, 0, ZIP64_ID);
        put16(extra, 2, ZIP64_FIELD_BYTES);
        put64(extra, 4, size);
        put64(extra, 12, compressedSize);
        return extra;
    }

    private static byte[] localExtra(long modified, long uid, long gid, boolean zip64, long compressedSize, long size) {
        byte[] ownerData = ownerData(uid, gid);
        boolean timestamp = fitsUnixTime(modified);
        byte[] extra = new byte[(zip64 ? 4 + ZIP64_FIELD_BYTES : 0) + (timestamp ? 9 : 0) + 4 + ownerData.length];
        int pos = 0;
        if (zip64) {
            byte[] field = zip64LocalExtra(compressedSize, size);
            System.arraycopy(field, 0, extra, pos, field.length);
            pos += field.length;
        }
        if (timestamp) {
            pos = putTimestamp(extra, pos, modified);
        }
        put16(extra, pos, OWNER_ID);
        put16(extra, pos + 2, ownerData.length);
        System.arraycopy(ownerData, 0, extra, pos + 4, ownerData.length);
        return extra;
    }

    static byte[] ownerData(long uid, long gid) {
        byte[] data = new byte[11];
        data[0] = 1;
        data[1] = 4;
        put32(data, 2, Math.max(0, Math.min(uid, UINT32_MAX)));
        data[6] = 4;
        put32(data, 7, Math.max(0, Math.min(gid, UINT32_MAX)));
        return data;
    }

    static byte[] centralHeader(Entry entry, long offset) throws IOException {
        byte[] name = nameBytes(entry.name());
        boolean overflowSize = entry.size() >= ZIP64_LIMIT;
        boolean overflowCompressed = entry.compressedSize() >= ZIP64_LIMIT;
        boolean overflowOffset = offset >= ZIP64_LIMIT;
        int zip64Fields = (overflowSize ? 1 : 0) + (overflowCompressed ? 1 : 0) + (overflowOffset ? 1 : 0);
        boolean zip64 = zip64Fields > 0;
        boolean timestamp = fitsUnixTime(entry.modified());
        int extraLength = (zip64 ? 4 + 8 * zip64Fields : 0) + (timestamp ? 9 : 0) + 4;
        byte[] header = new byte[46 + name.length + extraLength];
        boolean needsZip64 = zip64 || entry.localZip64();
        put32(header, 0, CENTRAL_SIGNATURE);
        put16(header, 4, needsZip64 ? MADE_BY_UNIX_ZIP64 : MADE_BY_UNIX);
        put16(header, 6, needsZip64 ? VERSION_ZIP64 : VERSION_DEFAULT);
        put16(header, 8, UTF8_FLAG);
        put16(header, 10, entry.method());
        put32(header, 12, dosTime(entry.modified()));
        put32(header, 16, entry.crc());
        put32(header, 20, overflowCompressed ? ZIP64_LIMIT : entry.compressedSize());
        put32(header, 24, overflowSize ? ZIP64_LIMIT : entry.size());
        put16(header, 28, name.length);
        put16(header, 30, extraLength);
        put32(header, 38, externalAttributes(entry.mode(), entry.name().endsWith("/")));
        put32(header, 42, overflowOffset ? ZIP64_LIMIT : offset);
        System.arraycopy(name, 0, header, 46, name.length);
        int pos = 46 + name.length;
        if (zip64) {
            put16(header, pos, ZIP64_ID);
            put16(header, pos + 2, 8 * zip64Fields);
            pos += 4;
            if (overflowSize) {
                put64(header, pos, entry.size());
                pos += 8;
            }
            if (overflowCompressed) {
                put64(header, pos, entry.compressedSize());
                pos += 8;
            }
            if (overflowOffset) {
                put64(header, pos, offset);
                pos += 8;
            }
        }
        if (timestamp) {
            pos = putTimestamp(header, pos, entry.modified());
        }
        put16(header, pos, OWNER_ID);
        put16(header, pos + 2, 0);
        return header;
    }

    static byte[] endOfArchive(long entries, long centralSize, long centralOffset) {
        boolean zip64 = entries >= 0xFFFF || centralSize >= ZIP64_LIMIT || centralOffset >= ZIP64_LIMIT;
        byte[] tail = new byte[(zip64 ? 56 + 20 : 0) + 22];
        int pos = 0;
        if (zip64) {
            put32(tail, 0, ZIP64_END_SIGNATURE);
            put64(tail, 4, 44);
            put16(tail, 12, MADE_BY_UNIX_ZIP64);
            put16(tail, 14, VERSION_ZIP64);
            put64(tail, 24, entries);
            put64(tail, 32, entries);
            put64(tail, 40, centralSize);
            put64(tail, 48, centralOffset);
            put32(tail, 56, ZIP64_LOCATOR_SIGNATURE);
            put64(tail, 64, centralOffset + centralSize);
            put32(tail, 72, 1);
            pos = 76;
        }
        put32(tail, pos, END_SIGNATURE);
        put16(tail, pos + 8, Math.min(entries, 0xFFFF));
        put16(tail, pos + 10, Math.min(entries, 0xFFFF));
        put32(tail, pos + 12, Math.min(centralSize, ZIP64_LIMIT));
        put32(tail, pos + 16, Math.min(centralOffset, ZIP64_LIMIT));
        return tail;
    }

    static long externalAttributes(int mode, boolean directory) {
        return ((long) mode << 16) | ((mode & 0200) == 0 ? 1 : 0) | (directory ? 0x10 : 0);
    }

    static boolean fitsUnixTime(long millis) {
        long seconds = Math.floorDiv(millis, 1000L);
        return seconds >= 0 && seconds <= Integer.MAX_VALUE;
    }

    static long dosTime(long millis) {
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        if (time.getYear() < 1980) {
            return DOS_BEFORE_1980;
        }
        if (time.getYear() > DOS_MAX_YEAR) {
            time = LocalDateTime.of(DOS_MAX_YEAR, 12, 31, 23, 59, 58);
        }
        return ((long) (time.getYear() - 1980) << 25 | (long) time.getMonthValue() << 21 | (long) time.getDayOfMonth() << 16
            | (long) time.getHour() << 11 | (long) time.getMinute() << 5 | time.getSecond() >> 1) & 0xFFFFFFFFL;
    }

    private static int putTimestamp(byte[] target, int pos, long modified) {
        put16(target, pos, TIMESTAMP_ID);
        put16(target, pos + 2, 5);
        target[pos + 4] = 1;
        put32(target, pos + 5, Math.floorDiv(modified, 1000L));
        return pos + 9;
    }

    static void put16(byte[] target, int pos, long value) {
        target[pos] = (byte) value;
        target[pos + 1] = (byte) (value >> 8);
    }

    static void put32(byte[] target, int pos, long value) {
        put16(target, pos, value);
        put16(target, pos + 2, value >> 16);
    }

    static void put64(byte[] target, int pos, long value) {
        put32(target, pos, value);
        put32(target, pos + 4, value >> 32);
    }
}
