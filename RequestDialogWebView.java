/*
 * RequestDialogWebView.java — Embedded Roblox browser for game requests.
 *
 * Architecture:
 *   - Outer shell is a Swing JDialog containing a JFXPanel (the Swing/JavaFX bridge).
 *   - Inside the JFXPanel lives a JavaFX Scene with a BorderPane:
 *       [  WebView fills centre  ]
 *       [  SlidePanel at bottom  ]  <- animated, only visible on game pages
 *
 * Reopen fix:
 *   Each time the dialog is opened a brand-new JFXPanel + WebView is created.
 *   The old scene is discarded. This avoids the "blue screen on reopen" caused
 *   by trying to reuse a disposed JFXPanel.
 *
 * Thumbnail fix:
 *   JavaFX WebKit's locationProperty fires for EVERY resource the engine
 *   touches — page navigations, CDN images, XHR, fonts. Any domain-blocking
 *   listener attached to locationProperty kills image loads mid-flight.
 *   Solution: NO domain blocker on locationProperty at all. Instead we use
 *   the load Worker's SUCCEEDED state to check only completed page navigations,
 *   and JavaScript injection (via executeScript after load) to intercept
 *   outbound link clicks before they navigate away.
 *
 * Navigation security:
 *   After each page finishes loading we inject a small JS snippet that
 *   intercepts all <a> clicks. If the href points outside roblox.com the
 *   click is cancelled and window.alert() is called with a special
 *   "BLOXBOX_BLOCK:<url>" token. WebEngine.setOnAlert() catches that token
 *   and redirects back to charts — without touching any resource loads.
 *
 * Request flow (no dialog close required):
 *   1. Child navigates to a game page -> slide panel rises.
 *   2. Child clicks "+ Request This Game".
 *   3. Brief confirmation shown for 2 seconds.
 *   4. Panel resets — child can keep browsing and request more games.
 *
 * Threading rules:
 *   - All JavaFX node mutations must happen on the JFXAT (Platform.runLater).
 *   - All Swing mutations must happen on the EDT (SwingUtilities.invokeLater).
 *
 * Requires: javafx.web, javafx.swing, jdk.jsobject modules on module path.
 */

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;

public class RequestDialogWebView extends JDialog {

    // ── Slide-panel height constants ──────────────────────────────────────────
    private static final double PANEL_HIDDEN_H  = 0.0;
    private static final double PANEL_VISIBLE_H = 130.0;

    // ── Game page URL pattern ─────────────────────────────────────────────────
    // Matches: roblox.com/games/185655149/... and captures the place ID
    private static final Pattern GAME_PAGE = Pattern.compile("/games/(\\d+)");

    // ── JS injected after every page load ────────────────────────────────────
    // Intercepts all <a> clicks. If the destination is outside roblox.com,
    // cancels the click and fires window.alert("BLOXBOX_BLOCK:<url>") which
    // WebEngine.setOnAlert() catches on the Java side.
    // This approach does NOT touch resource loads (images, XHR, fonts) —
    // only actual user-initiated link navigations are intercepted.
    private static final String INTERCEPT_JS =
        "(function() {" +
        "  document.addEventListener('click', function(e) {" +
        "    var a = e.target.closest('a');" +
        "    if (!a || !a.href) return;" +
        "    var url = a.href;" +
        // Allow roblox.com and all its subdomains
        "    if (url.match(/^https?:\\/\\/([a-z0-9-]+\\.)*roblox\\.com/i)) return;" +
        // Allow internal SPA navigation (hash, javascript:, blob:, data:)
        "    if (url.startsWith('#') || url.startsWith('javascript:') ||" +
        "        url.startsWith('blob:') || url.startsWith('data:')) return;" +
        // Block and signal Java via alert
        "    e.preventDefault();" +
        "    e.stopPropagation();" +
        "    window.alert('BLOXBOX_BLOCK:' + url);" +
        "  }, true);" + // capture phase — fires before Roblox's own listeners
        "})();";

