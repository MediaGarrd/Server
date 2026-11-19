package wellatleastitried.mediagarrdServer.utilities;

public final class LogSanitizer {

    private static final int MAX_LENGTH = 256;

    private LogSanitizer() {}

    public static String clean(Object value) {
        if (value == null) {
            return "null";
        }
        String text = value.toString();
        StringBuilder cleaned = new StringBuilder(Math.min(text.length(), MAX_LENGTH + 3));
        for (int i = 0; i < text.length() && cleaned.length() < MAX_LENGTH; i++) {
            char c = text.charAt(i);
            cleaned.append(Character.isISOControl(c) || c == ' ' || c == ' ' ? '_' : c);
        }
        if (cleaned.length() < text.length()) {
            cleaned.append("...");
        }
        return cleaned.toString();
    }
}
