package wellatleastitried.mediagarrdServer.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ZipFormatTest {

    @Test
    void theExtendedTimestampCoversOnlyTimesFromTheEpochToTheSignedLimit() {
        assertFalse(ZipFormat.fitsUnixTime(-1L));
        assertFalse(ZipFormat.fitsUnixTime(-631_152_000_000L));
        assertTrue(ZipFormat.fitsUnixTime(0L));
        assertTrue(ZipFormat.fitsUnixTime(Integer.MAX_VALUE * 1000L));
        assertFalse(ZipFormat.fitsUnixTime((Integer.MAX_VALUE + 1L) * 1000L));
    }

    @Test
    void ownerDataIsAlwaysFourByteIdsInLittleEndian() {
        assertArrayEquals(new byte[] {1, 4, 33, 0, 0, 0, 4, (byte) 0xE8, 3, 0, 0}, ZipFormat.ownerData(33, 1000));
        assertArrayEquals(new byte[] {1, 4, 0, 0, 0, 0, 4, 0, 0, 0, 0}, ZipFormat.ownerData(0, 0));
    }

    @Test
    void ownerIdsOutsideThirtyTwoBitsAreClamped() {
        byte[] data = ZipFormat.ownerData(0x100000005L, -7);

        assertEquals(11, data.length);
        assertArrayEquals(new byte[] {1, 4, -1, -1, -1, -1, 4, 0, 0, 0, 0}, data);
    }
}