    // ── Instance state ────────────────────────────────────────────────────────
    private WebEngine engine;          // JavaFX WebEngine
    private VBox      slidePanel;      // Animated request panel at the bottom
    private Label     panelGameLabel;  // Game name shown in the slide panel
    private TextField noteField;       // Optional note before requesting
    private Button    requestBtn;      // "+ Request This Game"
    private Timeline  slideIn;         // Slide-up animation
    private Timeline  slideOut;        // Slide-down animation
    private String    currentPlaceId;  // Place ID of the current game page
    private String    currentGameName; // Fetched async from Roblox API

    // ── Back/forward history stack ────────────────────────────────────────────
    // WebEngine has no built-in back() method accessible without reflection.
    // We maintain our own stack of successfully loaded page URLs.
    private final java.util.Deque<String> history = new java.util.ArrayDeque<>();

    // ── Constructor ───────────────────────────────────────────────────────────
    RequestDialogWebView(JFrame parent) {
        super(parent, "BloxBox — Browse & Request Games", false); // non-modal
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setPreferredSize(new Dimension(1100, 750));
        getContentPane().setBackground(Color.decode("#0f0f1a"));
        setLayout(new BorderLayout());

        // ── Fresh JFXPanel every time the dialog opens ────────────────────────
        // A JFXPanel cannot be reused after its parent dialog is disposed.
        // Creating a new one each time the dialog is constructed avoids the
        // "blue screen on reopen" issue caused by attaching a scene to a
        // stale/disposed AWT component.
        JFXPanel jfxPanel = new JFXPanel();
        add(jfxPanel, BorderLayout.CENTER);

        // ── Top bar (Swing layer) — back button + title + done button ─────────
        JButton backBtn = launcher.makeButton("◀  Back", Color.decode("#252540"));
        backBtn.addActionListener(e -> goBack());

        JButton doneBtn = launcher.makeButton("✕  Done", Color.decode("#333333"));
        doneBtn.addActionListener(e -> dispose());

        javax.swing.JPanel topBar = new javax.swing.JPanel(new BorderLayout());
        topBar.setBackground(Color.decode("#13132a"));
        topBar.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 10, 4, 10));

        javax.swing.JLabel titleLbl = new javax.swing.JLabel(
            "Browse Roblox — request as many games as you like!"
        );
        titleLbl.setFont(launcher.FONT_SMALL);
        titleLbl.setForeground(launcher.SUBTEXT_COLOR);

        // Left side: back button + title
        javax.swing.JPanel leftGroup = new javax.swing.JPanel(
            new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)
        );
        leftGroup.setBackground(Color.decode("#13132a"));
        leftGroup.add(backBtn);
        leftGroup.add(titleLbl);

        topBar.add(leftGroup, BorderLayout.WEST);
        topBar.add(doneBtn,   BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        pack();
        setLocationRelativeTo(parent);

        // ── CRITICAL: setVisible BEFORE Platform.runLater ────────────────────
        // The JFXPanel must be realized (on-screen) before JavaFX attaches a
        // Scene to it. If not yet visible, JavaFX gets a zero-size component
        // and never triggers a repaint — permanently blank WebView.
        setVisible(true);

        // Build the JavaFX scene now that the JFXPanel is on screen
        Platform.runLater(() -> initJavaFXScene(jfxPanel));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JAVAFX SCENE SETUP — runs on the JFXAT
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Build the JavaFX scene: WebView + slide panel + animations + listeners.
     * Initialization order is strict — see inline comments.
     */
    private void initJavaFXScene(JFXPanel jfxPanel) {
        // ── WebView ───────────────────────────────────────────────────────────
        WebView webView = new WebView();
        engine = webView.getEngine();

        // Spoof Chrome UA — JavaFX WebKit's real UA causes Roblox to serve
        // a degraded page or blank thumbnails
        engine.setUserAgent(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        );

        // ── Slide panel + animations — built BEFORE any load() call ──────────
        // engine.load() fires locationProperty synchronously. If slideIn/slideOut
        // don't exist yet when the game-page listener fires, we get NPE.
        slidePanel = buildSlidePanel();
        slidePanel.setPrefHeight(PANEL_HIDDEN_H);
        slidePanel.setMaxHeight(PANEL_HIDDEN_H);
        slidePanel.setOpacity(0.0);

        BorderPane root = new BorderPane();
        root.setCenter(webView);
        root.setBottom(slidePanel);
        root.setStyle("-fx-background-color: #0f0f1a;");

        slideIn = new Timeline(new KeyFrame(Duration.millis(280),
            new KeyValue(slidePanel.prefHeightProperty(), PANEL_VISIBLE_H),
            new KeyValue(slidePanel.maxHeightProperty(),  PANEL_VISIBLE_H),
            new KeyValue(slidePanel.opacityProperty(),    1.0)));

        slideOut = new Timeline(new KeyFrame(Duration.millis(200),
            new KeyValue(slidePanel.prefHeightProperty(), PANEL_HIDDEN_H),
            new KeyValue(slidePanel.maxHeightProperty(),  PANEL_HIDDEN_H),
            new KeyValue(slidePanel.opacityProperty(),    0.0)));

        // ── Load worker listener — fires on page navigation state changes ─────
        // SUCCEEDED = a full top-level page loaded successfully.
        // We use this to:
        //   1. Track history for the back button
        //   2. Inject the link-click interceptor JS
        //   3. Show/hide the slide panel based on whether it's a game page
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState != Worker.State.SUCCEEDED) return;

            String url = engine.getLocation();
            if (url == null || url.isEmpty()) return;

            launcher.LOG.fine("[WebView] Page loaded: " + url);

            // Push to history stack for back button (skip duplicates)
            if (history.isEmpty() || !url.equals(history.peek())) {
                history.push(url);
            }

            // Inject link-click interceptor so outbound links are blocked
            // without touching any resource loads (images, XHR, fonts)
            try {
                engine.executeScript(INTERCEPT_JS);
                launcher.LOG.fine("[WebView] Injected link interceptor");
            } catch (Exception e) {
                launcher.LOG.warning("[WebView] JS inject failed: " + e.getMessage());
            }

            // Show/hide slide panel based on whether this is a game page
            Matcher m = GAME_PAGE.matcher(url);
            if (m.find()) {
                onGamePageDetected(m.group(1));
            } else {
                hidePanelAnimated();
            }
        });

        // ── Alert handler — receives BLOXBOX_BLOCK signals from injected JS ───
        // When the interceptor catches an outbound link click it calls
        // window.alert("BLOXBOX_BLOCK:<url>"). We catch that here and redirect.
        // Normal Roblox alerts (rare) are passed through unless prefixed.
        engine.setOnAlert(event -> {
            String msg = event.getData();
            if (msg != null && msg.startsWith("BLOXBOX_BLOCK:")) {
                String blocked = msg.substring("BLOXBOX_BLOCK:".length());
                launcher.LOG.warning("[bloxbox] Blocked outbound link: " + blocked);
                // Redirect back to charts — no effect on resource loads
                engine.load(launcher.ROBLOX_GAME_SEARCH_URL);
            }
            // Swallow all alerts — don't show browser alert dialogs to the child
        });

        // ── Load starting page LAST — after all listeners exist ──────────────
        engine.load(launcher.ROBLOX_GAME_SEARCH_URL);

        // Wire scene to the JFXPanel — triggers first paint
        Scene scene = new Scene(root);
        scene.setFill(javafx.scene.paint.Color.web("#0f0f1a"));
        jfxPanel.setScene(scene);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BACK NAVIGATION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Navigate to the previous page in our history stack.
     * Called from the Swing back button — must dispatch to JFXAT.
     * If history has only one entry (the start page) the button does nothing.
     */
    private void goBack() {
        Platform.runLater(() -> {
            if (history.size() <= 1) return; // Nothing to go back to
            history.pop(); // Discard current page
            String prev = history.peek(); // Peek at previous (don't pop — keep it as current)
            if (prev != null) {
                launcher.LOG.fine("[WebView] Back -> " + prev);
                engine.load(prev);
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SLIDE PANEL CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Build the slide-up request panel.
     * Row 1: game name label
     * Row 2: "Note:" label | text field (stretchy) | Request btn | Dismiss btn
     */
    private VBox buildSlidePanel() {
        VBox panel = new VBox(8);
        panel.setStyle(
            "-fx-background-color: #1a1a2e;" +
            "-fx-border-color: #2a2a4a;" +
            "-fx-border-width: 1 0 0 0;"
        );
        panel.setPadding(new Insets(12, 20, 12, 20));
        panel.setAlignment(Pos.CENTER_LEFT);

        // Game name label — updated once async name fetch completes
        panelGameLabel = new Label("Navigate to a Roblox game page to request it");
        panelGameLabel.setStyle(
            "-fx-text-fill: #eaeaea; -fx-font-family: Georgia; -fx-font-size: 13;"
        );

        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER_LEFT);

        Label noteLbl = new Label("Note (optional):");
        noteLbl.setStyle("-fx-text-fill: #888888; -fx-font-size: 11;");

        noteField = new TextField();
        noteField.setPromptText("Why do you want to play it?");
        noteField.setStyle(
            "-fx-background-color: #252540;" +
            "-fx-text-fill: #eaeaea;" +
            "-fx-prompt-text-fill: #555577;" +
            "-fx-font-size: 11;" +
            "-fx-padding: 5 8 5 8;"
        );
        HBox.setHgrow(noteField, Priority.ALWAYS);

        requestBtn = new Button("+ Request This Game");
        applyRequestBtnStyle(requestBtn, false);
        requestBtn.setOnAction(e -> onRequestClicked());

        Button dismissBtn = new Button("x");
        dismissBtn.setStyle(
            "-fx-background-color: #333;" +
            "-fx-text-fill: #aaa;" +
            "-fx-font-size: 12;" +
            "-fx-padding: 5 10 5 10;" +
            "-fx-cursor: hand;" +
            "-fx-background-radius: 4;"
        );
        dismissBtn.setOnAction(e -> hidePanelAnimated());

        controls.getChildren().addAll(noteLbl, noteField, requestBtn, dismissBtn);
        panel.getChildren().addAll(panelGameLabel, controls);
        return panel;
    }

    /** Apply the standard blue request button style, or a muted disabled style. */
    private void applyRequestBtnStyle(Button btn, boolean disabled) {
        if (disabled) {
            btn.setStyle(
                "-fx-background-color: #1e4d6b;" +
                "-fx-text-fill: #aaaaaa;" +
                "-fx-font-family: Georgia;" +
                "-fx-font-weight: bold;" +
                "-fx-font-size: 12;" +
                "-fx-padding: 7 16 7 16;" +
                "-fx-background-radius: 4;"
            );
        } else {
            btn.setStyle(
                "-fx-background-color: #2a6496;" +
                "-fx-text-fill: white;" +
                "-fx-font-family: Georgia;" +
                "-fx-font-weight: bold;" +
                "-fx-font-size: 12;" +
                "-fx-padding: 7 16 7 16;" +
                "-fx-cursor: hand;" +
                "-fx-background-radius: 4;"
            );
            // Hover darken
            btn.setOnMouseEntered(e -> { if (!btn.isDisabled()) btn.setStyle(btn.getStyle().replace("#2a6496","#1e4d6b")); });
            btn.setOnMouseExited (e -> { if (!btn.isDisabled()) btn.setStyle(btn.getStyle().replace("#1e4d6b","#2a6496")); });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GAME PAGE DETECTION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Called on the JFXAT when a successful page load is a game page.
     * Shows the slide panel and kicks off an async game name fetch.
     */
    private void onGamePageDetected(String placeId) {
        currentPlaceId  = placeId;
        currentGameName = null;

        panelGameLabel.setText("Looking up game...");
        panelGameLabel.setStyle(
            "-fx-text-fill: #eaeaea; -fx-font-family: Georgia; -fx-font-size: 13;"
        );
        noteField.clear();
        noteField.setDisable(false);
        resetRequestButton();
        showPanelAnimated();

        // Fetch game name off the JFXAT — network must not block the UI thread
        CompletableFuture.runAsync(() -> {
            String name = launcher.fetchGameName(placeId);
            currentGameName = (name != null) ? name : "Game " + placeId;
            Platform.runLater(() ->
                panelGameLabel.setText("Game: " + currentGameName + "  |  ID: " + placeId)
            );
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REQUEST BUTTON
    // ══════════════════════════════════════════════════════════════════════════

    /** Fires when child clicks "+ Request This Game". */
    private void onRequestClicked() {
        if (currentPlaceId == null) return;

        // Snapshot before going async — child may navigate while saving
        final String placeId = currentPlaceId;
        final String name    = (currentGameName != null) ? currentGameName : "Game " + placeId;
        final String note    = noteField.getText().strip();
        final String pageUrl = engine.getLocation();

        // Disable immediately — prevents double-submit
        requestBtn.setDisable(true);
        requestBtn.setText("Saving...");
        applyRequestBtnStyle(requestBtn, true);

        CompletableFuture.runAsync(() -> {
            boolean ok = launcher.saveRequest(placeId, name, note, pageUrl);
            launcher.LOG.info("[bloxbox] Request saved: '" + name + "' -> " + ok);
            Platform.runLater(() -> showConfirmation(name, ok));
        });
    }

    /**
     * Show brief success/failure confirmation, then reset after 2 seconds.
     * Must be called on the JFXAT.
     */
    private void showConfirmation(String gameName, boolean success) {
        if (success) {
            panelGameLabel.setText("'" + gameName + "' requested! Ask a parent to review it.");
            panelGameLabel.setStyle(
                "-fx-text-fill: #4caf50; -fx-font-family: Georgia; " +
                "-fx-font-size: 13; -fx-font-weight: bold;"
            );
        } else {
            panelGameLabel.setText("Could not save — check file permissions.");
            panelGameLabel.setStyle(
                "-fx-text-fill: #e94560; -fx-font-family: Georgia; -fx-font-size: 13;"
            );
        }
        noteField.clear();
        noteField.setDisable(true);
        requestBtn.setText("Requested!");

        PauseTransition pause = new PauseTransition(Duration.seconds(2));
        pause.setOnFinished(e -> {
            // Restore only if still on a game page
            Matcher m = GAME_PAGE.matcher(engine.getLocation());
            if (m.find()) {
                panelGameLabel.setText("Game: " + currentGameName + "  |  ID: " + currentPlaceId);
                panelGameLabel.setStyle(
                    "-fx-text-fill: #eaeaea; -fx-font-family: Georgia; -fx-font-size: 13;"
                );
                noteField.clear();
                noteField.setDisable(false);
                resetRequestButton();
            } else {
                hidePanelAnimated();
            }
        });
        pause.play();
    }

    /** Restore the request button to its ready state. */
    private void resetRequestButton() {
        requestBtn.setDisable(false);
        requestBtn.setText("+ Request This Game");
        applyRequestBtnStyle(requestBtn, false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PANEL ANIMATION
    // ══════════════════════════════════════════════════════════════════════════

    /** Animate the slide panel up. Safe to call repeatedly. Must be on JFXAT. */
    private void showPanelAnimated() {
        slideOut.stop();
        slideIn.playFromStart();
    }

    /** Animate the slide panel back down. Must be on JFXAT. */
    private void hidePanelAnimated() {
        slideIn.stop();
        slideOut.playFromStart();
    }
}
