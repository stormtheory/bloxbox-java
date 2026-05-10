// Compile: javac --module-path ./lib --add-modules javafx.web,javafx.swing -cp . *.java
// Run:     java --module-path ./lib --add-modules javafx.web,javafx.swing launcher [--debug] [--game-log-output]
/*
 * launcher.java — BloxBox entry point, shared constants, config I/O, HTTP, and Sober launch logic.
 *
 * All other classes (GameCard, PinDialog, LauncherApp, RequestDialogWebView,
 * RequestDialogFallback) live in their own .java files and reference the
 * public static members here directly — no package needed for single-dir builds.
 *
 * Config file:   /etc/bloxbox/roblox_whitelist.json  (root-owned, parent edits)
 * Requests file: ~/.cache/bloxbox_launcher/requests.json  (child writes, parent reviews)
 * Thumb cache:   ~/.cache/bloxbox_launcher/thumbnails/<place_id>.png
 */

import java.awt.Color;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.image.BufferedImage;

public class launcher {

    // ── Logging — shared across all files ────────────────────────────────────
    public static final Logger LOG = Logger.getLogger("bloxbox");

    // ── CLI flags — set in main(), read everywhere ────────────────────────────
    public static boolean DEBUG_MODE      = false;
    public static boolean GAME_LOG_OUTPUT = false;

    // ── System config paths — root-owned on Linux, user-local on Windows/macOS ─
    // Windows: config lives next to the jar; Linux: /etc/bloxbox/
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final boolean IS_MAC     = System.getProperty("os.name").toLowerCase().contains("mac");

    public static final String CONFIG_PATH =
        IS_WINDOWS ? "config/roblox_whitelist.json" :
        IS_MAC     ? System.getProperty("user.home") + "/Library/Application Support/bloxbox/roblox_whitelist.json" :
                     "/etc/bloxbox/roblox_whitelist.json";

    // Requests file lives in the user's home cache on all platforms
    public static final String REQUESTS_PATH =
        System.getProperty("user.home") + (IS_WINDOWS ? "\\.cache\\bloxbox_launcher\\requests.json"
                                                       : "/.cache/bloxbox_launcher/requests.json");

    // Thumbnail disk cache — platform path separator handled by Path.of()
    public static final Path CACHE_DIR =
        Path.of(System.getProperty("user.home"), ".cache", "bloxbox_launcher", "thumbnails");

    // ── Window / app identity ─────────────────────────────────────────────────
    public static final String WINDOW_TITLE = "BloxBox";

    // ── PIN lock for the Request-a-Game flow ──────────────────────────────────
    // Set LOCK_REQUEST_GAMES = true and paste the SHA-256 hash of your chosen PIN.
    // Generate hash (Linux/macOS): echo -n "yourpin" | sha256sum
    // Generate hash (Windows PS):  (Get-FileHash -InputStream ([IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes("yourpin"))) -Algorithm SHA256).Hash.ToLower()
    public static final boolean LOCK_REQUEST_GAMES         = true;
    public static final String  LOCK_REQUEST_PIN_PASS_HASH = "d74ff0ee8da3b9806b18c877dbf29bbde50b5bd8e4dad7a3a725000feb82e8f1";

    // ── PIN lock for the parent Approval flow (separate from request PIN) ─────
    public static final boolean GAME_APPROVAL_NEEDED      = true;
    public static final boolean LOCK_APPROVAL_PIN         = true;
    public static final String  LOCK_APPROVAL_PIN_PASS_HASH = "d74ff0ee8da3b9806b18c877dbf29bbde50b5bd8e4dad7a3a725000feb82e8f1";

    // ── Game category constants ───────────────────────────────────────────────
    // These are the recognised category labels that map to sidebar entries.
    // The emoji prefix in the JSON value is extracted automatically for the sidebar icon.
    // Any game whose "category" field is missing or unrecognised goes into UNSORTED.
    public static final String[] GAME_CATEGORIES = {
        "🎭 Role Playing",
        "🏎️ Racing",
        "👨‍👩‍👧 Family",
        "📚 Education",
        "🌍 World Exploring",
        "Sports"
    };
    public static final String GAME_UNSORTED_CATEGORY = "🔧 Unsorted";

