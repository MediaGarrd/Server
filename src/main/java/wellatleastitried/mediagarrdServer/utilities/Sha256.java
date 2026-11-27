package wellatleastitried.mediagarrdServer.utilities;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Sha256 {

    private Sha256() {}

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String hex(String value) {
        return hex(newDigest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
