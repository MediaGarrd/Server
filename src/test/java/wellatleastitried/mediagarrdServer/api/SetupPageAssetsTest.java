package wellatleastitried.mediagarrdServer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class SetupPageAssetsTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    private static String read(String name) throws IOException {
        return Files.readString(STATIC.resolve(name));
    }

    @Test
    void thePagesNeedNoInlineScriptOrStyleSoTheCspHolds() throws IOException {
        for (String page : new String[] {"config.html", "index.html"}) {
            String html = read(page);
            assertFalse(Pattern.compile("<script(?![^>]*\\bsrc=)").matcher(html).find(), page + " has an inline script");
            assertFalse(html.contains("<style"), page + " has an inline style block");
            assertFalse(Pattern.compile("\\sstyle=").matcher(html).find(), page + " has an inline style attribute");
            assertFalse(Pattern.compile("\\son[a-z]+=").matcher(html).find(), page + " has an inline event handler");
        }
        try (Stream<Path> scripts = Files.walk(STATIC.resolve("js"))) {
            for (Path script : scripts.filter(path -> path.toString().endsWith(".js")).toList()) {
                String code = Files.readString(script);
                for (String forbidden : new String[] {"innerHTML", "insertAdjacentHTML", "eval(", "cssText", "setAttribute('style'"}) {
                    assertFalse(code.contains(forbidden), script + " uses " + forbidden);
                }
            }
        }
    }

    @Test
    void aFailedUnlockKeepsTheWorkspaceHiddenAndShowsTheError() throws IOException {
        String config = read("js/config.js");

        assertTrue(config.contains("workspace.hidden = true;"));
        assertTrue(read("config.html").contains("id=\"fatal\" role=\"alert\""));
    }

    @Test
    void thePinFormIsAccessibleAndRefocusedAfterAMistake() throws IOException {
        String html = read("config.html");
        String setup = read("js/setup.js");

        assertTrue(html.contains("aria-describedby=\"pinError\""));
        assertTrue(html.contains("id=\"pinError\" data-error role=\"alert\" aria-live=\"polite\""));
        assertTrue(setup.contains("form.elements.pin.focus()"));
    }

    @Test
    void theFinishButtonIsFoundByItsOwnAttribute() throws IOException {
        assertTrue(read("config.html").contains("data-finish"));
        assertTrue(read("js/setup.js").contains("querySelector('[data-finish]')"));
    }

    @Test
    void theFinishReplyShowsThePairingPinWithACopyButtonAndNeverStoresIt() throws IOException {
        String html = read("config.html");
        String setup = read("js/setup.js");
        String config = read("js/config.js");

        assertTrue(html.contains("id=\"pairingResult\" hidden"));
        assertTrue(html.contains("data-copy-pin"));
        assertTrue(html.contains("data-without-pin"));
        assertTrue(html.indexOf("id=\"pairingResult\"") < html.indexOf("<div class=\"stack\" data-setup>"));
        assertTrue(setup.contains("code.textContent = pairingPin;"));
        assertTrue(setup.contains("result.pairingPin"));
        assertFalse(setup.contains("localStorage") || setup.contains("sessionStorage") || setup.contains("location."));
        assertTrue(config.contains("document.getElementById('pairingResult')"));
        assertTrue(read("css/mediagarrd.css").contains(".pin-row { display: flex; flex-wrap: wrap;"));
    }

    @Test
    void aRateLimitedUnlockShowsTheWaitFromRetryAfter() throws IOException {
        assertTrue(read("js/api.js").contains("Retry-After"));
        assertTrue(read("js/setup.js").contains("e.retryAfter"));
    }

    @Test
    void aPartiallySavedSettingsFormIsRefreshedAndExplained() throws IOException {
        String settings = read("js/settings.js");

        assertTrue(settings.contains("were saved, but the interval was not"));
        assertTrue(settings.contains("api.getConfig().then(show)"));
    }

    @Test
    void theServiceFormAndTableAreLabelled() throws IOException {
        String html = read("config.html");

        assertTrue(html.contains("<legend class=\"field-label\">Host paths to back up</legend>"));
        assertTrue(html.contains("aria-label=\"Host path\""));
        assertTrue(html.contains("aria-label=\"Remove path\""));
        assertTrue(html.contains("<caption class=\"sr-only\">"));
        assertEquals(3, html.split("<th scope=\"col\"", -1).length - 1);
    }

    @Test
    void focusIsVisibleTablesScrollAndMotionCanBeReduced() throws IOException {
        String css = read("css/mediagarrd.css");

        assertFalse(css.contains("outline: none"));
        assertTrue(css.contains("input:focus-visible"));
        assertTrue(css.contains(".table-wrap { overflow-x: auto; }"));
        assertTrue(css.contains("prefers-reduced-motion: reduce"));
        assertTrue(read("config.html").contains("class=\"table-wrap\""));
    }

    @Test
    void theWebhookPanelIsWiredIntoTheSetupPage() throws IOException {
        String html = read("config.html");
        String config = read("js/config.js");
        String webhook = read("js/webhook.js");
        String api = read("js/api.js");

        assertTrue(html.contains("id=\"webhookForm\""));
        assertTrue(html.contains("id=\"webhookUrl\" name=\"url\" type=\"url\" autocomplete=\"off\""));
        assertTrue(html.contains("data-webhook-test"));
        assertTrue(html.contains("data-webhook-clear"));
        assertTrue(html.indexOf("id=\"webhook\"") < html.indexOf("id=\"finish\""));
        assertTrue(config.contains("initWebhook(document.getElementById('webhookForm'), notify)"));
        assertTrue(api.contains("'/api/v1/notifications/webhook/test'"));
        assertTrue(webhook.contains("[data-webhook-test]"));
        assertTrue(webhook.contains("[data-webhook-clear]"));
        assertFalse(webhook.contains("url.value = status.target"));
    }

    @Test
    void intervalFieldIsBoundedFinishSitsAfterServicesAndPinErrorIsRed() throws IOException {
        String html = read("config.html");
        String settings = read("js/settings.js");

        assertTrue(html.contains("id=\"intervalHours\" name=\"intervalHours\" type=\"number\" min=\"0.25\" max=\"8760\""));
        assertTrue(settings.contains("MIN_HOURS = 0.25"));
        assertTrue(settings.contains("MAX_HOURS = 8760"));
        assertTrue(settings.contains("Backup interval must be between 15 minutes and 365 days"));
        assertTrue(html.indexOf("id=\"services\"") < html.indexOf("id=\"finish\""));
        assertTrue(read("css/mediagarrd.css").contains(".note.error-text { color: #fca5a5; }"));
    }

    @Test
    void noNoteSitsDirectlyBeforeAFieldGroupInTheSetupPage() throws IOException {
        assertFalse(Pattern.compile("<p class=\"note\"[^>]*>[^<]*</p>\\s*<div class=\"field-group\"").matcher(read("config.html")).find());
    }

    @Test
    void gridTracksAndItemsCanShrinkAndButtonsWrapOnNarrowScreens() throws IOException {
        String css = read("css/mediagarrd.css");

        assertTrue(css.contains("grid-template-columns: 360px minmax(0, 1fr);"));
        assertTrue(css.contains("@media (max-width: 900px) { .workspace { grid-template-columns: minmax(0, 1fr); } }"));
        assertTrue(css.contains(".workspace > * { min-width: 0; }"));
        assertTrue(css.contains(".button-row { display: flex; flex-wrap: wrap;"));
        assertTrue(css.contains(".input-row { display: grid; grid-template-columns: minmax(0, 1fr) auto;"));
    }
}