    // ── Roblox browse URL — used by both WebView and fallback dialogs ─────────
    public static final String ROBLOX_GAME_SEARCH_URL =
        "https://www.roblox.com/charts?device=computer&country=us";

    // ── Roblox thumbnail API — place ID → PNG URL, no universe lookup needed ──
    public static final String THUMBNAIL_API =
        "https://thumbnails.roblox.com/v1/places/gameicons?placeIds=%s&size=256x256&format=Png";

    // ── Visual palette — shared across all UI files ───────────────────────────
    public static final Color BG_COLOR       = Color.decode("#0f0f1a"); // Dark navy background
    public static final Color CARD_COLOR     = Color.decode("#1a1a2e"); // Slightly lighter card background
    public static final Color ACCENT_COLOR   = Color.decode("#e94560"); // Red accent (play button)
    public static final Color REQUEST_COLOR  = Color.decode("#2a6496"); // Blue (request a game button)
    public static final Color TEXT_COLOR     = Color.decode("#eaeaea"); // Light text
    public static final Color SUBTEXT_COLOR  = Color.decode("#888888"); // Muted subtext
    public static final Color HOVER_COLOR    = Color.decode("#252540"); // Card hover highlight
    public static final Color SIDEBAR_COLOR  = Color.decode("#13132a"); // Sidebar background (slightly darker than BG)
    public static final Color SIDEBAR_SEL    = Color.decode("#2a2a4a"); // Selected sidebar item highlight

    // ── Shared fonts ──────────────────────────────────────────────────────────
    public static final Font FONT_TITLE = new Font("Georgia",    Font.BOLD,  28);
    public static final Font FONT_CARD  = new Font("Georgia",    Font.BOLD,  12);
    public static final Font FONT_SMALL = new Font("Monospaced", Font.PLAIN, 10);
    public static final Font FONT_BTN   = new Font("Georgia",    Font.BOLD,  10);

    // ── Game name font ────────────────────────────────────────────────────────
    // Dialog logical font — Java maps this to a system font chain.
    // Emoji rendering is handled separately by EmojiLabel which paints
    // emoji codepoints using a directly loaded color emoji font.
    public static final Font FONT_NAME  = new Font("Dialog", Font.BOLD, 12);

    // ── Emoji font — loaded once, shared by all EmojiLabel instances ──────────

    // ── Card / grid layout constants ──────────────────────────────────────────
    public static final int CARD_WIDTH  = 200; // Fixed card width in pixels
    public static final int CARD_HEIGHT = 300; // Fixed card height in pixels
    public static final int THUMB_SIZE  = 160; // Thumbnail display size in pixels
    public static final int COLS        = 4;   // Game cards per row

    // ── Sidebar animation constants ───────────────────────────────────────────
    public static final int SIDEBAR_COLLAPSED_W = 52;  // Icon-only width (px)
    public static final int SIDEBAR_EXPANDED_W  = 200; // Expanded with label (px)
    public static final int SIDEBAR_ANIM_STEP   = 12;  // Pixels per timer tick
    public static final int SIDEBAR_ANIM_MS     = 16;  // Timer interval (~60 fps)

