package wellatleastitried.mediagarrdServer.service;

import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import wellatleastitried.mediagarrdServer.MediaGarrdProperties;

@Component
public class ZipCompression {

    static final int MIN_LEVEL = 0;
    static final int MAX_LEVEL = 9;
    private static final Set<String> ALREADY_COMPRESSED = Set.of(
        "zip", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar",
        "jpg", "jpeg", "png", "gif", "webp",
        "mp4", "mkv", "mp3", "flac", "ogg",
        "mov", "avi", "webm", "m4v", "m4a", "aac", "opus", "heic", "avif",
        "lz4", "zstd", "jar", "docx", "xlsx", "pptx");

    private final int level;

    @Autowired
    public ZipCompression(MediaGarrdProperties properties) {
        this(properties.getCompressionLevel());
    }

    public ZipCompression(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalStateException("mediagarrd.compression-level must be between " + MIN_LEVEL + " and " + MAX_LEVEL + ", was " + level);
        }
        this.level = level;
    }

    public int level() {
        return level;
    }

    boolean stores(String name) {
        if (level == 0) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot > name.lastIndexOf('/') && ALREADY_COMPRESSED.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
