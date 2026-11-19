package wellatleastitried.mediagarrdServer.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class WebhookUrl {

    static final int MAX_LENGTH = 2048;

    private WebhookUrl() {}

    static String validate(String raw) {
        String url = raw.strip();
        if (url.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Webhook URL is too long");
        }
        if (url.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c) || Character.isSpaceChar(c))) {
            throw new IllegalArgumentException("Webhook URL must not contain spaces or control characters");
        }
        URI uri = parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Webhook URL must use http or https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Webhook URL must not contain user info");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("Webhook URL must have a valid host name");
        }
        if (url.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Webhook URL must not contain a fragment");
        }
        return url;
    }

    static URI parse(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Webhook URL is not a valid URL");
        }
    }

    static String host(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "unknown host" : host;
        } catch (URISyntaxException e) {
            return "unknown host";
        }
    }

    static String target(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
        if (uri.getScheme() == null || uri.getHost() == null || uri.getHost().isEmpty()) {
            return null;
        }
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + port;
    }
}