    // ── Shared HTTP client — reused across all API calls ─────────────────────
    // Single instance, thread-safe, persistent connection pool
    public static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    // ── Entry point ───────────────────────────────────────────────────────────
    public static void main(String[] args) {
        // Parse CLI flags — mirrors Python argparse behaviour
        for (String arg : args) {
            if (arg.equals("--debug")           || arg.equals("-d")) DEBUG_MODE      = true;
            if (arg.equals("--game-log-output") || arg.equals("-l")) GAME_LOG_OUTPUT = true;
        }

        // Configure Java logging level from --debug flag
        LOG.setLevel(DEBUG_MODE ? Level.ALL : Level.INFO);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(DEBUG_MODE ? Level.ALL : Level.INFO);
        LOG.addHandler(ch);
        LOG.setUseParentHandlers(false);

        // Warn early if the whitelist config is missing — nothing to show without it
        if (!Files.exists(Path.of(CONFIG_PATH))) {
            LOG.severe("[bloxbox] System config not found at " + CONFIG_PATH);
            JOptionPane.showMessageDialog(null,
                "Game config not found at:\n" + CONFIG_PATH +
                "\n\nAsk a parent to set up BloxBox.",
                "Config Missing", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // Create thumbnail cache directory on first run — safe no-op if already exists
        try { Files.createDirectories(CACHE_DIR); }
        catch (IOException e) { LOG.warning("[bloxbox] Could not create cache dir: " + e.getMessage()); }

        // Create requests file parent directory if it doesn't exist yet
        try { Files.createDirectories(Path.of(REQUESTS_PATH).getParent()); }
        catch (IOException e) { LOG.warning("[bloxbox] Could not create requests dir: " + e.getMessage()); }

        // JavaFX must be initialised on the JavaFX Application Thread before any
        // WebView is created. Calling Platform.startup() here ensures that even
        // though we drive the main window from Swing's EDT, the FX toolkit is
        // ready when RequestDialogWebView needs it.
        try {
            javafx.application.Platform.startup(() -> {
                LOG.fine("[bloxbox] JavaFX platform started");
            });
        } catch (IllegalStateException e) {
            // Already running — harmless if FX was started elsewhere
            LOG.fine("[bloxbox] JavaFX already started: " + e.getMessage());
        }

        // Launch the Swing UI on the Event Dispatch Thread — Swing is not thread-safe
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {} // Fall back to Metal L&F if system L&F unavailable

            LauncherApp app = new LauncherApp();
            app.setSize(1100, 720); // Wider to accommodate sidebar + game grid
            app.setLocationRelativeTo(null); // Centre on screen
            app.setVisible(true);
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONFIG HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Load the approved games list from the root-owned config file.
     * Format: {"games": [{"name":"...","place_id":"...","category":"...","description":"...","url":"..."}, ...]}
     * Returns an empty list on any error — never null.
     */
    public static List<Map<String, String>> loadConfig() {
        Path p = Path.of(CONFIG_PATH);
        if (!Files.exists(p)) return List.of();
        try {
            String raw = Files.readString(p);
            return parseGamesJson(raw);
        } catch (Exception e) {
            LOG.severe("[launcher] Could not read config " + CONFIG_PATH + ": " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Load pending game requests from the child-writable requests file.
     * Returns a mutable list — safe to append to and write back.
     */
    public static List<Map<String, String>> loadRequests() {
        Path p = Path.of(REQUESTS_PATH);
        if (!Files.exists(p)) return new ArrayList<>();
        try {
            String raw = Files.readString(p);
            return parseRequestsJson(raw);
        } catch (Exception e) {
            LOG.severe("[launcher] Could not read requests file: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Append a new game request entry and write the file back.
     * Safe to call from any thread — file I/O is the only side effect.
     * Returns true on success, false on any I/O or permission error.
     */
    public static boolean saveRequest(String placeId, String gameName, String note, String url) {
        LOG.fine("[bloxbox] save_request: " + placeId + " / " + gameName);

        List<Map<String, String>> requests = new ArrayList<>(loadRequests());

        // Build the timestamped request entry
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("place_id",  placeId.strip());
        entry.put("game_name", gameName.strip());
        entry.put("url",       url.strip());
        entry.put("note",      note.strip());
        entry.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        requests.add(entry);

        try {
            String json = buildRequestsJson(requests);
            Files.writeString(Path.of(REQUESTS_PATH), json,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LOG.fine("[bloxbox] Request saved → " + REQUESTS_PATH);
            return true;
        } catch (Exception e) {
            LOG.severe("[bloxbox] Failed to save request: " + e.getMessage());
            return false;
        }
    }

    /**
     * Verify a PIN string against the stored SHA-256 hash.
     * Returns true immediately if LOCK_REQUEST_GAMES is false.
     * Hash format matches `echo -n "pin" | sha256sum` output.
     */
    public static boolean verifyPin(String inputPin) {
        if (!LOCK_REQUEST_GAMES) return true; // PIN check disabled
        try {
            MessageDigest md  = MessageDigest.getInstance("SHA-256");
            byte[]        dig = md.digest(inputPin.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb  = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString().equals(LOCK_REQUEST_PIN_PASS_HASH);
        } catch (NoSuchAlgorithmException e) {
            LOG.severe("[bloxbox] SHA-256 unavailable: " + e.getMessage());
            return false;
        }
    }

    /**
     * Kill any running Sober (org.vinegarhq.Sober) process.
     * Uses pkill on Linux/macOS, taskkill on Windows.
     * Safe to call even when Sober is not running.
     */
    public static void terminateSober() {
        try {
            String[] cmd = IS_WINDOWS
                ? new String[]{"taskkill", "/F", "/IM", "sober.exe"}
                : new String[]{"pkill", "sober"};
            Process result = Runtime.getRuntime().exec(cmd);
            int code = result.waitFor();
            LOG.info("[bloxbox] terminate sober exit code: " + code);
        } catch (Exception e) {
            LOG.warning("[bloxbox] terminateSober failed: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // THUMBNAIL FETCHING
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Resolve the CDN image URL for a given Roblox place ID.
     * Uses the direct gameicons endpoint — no universe ID lookup required.
     * Returns the URL string or null on any failure.
     */
    public static String fetchThumbnailUrl(String placeId) {
        String url = String.format(THUMBNAIL_API, placeId);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            // Minimal parse of {"data":[{"imageUrl":"https://..."}]}
            String body = resp.body();
            int idx = body.indexOf("\"imageUrl\"");
            if (idx < 0) return null;
            int s = body.indexOf('"', idx + 10) + 1;
            int e = body.indexOf('"', s);
            return body.substring(s, e);
        } catch (Exception e) {
            LOG.severe("[launcher] Thumbnail URL fetch failed for " + placeId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetch the display name of a Roblox game via the economy assets API.
     * Returns the name string or null on failure.
     */
    public static String fetchGameName(String placeId) {
        String url = "https://economy.roblox.com/v2/assets/" + placeId + "/details";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            // Minimal parse of {"Name":"..."}
            int idx = body.indexOf("\"Name\"");
            if (idx < 0) return null;
            int s = body.indexOf('"', idx + 6) + 1;
            int e = body.indexOf('"', s);
            return body.substring(s, e);
        } catch (Exception e) {
            LOG.severe("[bloxbox] Game name lookup failed for " + placeId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Full pipeline: place ID → CDN URL → disk-cached BufferedImage.
     *
     * Cache path: ~/.cache/bloxbox_launcher/thumbnails/<place_id>.png
     * On cache hit:  read from disk (fast, no network).
     * On cache miss: download PNG, write to disk, return image.
     * Returns null if any step fails — callers show an emoji placeholder.
     */
    public static BufferedImage fetchThumbnailImage(String placeId) {
        // ── Cache hit — skip all network calls ───────────────────────────────
        Path cacheFile = CACHE_DIR.resolve(placeId + ".png");
        if (Files.exists(cacheFile)) {
            try {
                return ImageIO.read(cacheFile.toFile());
            } catch (Exception e) {
                LOG.warning("[launcher] Corrupt cache for " + placeId + " — purging");
                try { Files.deleteIfExists(cacheFile); } catch (IOException ignored) {}
            }
        }

        // ── Cache miss — fetch URL then download bytes ────────────────────────
        String thumbUrl = fetchThumbnailUrl(placeId);
        if (thumbUrl == null) return null;

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(thumbUrl))
                .timeout(Duration.ofSeconds(8)).GET().build();
            byte[] imgData = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray()).body();

            // Persist to disk so the next launch is instant
            Files.write(cacheFile, imgData);

            return ImageIO.read(new ByteArrayInputStream(imgData));
        } catch (Exception e) {
            LOG.severe("[launcher] Thumbnail download failed for " + placeId + ": " + e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GAME LAUNCHING
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Launch a Roblox game directly via the roblox:// URI scheme, bypassing
     * the Roblox homepage entirely.
     *
     * Launch order:
     *   1. Flatpak Sober  — confirmed on Linux Mint 22.3
     *   2. xdg-open       — if Sober registered the roblox:// handler
     *   3. Windows shell  — java.awt.Desktop.browse() for roblox:// on Windows
     *   4. Error dialog   — with install instructions
     *
     * Runs in a background thread so the UI stays responsive.
     */
    public static void launchGame(String placeId, String gameName, JFrame parentFrame) {
        final String pid = placeId.strip();
        final String uri = "roblox://experiences/start?placeId=" + pid;
        LOG.info("[launcher] Launching '" + gameName + "' → " + uri);

        new Thread(() -> {
            // ── Strategy 1: Flatpak Sober (Linux) ────────────────────────────
            if (!IS_WINDOWS && !IS_MAC) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("flatpak", "run", "org.vinegarhq.Sober", uri);
                    pb.redirectErrorStream(true); // Merge stdout+stderr for log monitoring
                    Process proc = pb.start();
                    // Daemon thread monitors Sober's output for known error strings
                    Thread monitor = new Thread(() -> monitorSoberLog(proc, gameName, parentFrame));
                    monitor.setDaemon(true);
                    monitor.start();
                    return; // Handed off — done
                } catch (IOException e) {
                    LOG.warning("[launcher] flatpak not found, trying xdg-open...");
                }

                // ── Strategy 2: xdg-open roblox:// ───────────────────────────
                try {
                    new ProcessBuilder("xdg-open", uri).start();
                    return;
                } catch (IOException ignored) {}
            }

            // ── Strategy 3: Windows / macOS — java.awt.Desktop ───────────────
            // Desktop.browse() uses the OS-registered handler for roblox://
            if (IS_WINDOWS || IS_MAC) {
                try {
                    java.awt.Desktop.getDesktop().browse(new URI(uri));
                    return;
                } catch (Exception e) {
                    LOG.severe("[launcher] Desktop.browse failed: " + e.getMessage());
                }
            }

            // ── Strategy 4: Nothing worked ────────────────────────────────────
            SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(parentFrame,
                    "Could not launch '" + gameName + "'.\n\n" +
                    "Linux:   flatpak install flathub org.vinegarhq.Sober\n" +
                    "Windows: Install Roblox from roblox.com",
                    "Launch Failed", JOptionPane.ERROR_MESSAGE)
            );
        }, "sober-launch-" + pid).start();
    }

    /**
     * Background thread body: tails Sober's merged stdout/stderr looking for
     * known fatal error strings.  On match: kills Sober and shows a friendly
     * dialog on the EDT.
     *
     * Mirrors Python _monitor_sober_log().
     */
    public static void monitorSoberLog(Process proc, String gameName, JFrame parentFrame) {
        // Hard-error patterns — shown to the user as a dialog
        Map<String, String[]> ERROR_PATTERNS = new LinkedHashMap<>();
        ERROR_PATTERNS.put("App not yet initialized, returning from game", new String[]{
            "Login / Session Error",
            "Roblox returned to the home screen before the game loaded.\n\n" +
            "Fix: Open Sober manually, log in again, then retry."
        });
        ERROR_PATTERNS.put("HTTP error code:`nil`", new String[]{
            "Network / Auth Error",
            "Roblox reported a network or authentication error.\n\nCheck your connection and try again."
        });
        ERROR_PATTERNS.put("SessionReporterState_GameExitRequested", new String[]{
            "Kicked by Server",
            "The Roblox server ended the session before the game started.\n\nTry again shortly."
        });

        // Watch patterns — logged at debug level only, not shown to the child
        Map<String, String> WATCH_PATTERNS = new LinkedHashMap<>();
        WATCH_PATTERNS.put("524",    "Error 524 — Server Timeout");
        WATCH_PATTERNS.put("server", "Game server did not respond in time");
        WATCH_PATTERNS.put("Wait",   "Temporary Roblox issue detected");

        String[] detectedError = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {

                // Raw game log passthrough — enabled with --game-log-output
                if (GAME_LOG_OUTPUT) LOG.fine("[sober] " + line);

                // Debug-only watch pattern scan
                if (DEBUG_MODE) {
                    for (Map.Entry<String, String> w : WATCH_PATTERNS.entrySet())
                        if (line.contains(w.getKey()))
                            LOG.warning("[bloxbox] Watch hit: " + w.getValue());
                }

                // Hard-error scan — stop on first match
                for (Map.Entry<String, String[]> err : ERROR_PATTERNS.entrySet()) {
                    if (line.contains(err.getKey())) {
                        detectedError = err.getValue();
                        LOG.severe("[bloxbox] Error detected: " + err.getKey());
                        break;
                    }
                }
                if (detectedError != null) break;
            }
        } catch (Exception e) {
            LOG.severe("[bloxbox] Monitor thread error: " + e.getMessage());
            return;
        }

        if (detectedError != null) {
            final String[] err = detectedError;
            terminateSober();
            // Always update Swing from the EDT — never from a background thread
            SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(parentFrame,
                    err[1], "⚠️  " + err[0] + " — " + gameName,
                    JOptionPane.ERROR_MESSAGE)
            );
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MINIMAL JSON HELPERS — no external deps, sufficient for our controlled format
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Parse {"games":[{...},{...}]} into a list of string maps.
     * Handles the standard whitelist config format.
     */
    public static List<Map<String, String>> parseGamesJson(String json) {
        return parseJsonArray(json);
    }

    /** Parse {"requests":[{...}]} — same shape as games. */
    public static List<Map<String, String>> parseRequestsJson(String json) {
        return parseJsonArray(json);
    }

    /** Internal: extract the first JSON array from the string and parse its objects. */
    private static List<Map<String, String>> parseJsonArray(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        int arrStart = json.indexOf('[');
        int arrEnd   = json.lastIndexOf(']');
        if (arrStart < 0 || arrEnd < 0) return result;
        for (String obj : splitObjects(json.substring(arrStart + 1, arrEnd))) {
            Map<String, String> m = parseStringMap(obj);
            if (!m.isEmpty()) result.add(m);
        }
        return result;
    }

    /** Split a JSON array body into individual top-level {...} object strings. */
    public static List<String> splitObjects(String arrayBody) {
        List<String> objs = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if      (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0 && start >= 0) { objs.add(arrayBody.substring(start, i + 1)); start = -1; } }
        }
        return objs;
    }

    /**
     * Parse a flat JSON object {"key":"value",...} into a LinkedHashMap.
     * Preserves key insertion order — important for requests serialisation.
     */
    /**
     * Parse a flat JSON object {"key":"value",...} into a LinkedHashMap.
     * Preserves key insertion order — important for requests serialisation.
     *
     * Unicode / emoji handling:
     *   JSON encodes emoji above U+FFFF as surrogate pairs: \U+d83c\U+df7c
     *   Our regex captures those as literal backslash-u sequences. We pass
     *   the raw captured value through unescapeJson() which:
     *     1. Combines surrogate pairs into proper Java char sequences
     *     2. Handles u+XXXX single-codepoint escapes
     *     3. Handles standard escapes: \n \t \r \\ \"
     *   Without this, emoji names render as [\U+d83c\U+df7c] in game cards.
     */
    public static Map<String, String> parseStringMap(String obj) {
        Map<String, String> m = new LinkedHashMap<>();
        // Match "key": "value" pairs — captures raw escaped content inside the quotes
        Pattern p  = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher mt = p.matcher(obj);
        while (mt.find()) m.put(mt.group(1), unescapeJson(mt.group(2)));
        return m;
    }

    /**
     * Unescape a JSON string value captured by the regex in parseStringMap.
     *
     * Handles:
     *   u+XXXX        — single Unicode escape (BMP codepoints)
     *   u+XXXXu+XXXX — surrogate pair (emoji / supplementary codepoints above U+FFFF)
     *                    e.g. \U+d83c\U+df7c -> 🍼  \U+d83e\U+dde0 -> 🧠
     *   \\"          — escaped quote -> "
     *   \\n          — newline
     *   \\t          — tab
     *   \\r          — carriage return
     *   \\\\       — escaped backslash -> \
     */
    public static String unescapeJson(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb  = new StringBuilder(s.length());
        int           len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= len) {
                sb.append(c);
                continue;
            }
            char next = s.charAt(i + 1);
            switch (next) {
                case '"':  sb.append('"');  i++; break;
                case '\\': sb.append('\\'); i++; break;
                case 'n':  sb.append('\n'); i++; break;
                case 't':  sb.append('\t'); i++; break;
                case 'r':  sb.append('\r'); i++; break;
                case 'u':
                    // Need at least 4 hex digits after u
                    if (i + 5 <= len) {
                        String hex = s.substring(i + 2, i + 6);
                        try {
                            int codeUnit = Integer.parseInt(hex, 16);
                            // Check for surrogate pair: high surrogate \U+D800-\U+DBFF
                            // followed by low surrogate \U+DC00-\U+DFFF
                            if (codeUnit >= 0xD800 && codeUnit <= 0xDBFF && i + 11 <= len
                                    && s.charAt(i + 6) == '\\' && s.charAt(i + 7) == 'u') {
                                String hex2 = s.substring(i + 8, i + 12);
                                int low = Integer.parseInt(hex2, 16);
                                if (low >= 0xDC00 && low <= 0xDFFF) {
                                    // Valid surrogate pair — combine into supplementary codepoint
                                    int codePoint = Character.toCodePoint((char) codeUnit, (char) low);
                                    sb.appendCodePoint(codePoint);
                                    i += 11; // consumed uXXXXuXXXX (12 chars, -1 for loop i++)
                                    break;
                                }
                            }
                            // Single BMP codepoint
                            sb.append((char) codeUnit);
                            i += 5; // consumed uXXXX (6 chars, -1 for loop i++)
                        } catch (NumberFormatException e) {
                            // Not valid hex — emit literally
                            sb.append(c);
                        }
                    } else {
                        sb.append(c); // Not enough chars — emit literally
                    }
                    break;
                default:
                    sb.append(c); // Unknown escape — emit backslash literally
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * Strip emoji and clean up a Roblox game name for display in Swing.
     * Java 2D on Linux cannot render color emoji fonts via any Swing path.
     * Removes:
     *   - Supplementary codepoints (U+1F000+) — all modern emoji
     *   - BMP emoji ranges (U+2600-U+27FF misc symbols/dingbats)
     *   - Variation selectors (U+FE00-U+FE0F)
     *   - Zero-width joiners (U+200D)
     *   - Leftover empty brackets [] or () after stripping
     *   - Leading/trailing whitespace and punctuation artifacts
     */
    public static String cleanGameName(String name) {
        if (name == null || name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder();
        name.codePoints().forEach(cp -> {
            // Skip all emoji and symbol ranges
            if (cp >= 0x1F000) return;                        // Supplementary emoji
            if (cp >= 0x2600 && cp <= 0x27FF) return;         // Misc symbols + dingbats
            if (cp >= 0x2B00 && cp <= 0x2BFF) return;         // Misc symbols and arrows
            if (cp >= 0xFE00 && cp <= 0xFE0F) return;         // Variation selectors
            if (cp == 0x200D) return;                          // Zero-width joiner
            sb.appendCodePoint(cp);
        });
        // Remove empty bracket pairs left behind after emoji removal e.g. "[] " or "() "
        String result = sb.toString()
            .replaceAll("\\[\\s*\\]", "")   // empty []
            .replaceAll("\\(\\s*\\)", "")   // empty ()
            .replaceAll("\s{2,}", " ")     // collapse multiple spaces
            .strip();
        return result.isEmpty() ? name : result; // Fall back to original if everything was stripped
    }

    /** Serialise a list of request maps back to a pretty-printed JSON string. */
    public static String buildRequestsJson(List<Map<String, String>> requests) {
        StringBuilder sb = new StringBuilder("{\n  \"requests\": [\n");
        for (int i = 0; i < requests.size(); i++) {
            sb.append("    {");
            Map<String, String> r    = requests.get(i);
            List<String>        keys = new ArrayList<>(r.keySet());
            for (int j = 0; j < keys.size(); j++) {
                String k = keys.get(j), v = r.get(k).replace("\"", "\\\"");
                sb.append("\"").append(k).append("\": \"").append(v).append("\"");
                if (j < keys.size() - 1) sb.append(", ");
            }
            sb.append(i < requests.size() - 1 ? "},\n" : "}\n");
        }
        return sb.append("  ]\n}").toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI UTILITY HELPERS — used by multiple UI files
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Create a styled flat button matching the BloxBox dark theme.
     * Hover darkens the background — mirrors Python activebackground.
     * All buttons in the app go through this factory for visual consistency.
     */
    public static javax.swing.JButton makeButton(String text, Color bg) {
        javax.swing.JButton btn = new javax.swing.JButton(text);
        btn.setFont(FONT_BTN);
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 14, 6, 14));
        btn.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

        // Darken on hover, restore on exit
        Color hoverBg = bg.darker();
        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { if (btn.isEnabled()) btn.setBackground(hoverBg); }
            @Override public void mouseExited (java.awt.event.MouseEvent e) { if (btn.isEnabled()) btn.setBackground(bg);      }
        });
        return btn;
    }
}
