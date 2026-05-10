// Compile: javac launcher.java
// Run:     java launcher [--debug] [--game-log-output]
/*
 * BloxBoxLauncher.java — Kid-facing Roblox whitelist launcher (Java Swing port).
 *
 * Features:
 *   - Shows only parent-approved games as tiles with cover art thumbnails
 *   - Launches directly into the game via roblox:// URI (bypasses Roblox homepage)
 *   - Request button lets the child submit a new game URL for parent review
 *   - Requests are saved to ~/.cache/bloxbox_launcher/requests.json (root-owned, parent reviews it)
 *
 * Run as the child's user account (no sudo needed to launch).
 * The config and requests files are root-owned so only the parent can modify them.
 *
 * Compile:   javac launcher.java
 * Run:       java launcher [--debug] [--game-log-output]
 * Or single-file (Java 21+): java launcher.java
 *
 * Dependencies (all standard JDK — no extra JARs needed):
 *   javax.swing, java.awt, java.net.http, org.json (bundled via simple parser below)
 *
 * NOTE: For thumbnail display this uses javax.imageio — no Pillow equivalent needed.
 *       For JSON parsing a minimal built-in parser is used to avoid external deps;
 *       swap in org.json or Jackson if you prefer a full JSON library.
 */

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
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
import java.util.concurrent.CompletableFuture;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class launcher {

    // ── Logging ───────────────────────────────────────────────────────────────
    private static final Logger LOG = Logger.getLogger("bloxbox");

    // ── CLI flags ─────────────────────────────────────────────────────────────
    // Mirrors Python argparse: --debug and --game-log-output
    private static boolean DEBUG_MODE       = false;
    private static boolean GAME_LOG_OUTPUT  = false;

    // ── System config paths — root-owned, child cannot modify ─────────────────
    // These mirror /etc/bloxbox/config.py values — update to match your install
    private static final String CONFIG_PATH    = "/etc/bloxbox/roblox_whitelist.json";
    private static final String REQUESTS_PATH  = System.getProperty("user.home") + ".cache/bloxbox_launcher/requests.json";
    private static final Path   CACHE_DIR      = Path.of(System.getProperty("user.home"), ".cache", "bloxbox_launcher", "thumbnails");
    private static final String WINDOW_TITLE   = "BloxBox Game Launcher";

    // PIN lock — set LOCK_REQUEST_GAMES=true and provide SHA-256 hash of the PIN
    // Generate hash: echo -n "yourpin" | sha256sum
    private static final boolean LOCK_REQUEST_GAMES        = true;
    private static final String  LOCK_REQUEST_PIN_PASS_HASH = "d74ff0ee8da3b9806b18c877dbf29bbde50b5bd8e4dad7a3a725000feb82e8f1"; // Set your hash here

    // Roblox browse URL — shown in the fallback request dialog browser
    private static final String ROBLOX_GAME_SEARCH_URL =
        "https://www.roblox.com/charts?device=computer&country=us";

    // ── Roblox API ────────────────────────────────────────────────────────────
    // Direct thumbnail endpoint — takes place ID, no universe ID lookup needed
    private static final String THUMBNAIL_API =
        "https://thumbnails.roblox.com/v1/places/gameicons?placeIds=%s&size=256x256&format=Png";

    // ── Visual settings ───────────────────────────────────────────────────────
    private static final Color BG_COLOR      = Color.decode("#0f0f1a");  // Dark navy background
    private static final Color CARD_COLOR    = Color.decode("#1a1a2e");  // Slightly lighter card background
    private static final Color ACCENT_COLOR  = Color.decode("#e94560");  // Red accent (play button)
    private static final Color REQUEST_COLOR = Color.decode("#2a6496");  // Blue (request a game button)
    private static final Color TEXT_COLOR    = Color.decode("#eaeaea");  // Light text
    private static final Color SUBTEXT_COLOR = Color.decode("#888888");  // Muted subtext
    private static final Color HOVER_COLOR   = Color.decode("#252540");  // Card hover highlight

    private static final Font FONT_TITLE = new Font("Georgia",   Font.BOLD,  28);
    private static final Font FONT_CARD  = new Font("Georgia",   Font.BOLD,  12);
    private static final Font FONT_SMALL = new Font("Monospaced", Font.PLAIN, 10);
    private static final Font FONT_BTN   = new Font("Georgia",   Font.BOLD,  10);

    // Card dimensions — tall enough for thumbnail + name + play button
    private static final int CARD_WIDTH  = 200;
    private static final int CARD_HEIGHT = 300;
    private static final int THUMB_SIZE  = 160;   // Thumbnail display size in pixels
    private static final int COLS        = 4;     // Game cards per row

    // ── Shared HTTP client — reused for all Roblox API calls ─────────────────
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    // ── Entry point ───────────────────────────────────────────────────────────
    public static void main(String[] args) {
        // Parse CLI flags — mirrors Python argparse
        for (String arg : args) {
            if (arg.equals("--debug")           || arg.equals("-d")) DEBUG_MODE      = true;
            if (arg.equals("--game-log-output") || arg.equals("-l")) GAME_LOG_OUTPUT = true;
        }

        // Configure logging level based on --debug flag
        LOG.setLevel(DEBUG_MODE ? Level.ALL : Level.INFO);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(DEBUG_MODE ? Level.ALL : Level.INFO);
        LOG.addHandler(ch);
        LOG.setUseParentHandlers(false);

        // Warn early if config file is missing — parallel to Python FileNotFoundError
        if (!Files.exists(Path.of(CONFIG_PATH))) {
            LOG.severe("[bloxbox] System config not found at " + CONFIG_PATH);
            JOptionPane.showMessageDialog(null,
                "System config not found at:\n" + CONFIG_PATH +
                "\n\nAsk a parent to set up BloxBox.",
                "Config Missing", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // Create cache directory on first run — equivalent to CACHE_DIR.mkdir(parents=True)
        try { Files.createDirectories(CACHE_DIR); }
        catch (IOException e) { LOG.warning("[bloxbox] Could not create cache dir: " + e.getMessage()); }

        // Launch Swing UI on the Event Dispatch Thread (EDT) — Swing is not thread-safe
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {} // Fall back to default Metal L&F

            LauncherApp app = new LauncherApp();
            app.setSize(940, 680);
            app.setLocationRelativeTo(null); // Centre on screen
            app.setVisible(true);
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONFIG HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Load the approved games list from the root-owned config file.
     * Config format (JSON): {"games": [{"name": "...", "place_id": "...", "description": "..."}, ...]}
     * Returns a list of game maps. Returns empty list on error (never null).
     */
    static List<Map<String, String>> loadConfig() {
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
     * Load pending game requests from the requests file.
     * File: /etc/bloxbox/requests.json  (written as the child's user, readable by parent)
     * Returns list of request maps: [{place_id, game_name, url, note, timestamp}, ...]
     */
    static List<Map<String, String>> loadRequests() {
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
     * Append a new game request to the requests JSON file.
     * Equivalent to Python's save_request() — reads, appends, writes atomically-ish.
     * Returns true on success, false on permission/IO error.
     */
    static boolean saveRequest(String placeId, String gameName, String note, String url) {
        LOG.fine("[bloxbox] save_request called: " + placeId + " / " + gameName);
        LOG.fine("[bloxbox] REQUESTS_PATH: " + REQUESTS_PATH);
        LOG.fine("[bloxbox] File exists: " + Files.exists(Path.of(REQUESTS_PATH)));

        List<Map<String, String>> requests = new ArrayList<>(loadRequests());
        LOG.fine("[bloxbox] Existing requests: " + requests.size());

        // Build the new request entry
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("place_id",  placeId.strip());
        entry.put("game_name", gameName.strip());
        entry.put("url",       url.strip());
        entry.put("note",      note.strip());
        entry.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        requests.add(entry);

        // Serialize and write — simple JSON builder (no external dep)
        try {
            String json = buildRequestsJson(requests);
            Files.writeString(Path.of(REQUESTS_PATH), json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LOG.fine("[bloxbox] Request saved → " + REQUESTS_PATH);
            return true;
        } catch (Exception e) {
            LOG.severe("[bloxbox] Failed to save request: " + e.getMessage());
            return false;
        }
    }

    /**
     * Verify input PIN against the stored SHA-256 hash.
     * If LOCK_REQUEST_GAMES is false, always returns true (PIN check disabled).
     * Mirrors Python verify_pin() — hash generated by: echo -n "pin" | sha256sum
     */
    static boolean verifyPin(String inputPin) {
        if (!LOCK_REQUEST_GAMES) return true; // PIN lock disabled in config
        try {
            MessageDigest md  = MessageDigest.getInstance("SHA-256");
            byte[]        dig = md.digest(inputPin.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb  = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString().equals(LOCK_REQUEST_PIN_PASS_HASH);
        } catch (NoSuchAlgorithmException e) {
            LOG.severe("[bloxbox] SHA-256 not available: " + e.getMessage());
            return false;
        }
    }

    /**
     * Kill any running Sober process — mirrors Python terminateSober().
     * Uses pkill on Linux. Safe to call even if Sober isn't running.
     */
    static void terminateSober() {
        try {
            Process result = Runtime.getRuntime().exec(new String[]{"pkill", "sober"});
            int code = result.waitFor();
            LOG.info("[bloxbox] pkill exit code: " + code);
        } catch (Exception e) {
            LOG.warning("[bloxbox] terminateSober failed: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // THUMBNAIL FETCHING
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Fetch the thumbnail image URL from the Roblox gameicons endpoint.
     * No universe ID lookup needed — place ID is sufficient.
     * Returns the CDN image URL string, or null on failure.
     */
    static String fetchThumbnailUrl(String placeId) {
        String url = String.format(THUMBNAIL_API, placeId);
        try {
            HttpRequest  req  = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            // Parse: {"data":[{"imageUrl":"https://..."}]}
            String body = resp.body();
            int    idx  = body.indexOf("\"imageUrl\"");
            if (idx < 0) return null;
            int s = body.indexOf('"', idx + 10) + 1;
            int e = body.indexOf('"', s);
            return body.substring(s, e);
        } catch (Exception e) {
            LOG.severe("[launcher] Thumbnail URL fetch failed for place " + placeId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetch the game name from Roblox using the economy assets API.
     * Returns the game name string, or null on failure.
     */
    static String fetchGameName(String placeId) {
        String url = "https://economy.roblox.com/v2/assets/" + placeId + "/details";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            // Parse: {"Name":"..."}
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
     * Full pipeline: place ID → thumbnail URL → BufferedImage.
     *
     * Uses a disk cache to avoid re-fetching on every app launch.
     * Cache lives at: ~/.cache/bloxbox_launcher/thumbnails/<place_id>.png
     *
     * Returns a BufferedImage, or null if anything fails.
     * Mirrors Python fetch_thumbnail_image() — no Pillow needed, uses javax.imageio.
     */
    static BufferedImage fetchThumbnailImage(String placeId) {
        // ── Check disk cache first to avoid unnecessary API calls ────────────
        Path cacheFile = CACHE_DIR.resolve(placeId + ".png");

        if (Files.exists(cacheFile)) {
            try {
                return ImageIO.read(cacheFile.toFile());
            } catch (Exception e) {
                LOG.severe("[launcher] Cache read failed for " + placeId + ": " + e.getMessage());
                try { Files.deleteIfExists(cacheFile); } catch (IOException ignored) {} // Purge corrupt cache file
            }
        }

        // ── Cache miss — fetch directly using place ID ────────────────────────
        String thumbUrl = fetchThumbnailUrl(placeId);
        if (thumbUrl == null) return null;

        try {
            // Download the raw PNG bytes
            HttpRequest req = HttpRequest.newBuilder(URI.create(thumbUrl))
                .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            byte[] imgData = resp.body();

            // Write to cache for next time
            Files.write(cacheFile, imgData);

            return ImageIO.read(new ByteArrayInputStream(imgData));
        } catch (Exception e) {
            LOG.severe("[launcher] Thumbnail download failed for place " + placeId + ": " + e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GAME LAUNCHING
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Launch a Roblox game directly, bypassing the Roblox homepage entirely.
     *
     * Launch strategy (tried in order):
     *   1. Flatpak Sober with full roblox:// URI — confirmed working on Linux Mint 22.3
     *   2. xdg-open roblox:// URI — fallback if Sober registered the URI handler
     *   3. Friendly error dialog with install instructions
     *
     * Sober (org.vinegarhq.Sober) is the community Linux Roblox client.
     * Vinegar (org.vinegarhq.Vinegar) does NOT support direct place launching.
     *
     * Runs Sober in a background thread so the UI stays responsive.
     */
    static void launchGame(String placeId, String gameName, JFrame parentFrame) {
        placeId    = placeId.strip();
        String uri = "roblox://experiences/start?placeId=" + placeId;
        LOG.info("[launcher] Launching '" + gameName + "' → " + uri);

        final String finalPlaceId = placeId;
        final String finalUri     = uri;

        // Background thread — keeps UI responsive while Sober loads
        new Thread(() -> {
            // ── Strategy 1: Flatpak Sober ─────────────────────────────────────
            // Pass the full roblox:// URI — bare place ID is silently ignored by Sober.
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "flatpak", "run", "org.vinegarhq.Sober", finalUri
                );
                pb.redirectErrorStream(true); // Merge stdout+stderr for log monitoring
                Process proc = pb.start();

                // Monitor Sober's log in a daemon thread for error patterns
                Thread monitor = new Thread(() -> monitorSoberLog(proc, gameName, parentFrame));
                monitor.setDaemon(true);
                monitor.start();
                return; // Handed off to Sober — done
            } catch (IOException e) {
                LOG.severe("[launcher] flatpak not found, falling back to xdg-open...");
            }

            // ── Strategy 2: xdg-open roblox:// URI ───────────────────────────
            // Works if Sober registered the roblox:// protocol handler during install
            try {
                new ProcessBuilder("xdg-open", finalUri).start();
                return;
            } catch (IOException ignored) {}

            // ── Strategy 3: Nothing worked ────────────────────────────────────
            SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(parentFrame,
                    "Could not launch '" + gameName + "'.\n\n" +
                    "Make sure Sober is installed:\n" +
                    "  flatpak install flathub org.vinegarhq.Sober",
                    "Launch Failed", JOptionPane.ERROR_MESSAGE)
            );
        }, "sober-launch").start();
    }

    /**
     * Background thread: reads Sober's log line by line watching for known error patterns.
     * On error: kills Sober, shows a friendly popup on the EDT.
     * Mirrors Python _monitor_sober_log().
     */
    static void monitorSoberLog(Process proc, String gameName, JFrame parentFrame) {
        // Error patterns map: log substring → {dialog title, user-friendly message}
        Map<String, String[]> ERROR_PATTERNS = new LinkedHashMap<>();
        ERROR_PATTERNS.put(
            "App not yet initialized, returning from game",
            new String[]{
                "Login / Session Error",
                "Roblox kicked back to the home screen before the game loaded.\n\n" +
                "Fix: Open Sober manually, log in again, then try Bloxbox."
            });
        ERROR_PATTERNS.put(
            "HTTP error code:`nil`",
            new String[]{
                "Network / Auth Error",
                "Roblox reported a network or authentication error.\n\n" +
                "Check your internet connection and try again."
            });
        ERROR_PATTERNS.put(
            "SessionReporterState_GameExitRequested",
            new String[]{
                "Kicked by Server",
                "The Roblox server ended the session before the game started.\n\n" +
                "The server may be full or restarting — try again shortly."
            });

        // Watch patterns — logged at debug level, not shown to user
        Map<String, String> WATCH_PATTERNS = new LinkedHashMap<>();
        WATCH_PATTERNS.put("524",    "Error 524 — Server Timeout");
        WATCH_PATTERNS.put("server", "The Roblox game server didn't respond in time.");
        WATCH_PATTERNS.put("Wait",   "This is a temporary Roblox issue — wait and try again.");

        String[] detectedError = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {

                // Optional raw game log output — mirrors Python --game-log-output flag
                if (GAME_LOG_OUTPUT) LOG.fine(line);

                // Debug-level watch pattern logging
                if (DEBUG_MODE) {
                    for (Map.Entry<String, String> watch : WATCH_PATTERNS.entrySet()) {
                        if (line.contains(watch.getKey())) {
                            LOG.severe("[bloxbox] Watch Error detected: " + watch.getKey());
                        }
                    }
                }

                // Check for hard error patterns
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
            final String[] error = detectedError;
            terminateSober();
            // Show error dialog on the EDT — never touch Swing from background threads
            SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(parentFrame,
                    error[1],
                    "⚠️  " + error[0] + " — " + gameName,
                    JOptionPane.ERROR_MESSAGE)
            );
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MINIMAL JSON HELPERS (no external dep)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Parse {"games":[{"name":"...","place_id":"...","description":"..."},...]}
     * Returns list of maps. Basic parser — sufficient for controlled config format.
     */
    static List<Map<String, String>> parseGamesJson(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        // Find the "games" array
        int arrStart = json.indexOf("[");
        int arrEnd   = json.lastIndexOf("]");
        if (arrStart < 0 || arrEnd < 0) return result;
        String arr = json.substring(arrStart + 1, arrEnd);
        // Split on object boundaries — find each {...} block
        for (String obj : splitObjects(arr)) {
            Map<String, String> m = parseStringMap(obj);
            if (!m.isEmpty()) result.add(m);
        }
        return result;
    }

    /** Parse {"requests":[...]} — same structure as games but different key. */
    @SuppressWarnings("unchecked")
    static List<Map<String, String>> parseRequestsJson(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        int arrStart = json.indexOf("[");
        int arrEnd   = json.lastIndexOf("]");
        if (arrStart < 0 || arrEnd < 0) return result;
        String arr = json.substring(arrStart + 1, arrEnd);
        for (String obj : splitObjects(arr)) {
            Map<String, String> m = parseStringMap(obj);
            if (!m.isEmpty()) result.add(m);
        }
        return result;
    }

    /** Split a JSON array body into individual object strings. */
    static List<String> splitObjects(String arrayBody) {
        List<String> objs  = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0 && start >= 0) { objs.add(arrayBody.substring(start, i + 1)); start = -1; } }
        }
        return objs;
    }

    /** Parse a flat JSON object {"key":"value",...} into a Map<String,String>. */
    static Map<String, String> parseStringMap(String obj) {
        Map<String, String> m  = new LinkedHashMap<>();
        Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
        Matcher mt = p.matcher(obj);
        while (mt.find()) m.put(mt.group(1), mt.group(2));
        return m;
    }

    /** Serialize list of request maps back to JSON string. */
    static String buildRequestsJson(List<Map<String, String>> requests) {
        StringBuilder sb = new StringBuilder("{\n  \"requests\": [\n");
        for (int i = 0; i < requests.size(); i++) {
            sb.append("    {");
            Map<String, String> r = requests.get(i);
            List<String> keys = new ArrayList<>(r.keySet());
            for (int j = 0; j < keys.size(); j++) {
                String k = keys.get(j), v = r.get(k);
                sb.append("\"").append(k).append("\": \"").append(v.replace("\"", "\\\"")).append("\"");
                if (j < keys.size() - 1) sb.append(", ");
            }
            sb.append("}");
            if (i < requests.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}");
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GAME CARD COMPONENT
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * A single game tile in the launcher grid.
     * Shows game thumbnail (loaded async in background), name, and Play button.
     * Hover highlights the card background.
     * Mirrors Python GameCard(tk.Frame).
     */
    static class GameCard extends JPanel {

        private final Map<String, String> game;
        private       JLabel             thumbLabel; // Placeholder → replaced with image async
        private final JFrame             parentFrame;

        GameCard(Map<String, String> game, JFrame parentFrame) {
            this.game        = game;
            this.parentFrame = parentFrame;

            setPreferredSize(new Dimension(CARD_WIDTH, CARD_HEIGHT));
            setBackground(CARD_COLOR);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(10, 8, 8, 8));

            buildUI();
            bindHover();

            // Kick off thumbnail fetch in background — keeps UI snappy
            // Mirrors Python threading.Thread(target=self._load_thumbnail, daemon=True).start()
            CompletableFuture.runAsync(() -> loadThumbnail());
        }

        private void buildUI() {
            // Thumbnail placeholder shown while image is loading
            thumbLabel = new JLabel("⏳", SwingConstants.CENTER);
            thumbLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 28));
            thumbLabel.setForeground(SUBTEXT_COLOR);
            thumbLabel.setBackground(CARD_COLOR);
            thumbLabel.setOpaque(true);
            thumbLabel.setPreferredSize(new Dimension(THUMB_SIZE, THUMB_SIZE));
            thumbLabel.setMaximumSize(new Dimension(THUMB_SIZE, THUMB_SIZE));
            thumbLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            add(thumbLabel);
            add(Box.createVerticalStrut(4));

            // Game name — truncated with HTML wrapping if too long
            String displayName = game.getOrDefault("name", "Unknown");
            JLabel nameLabel   = new JLabel(
                "<html><div style='text-align:center;width:" + (CARD_WIDTH - 16) + "px'>" +
                displayName + "</div></html>",
                SwingConstants.CENTER
            );
            nameLabel.setFont(FONT_CARD);
            nameLabel.setForeground(TEXT_COLOR);
            nameLabel.setBackground(CARD_COLOR);
            nameLabel.setOpaque(true);
            nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            add(nameLabel);
            add(Box.createVerticalStrut(2));

            // Play button — triggers direct game launch via Sober
            JButton playBtn = makeButton("▶  Play", ACCENT_COLOR);
            playBtn.addActionListener(e -> onLaunch());
            playBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            add(Box.createVerticalStrut(8));
            add(playBtn);
        }

        /** Load thumbnail image from cache or Roblox CDN in background. */
        private void loadThumbnail() {
            String placeId = game.getOrDefault("place_id", "");
            LOG.fine("[bloxbox] Loading thumbnail for " + placeId);

            BufferedImage img = fetchThumbnailImage(placeId);
            LOG.fine("[bloxbox] fetchThumbnailImage returned: " + img);

            if (img == null) {
                // No thumbnail available — swap spinner for game controller emoji
                SwingUtilities.invokeLater(() -> setPlaceholder("🎮"));
                LOG.severe("No thumbnail available for " + placeId);
                return;
            }

            // Resize to fit the card neatly — mirrors PIL Image.resize(LANCZOS)
            Image scaled = img.getScaledInstance(THUMB_SIZE, THUMB_SIZE, Image.SCALE_SMOOTH);
            LOG.fine("[bloxbox] resizing thumbnail for " + placeId);

            // Schedule the UI update on the EDT — never touch Swing from background threads
            SwingUtilities.invokeLater(() -> {
                thumbLabel.setIcon(new ImageIcon(scaled));
                thumbLabel.setText("");
                LOG.fine("[bloxbox] thumbnail set ok for " + placeId);
            });
        }

        /** Replace the spinner with a fallback emoji (API failed or no image). */
        private void setPlaceholder(String emoji) {
            thumbLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 32));
            thumbLabel.setText(emoji);
            thumbLabel.setIcon(null);
        }

        /** Play button click handler — kills existing Sober then launches the game. */
        private void onLaunch() {
            // Kill any already-running Sober instance before launching
            try {
                Process check = Runtime.getRuntime().exec(new String[]{"pgrep", "sober"});
                if (check.waitFor() == 0) terminateSober(); // Sober is running — kill it
            } catch (Exception ignored) {}

            launchGame(
                game.getOrDefault("place_id", ""),
                game.getOrDefault("name", "Game"),
                parentFrame
            );
        }

        /**
         * Highlight card on mouse-over with a slightly lighter background.
         * Mirrors Python _bind_hover() — applies to panel and all child components.
         */
        private void bindHover() {
            MouseAdapter hover = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { setCardBg(HOVER_COLOR); }
                @Override public void mouseExited (MouseEvent e) { setCardBg(CARD_COLOR);  }
            };

            // Apply to this panel and all direct children (labels, button)
            addMouseListener(hover);
            for (Component c : getComponents()) c.addMouseListener(hover);
        }

        /** Set background on this card and all opaque children simultaneously. */
        private void setCardBg(Color bg) {
            setBackground(bg);
            for (Component c : getComponents()) {
                if (c.isOpaque()) c.setBackground(bg);
            }
            repaint();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PIN DIALOG
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Modal PIN entry dialog shown before the request flow starts.
     * Verifies the entered PIN against the stored SHA-256 hash via verifyPin().
     * Returns verified=true only if the correct PIN was entered.
     * Mirrors Python PinDialog(tk.Toplevel).
     */
    static class PinDialog extends JDialog {

        boolean verified = false; // Set to true only on correct PIN

        PinDialog(JFrame parent) {
            super(parent, "Enter Passcode", true); // true = modal
            setResizable(false);
            getContentPane().setBackground(BG_COLOR);

            JPasswordField pinField  = new JPasswordField(12);
            JLabel         errorLbl  = new JLabel(" "); // Reserve space for error message

            pinField.setFont(new Font("Georgia", Font.PLAIN, 18));
            pinField.setBackground(Color.decode("#252540"));
            pinField.setForeground(TEXT_COLOR);
            pinField.setCaretColor(TEXT_COLOR);
            pinField.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            pinField.setEchoChar('●'); // Mask PIN characters

            errorLbl.setFont(FONT_SMALL);
            errorLbl.setForeground(ACCENT_COLOR);

            // ── Layout ────────────────────────────────────────────────────────
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBackground(BG_COLOR);
            panel.setBorder(BorderFactory.createEmptyBorder(24, 30, 20, 30));

            JLabel heading = new JLabel("🔒  Enter Passcode to Request a Game");
            heading.setFont(new Font("Georgia", Font.BOLD, 14));
            heading.setForeground(TEXT_COLOR);
            heading.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(heading);
            panel.add(Box.createVerticalStrut(6));

            JLabel sub = new JLabel("Ask a parent if you don't know the Passcode.");
            sub.setFont(FONT_SMALL);
            sub.setForeground(SUBTEXT_COLOR);
            sub.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(sub);
            panel.add(Box.createVerticalStrut(14));

            pinField.setAlignmentX(Component.CENTER_ALIGNMENT);
            pinField.setMaximumSize(new Dimension(200, 40));
            panel.add(pinField);
            panel.add(Box.createVerticalStrut(6));

            errorLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(errorLbl);
            panel.add(Box.createVerticalStrut(12));

            // Buttons
            JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
            btnRow.setBackground(BG_COLOR);

            JButton confirmBtn = makeButton("Confirm", REQUEST_COLOR);
            JButton cancelBtn  = makeButton("Cancel",  Color.decode("#333333"));

            btnRow.add(confirmBtn);
            btnRow.add(cancelBtn);
            panel.add(btnRow);

            add(panel);

            // ── Event handlers ────────────────────────────────────────────────
            Runnable onSubmit = () -> {
                String pin = new String(pinField.getPassword()).strip();
                if (pin.isEmpty()) {
                    errorLbl.setText("⚠️  Please enter a PIN.");
                    return;
                }
                if (verifyPin(pin)) {
                    verified = true; // Correct — set verified and close
                    dispose();
                } else {
                    // Wrong — clear entry, show error, let them try again
                    pinField.setText("");
                    errorLbl.setText("❌  Incorrect PIN. Try again.");
                    pinField.requestFocus();
                }
            };

            confirmBtn.addActionListener(e -> onSubmit.run());
            cancelBtn.addActionListener(e -> dispose());

            // Bind Enter key to submit — mirrors Python self.bind("<Return>", ...)
            pinField.addActionListener(e -> onSubmit.run());

            pack();
            setLocationRelativeTo(parent);

            // Focus the PIN entry immediately after dialog appears
            SwingUtilities.invokeLater(pinField::requestFocusInWindow);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REQUEST DIALOG — FALLBACK (no embedded browser)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Fallback request dialog for when no embedded browser is available.
     * Opens roblox.com/charts in the system browser (Firefox or xdg-open),
     * then child enters the place ID from the URL bar manually.
     * Mirrors Python RequestDialogFallback(tk.Toplevel).
     *
     * Note: A full embedded-browser dialog (equivalent to Python's RequestDialog
     * using pywebview) would require JavaFX WebView or JCEF. For a pure-Swing
     * build this fallback matches the Python RequestDialogFallback behaviour exactly.
     */
    static class RequestDialogFallback extends JDialog {

        private String fetchedPlaceId  = null;
        private String fetchedGameName = null;

        private JTextField idEntry;
        private JLabel     thumbLabel;
        private JLabel     nameLabel;
        private JLabel     statusLabel;
        private JTextField noteEntry;
        private JButton    submitBtn;

        RequestDialogFallback(JFrame parent) {
            super(parent, "Request a Game", true); // true = modal
            setResizable(false);
            getContentPane().setBackground(BG_COLOR);

            buildUI();
            pack();
            setLocationRelativeTo(parent);
        }

        private void buildUI() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBackground(BG_COLOR);
            panel.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

            // ── Title ─────────────────────────────────────────────────────────
            JLabel title = new JLabel("Request a New Game");
            title.setFont(new Font("Georgia", Font.BOLD, 16));
            title.setForeground(TEXT_COLOR);
            title.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(title);
            panel.add(Box.createVerticalStrut(4));

            // ── Instructions ──────────────────────────────────────────────────
            JLabel instructions = new JLabel(
                "<html><div style='text-align:center'>" +
                "Find a game in the browser that just opened.<br>" +
                "Copy the number from the URL bar:<br>" +
                "roblox.com/games/185655149/... → enter 185655149" +
                "</div></html>",
                SwingConstants.CENTER
            );
            instructions.setFont(FONT_SMALL);
            instructions.setForeground(SUBTEXT_COLOR);
            instructions.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(instructions);
            panel.add(Box.createVerticalStrut(10));

            // ── Place ID entry + look-up button ───────────────────────────────
            JPanel entryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            entryRow.setBackground(BG_COLOR);

            idEntry = new JTextField(20);
            idEntry.setFont(FONT_SMALL);
            idEntry.setBackground(Color.decode("#252540"));
            idEntry.setForeground(TEXT_COLOR);
            idEntry.setCaretColor(TEXT_COLOR);
            idEntry.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

            JButton lookupBtn = makeButton("Look Up", REQUEST_COLOR);
            lookupBtn.addActionListener(e -> onLookup());

            entryRow.add(idEntry);
            entryRow.add(Box.createHorizontalStrut(8));
            entryRow.add(lookupBtn);
            panel.add(entryRow);
            panel.add(Box.createVerticalStrut(10));

            // ── Preview area ──────────────────────────────────────────────────
            thumbLabel = new JLabel("", SwingConstants.CENTER);
            thumbLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 28));
            thumbLabel.setForeground(SUBTEXT_COLOR);
            thumbLabel.setBackground(BG_COLOR);
            thumbLabel.setOpaque(true);
            thumbLabel.setPreferredSize(new Dimension(100, 100));
            thumbLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(thumbLabel);
            panel.add(Box.createVerticalStrut(4));

            nameLabel = new JLabel("", SwingConstants.CENTER);
            nameLabel.setFont(FONT_CARD);
            nameLabel.setForeground(TEXT_COLOR);
            nameLabel.setBackground(BG_COLOR);
            nameLabel.setOpaque(true);
            nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(nameLabel);

            statusLabel = new JLabel("", SwingConstants.CENTER);
            statusLabel.setFont(FONT_SMALL);
            statusLabel.setForeground(SUBTEXT_COLOR);
            statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(statusLabel);
            panel.add(Box.createVerticalStrut(10));

            // ── Optional note ─────────────────────────────────────────────────
            JLabel noteLbl = new JLabel("Why do you want to play it? (optional):");
            noteLbl.setFont(FONT_SMALL);
            noteLbl.setForeground(TEXT_COLOR);
            panel.add(noteLbl);
            panel.add(Box.createVerticalStrut(4));

            noteEntry = new JTextField(30);
            noteEntry.setFont(FONT_SMALL);
            noteEntry.setBackground(Color.decode("#252540"));
            noteEntry.setForeground(TEXT_COLOR);
            noteEntry.setCaretColor(TEXT_COLOR);
            noteEntry.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            panel.add(noteEntry);
            panel.add(Box.createVerticalStrut(16));

            // ── Buttons — Submit disabled until lookup succeeds ───────────────
            JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
            btnRow.setBackground(BG_COLOR);

            submitBtn = makeButton("Send Request", Color.decode("#444444"));
            submitBtn.setForeground(Color.decode("#888888"));
            submitBtn.setEnabled(false); // Enabled after successful lookup

            JButton cancelBtn = makeButton("Cancel", Color.decode("#333333"));
            cancelBtn.addActionListener(e -> dispose());

            submitBtn.addActionListener(e -> onSubmit());

            btnRow.add(submitBtn);
            btnRow.add(cancelBtn);
            panel.add(btnRow);

            add(panel);
        }

        /** Validate the place ID and kick off background name+thumbnail lookup. */
        private void onLookup() {
            String placeId = idEntry.getText().strip();

            // Validate: place IDs are all digits
            if (!placeId.matches("\\d+")) {
                statusLabel.setForeground(ACCENT_COLOR);
                statusLabel.setText("⚠️  Place ID must be a number.");
                return;
            }

            statusLabel.setForeground(SUBTEXT_COLOR);
            statusLabel.setText("Looking up game...");
            nameLabel.setText("");
            thumbLabel.setIcon(null);
            thumbLabel.setText("⏳");
            submitBtn.setEnabled(false);
            submitBtn.setBackground(Color.decode("#444444"));
            submitBtn.setForeground(Color.decode("#888888"));

            // Background lookup — keeps the dialog responsive
            CompletableFuture.runAsync(() -> {
                String thumbUrl  = fetchThumbnailUrl(placeId);
                String gameName  = fetchGameName(placeId);
                SwingUtilities.invokeLater(() -> showPreview(placeId, gameName, thumbUrl));
            });
        }

        /** Update preview area and enable Submit if lookup succeeded. */
        private void showPreview(String placeId, String gameName, String thumbUrl) {
            if (gameName == null && thumbUrl == null) {
                statusLabel.setForeground(ACCENT_COLOR);
                statusLabel.setText("⚠️  Game not found. Check the Place ID.");
                thumbLabel.setText("❓");
                return;
            }

            fetchedPlaceId  = placeId;
            fetchedGameName = gameName != null ? gameName : "Game " + placeId;
            nameLabel.setText(fetchedGameName);

            if (thumbUrl != null) {
                // Load thumbnail in background — keep UI responsive
                final String url = thumbUrl;
                CompletableFuture.runAsync(() -> {
                    try {
                        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(8)).GET().build();
                        byte[] data = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray()).body();
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                        if (img != null) {
                            Image scaled = img.getScaledInstance(100, 100, Image.SCALE_SMOOTH);
                            SwingUtilities.invokeLater(() -> {
                                thumbLabel.setIcon(new ImageIcon(scaled));
                                thumbLabel.setText("");
                            });
                        }
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> { thumbLabel.setIcon(null); thumbLabel.setText("🎮"); });
                    }
                });
            } else {
                thumbLabel.setIcon(null);
                thumbLabel.setText("🎮");
            }

            statusLabel.setForeground(Color.decode("#4caf50"));
            statusLabel.setText("✅  Game found!");

            // Enable submit now that lookup succeeded
            submitBtn.setEnabled(true);
            submitBtn.setBackground(REQUEST_COLOR);
            submitBtn.setForeground(Color.WHITE);
        }

        /** Save the request and close the dialog. */
        private void onSubmit() {
            if (fetchedPlaceId == null) return;

            boolean ok = saveRequest(
                fetchedPlaceId,
                fetchedGameName,
                noteEntry.getText().strip(),
                ""
            );
            dispose();

            if (ok) {
                JOptionPane.showMessageDialog(getParent(),
                    "'" + fetchedGameName + "' has been requested.\n" +
                    "Ask a parent to review it!",
                    "Request Sent! 🎮", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(getParent(),
                    "Permission error saving your request.\n" +
                    "Ask a parent to check the requests file.",
                    "Could Not Save", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN LAUNCHER WINDOW
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Main launcher window.
     * Header with title + Request button, then a scrollable grid of game cards.
     * Mirrors Python LauncherApp(tk.Tk).
     */
    static class LauncherApp extends JFrame {

        LauncherApp() {
            super(WINDOW_TITLE);
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            getContentPane().setBackground(BG_COLOR);
            setLayout(new BorderLayout());

            buildUI();
        }

        private void buildUI() {
            // ── Header bar ────────────────────────────────────────────────────
            JPanel header = new JPanel(new BorderLayout());
            header.setBackground(BG_COLOR);
            header.setBorder(BorderFactory.createEmptyBorder(24, 30, 8, 30));

            // Left side: title + subtitle
            JPanel titleGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 6));
            titleGroup.setBackground(BG_COLOR);

            JLabel titleLbl = new JLabel("🎮 BloxBox Game Launcher");
            titleLbl.setFont(FONT_TITLE);
            titleLbl.setForeground(TEXT_COLOR);
            titleGroup.add(titleLbl);

            JLabel subLbl = new JLabel("Approved games only");
            subLbl.setFont(FONT_SMALL);
            subLbl.setForeground(SUBTEXT_COLOR);
            titleGroup.add(subLbl);

            // Right side: request button
            JButton requestBtn = makeButton("＋  Request a Game", REQUEST_COLOR);
            requestBtn.addActionListener(e -> openRequestDialog());

            header.add(titleGroup,  BorderLayout.WEST);
            header.add(requestBtn,  BorderLayout.EAST);
            add(header, BorderLayout.NORTH);

            // ── Scrollable game grid ──────────────────────────────────────────
            JPanel gridHolder = new JPanel();
            gridHolder.setBackground(BG_COLOR);
            populateGrid(gridHolder);

            JScrollPane scroll = new JScrollPane(gridHolder);
            scroll.setBackground(BG_COLOR);
            scroll.getViewport().setBackground(BG_COLOR);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(16); // Smooth scrolling

            add(scroll, BorderLayout.CENTER);
        }

        /**
         * Load approved games and render a GameCard for each.
         * Equivalent to Python _populate_grid() — uses a FlowLayout grid.
         */
        private void populateGrid(JPanel container) {
            List<Map<String, String>> games = loadConfig();
            container.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

            if (games.isEmpty()) {
                // Empty state — point child to the request button
                container.setLayout(new BorderLayout());
                JLabel empty = new JLabel(
                    "<html><div style='text-align:center'>" +
                    "No games approved yet.<br>Use '＋ Request a Game' above!</div></html>",
                    SwingConstants.CENTER
                );
                empty.setFont(new Font("Georgia", Font.PLAIN, 16));
                empty.setForeground(SUBTEXT_COLOR);
                empty.setBorder(BorderFactory.createEmptyBorder(80, 40, 80, 40));
                container.add(empty, BorderLayout.CENTER);
                return;
            }

            // Render cards in a COLS-wide grid — mirrors Python grid(row, column)
            container.setLayout(new GridLayout(0, COLS, 12, 12)); // 0 rows = auto-expand
            for (Map<String, String> game : games) {
                container.add(new GameCard(game, this));
            }
        }

        /**
         * Open the game request dialog.
         * First verifies the PIN — only proceeds if correct.
         * Opens system browser (Firefox or xdg-open) then shows the fallback dialog.
         * Note: the full embedded-browser experience (Python RequestDialog via pywebview)
         * would require JavaFX WebView or JCEF — this port uses the fallback by default.
         */
        private void openRequestDialog() {
            // Gate on PIN verification before showing request dialog
            PinDialog pin = new PinDialog(this);
            pin.setVisible(true);
            if (!pin.verified) return; // Cancelled or wrong PIN — do nothing

            // Open the system browser so the child can browse Roblox charts
            openBrowser(ROBLOX_GAME_SEARCH_URL);

            // Show the fallback place-ID entry dialog
            RequestDialogFallback dialog = new RequestDialogFallback(this);
            dialog.setVisible(true);
        }

        /**
         * Open a URL in the system browser.
         * Tries Firefox first, then xdg-open (same order as Python fallback chain).
         */
        private void openBrowser(String url) {
            // Strategy 1: Firefox
            try {
                new ProcessBuilder("firefox", url)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
                return;
            } catch (IOException ignored) {}

            // Strategy 2: xdg-open (desktop default browser)
            try {
                new ProcessBuilder("xdg-open", url)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            } catch (IOException ignored) {} // No browser available — just show the dialog
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI UTILITY HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Create a styled flat button matching the BloxBox dark theme.
     * Mirrors the common tk.Button(..., relief="flat", cursor="hand2") pattern.
     */
    static JButton makeButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BTN);
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Darken on hover — mirrors Python activebackground
        Color hover = bg.darker();
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { if (btn.isEnabled()) btn.setBackground(hover); }
            @Override public void mouseExited (MouseEvent e) { if (btn.isEnabled()) btn.setBackground(bg);    }
        });

        return btn;
    }
}