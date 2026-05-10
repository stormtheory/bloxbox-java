/*
 * GameCard.java -- A single game tile in the launcher grid.
 *
 * Displays:
 *   - Async-loaded thumbnail (spinner while loading, fallback on failure)
 *   - Game name with emoji stripped via launcher.cleanGameName()
 *   - Play button that launches via Sober / Desktop.browse()
 *   - Hover highlight on the whole card
 *
 * NOTE on emoji:
 *   Roblox game names frequently contain emoji e.g. "[bottle] Welcome to Bloxburg".
 *   Java 2D on Linux cannot render color emoji fonts (NotoColorEmoji is a color font
 *   and every Swing rendering path -- JLabel, TextLayout, HTML -- shows empty boxes).
 *   We strip emoji via launcher.cleanGameName() and also clean up leftover empty
 *   bracket artifacts so "[] Welcome to Bloxburg" becomes "Welcome to Bloxburg".
 *
 * Thumbnail loading runs in a CompletableFuture background thread.
 * All Swing updates are dispatched to the EDT via SwingUtilities.invokeLater().
 */

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

public class GameCard extends JPanel {

    // ── Instance state ────────────────────────────────────────────────────────
    private final Map<String, String> game;        // Game data from the whitelist JSON
    private       JLabel             thumbLabel;  // Placeholder replaced with image async
    private final JFrame             parentFrame; // Needed for error dialogs in launch flow

    // ── Constructor ───────────────────────────────────────────────────────────
    GameCard(Map<String, String> game, JFrame parentFrame) {
        this.game        = game;
        this.parentFrame = parentFrame;

        // Fixed card size -- prevents layout shift as thumbnails load
        setPreferredSize(new Dimension(launcher.CARD_WIDTH, launcher.CARD_HEIGHT));
        setBackground(launcher.CARD_COLOR);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 8, 8, 8));

        buildUI();
        bindHover();

        // Kick off thumbnail fetch in the background -- keeps grid snappy
        CompletableFuture.runAsync(this::loadThumbnail);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        // Spinner placeholder -- swapped for real thumbnail once async load finishes
        thumbLabel = new JLabel("...", SwingConstants.CENTER);
        thumbLabel.setFont(new Font("Dialog", Font.PLAIN, 14));
        thumbLabel.setForeground(launcher.SUBTEXT_COLOR);
        thumbLabel.setBackground(launcher.CARD_COLOR);
        thumbLabel.setOpaque(true);
        // Fixed pixel size prevents layout shift when the real image arrives
        thumbLabel.setPreferredSize(new Dimension(launcher.THUMB_SIZE, launcher.THUMB_SIZE));
        thumbLabel.setMaximumSize(new Dimension(launcher.THUMB_SIZE, launcher.THUMB_SIZE));
        thumbLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(thumbLabel);
        add(Box.createVerticalStrut(4));

        // Game name -- emoji stripped because Java 2D cannot render color emoji on Linux.
        // cleanGameName() also removes leftover empty brackets after emoji removal.
        // e.g. "[bottle] Welcome to Bloxburg house" -> "Welcome to Bloxburg"
        String rawName     = game.getOrDefault("name", "Unknown");
        String displayName = launcher.cleanGameName(rawName);

        JLabel nameLabel = new JLabel(
            "<html><div style='text-align:center;width:" + (launcher.CARD_WIDTH - 16) + "px'>"
            + displayName + "</div></html>",
            SwingConstants.CENTER
        );
        nameLabel.setFont(launcher.FONT_CARD);
        nameLabel.setForeground(launcher.TEXT_COLOR);
        nameLabel.setBackground(launcher.CARD_COLOR);
        nameLabel.setOpaque(true);
        nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(nameLabel);
        add(Box.createVerticalStrut(2));

        // Play button -- red accent, direct Sober/roblox:// launch
        JButton playBtn = launcher.makeButton("Play", launcher.ACCENT_COLOR);
        playBtn.addActionListener(e -> onLaunch());
        playBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(Box.createVerticalStrut(8));
        add(playBtn);
    }

    // ── Thumbnail loading (background thread) ─────────────────────────────────

    /**
     * Fetch the thumbnail image for this game's place ID.
     * Uses the shared disk cache -- network only on first load per game.
     * Called on a CompletableFuture worker thread, never the EDT.
     */
    private void loadThumbnail() {
        String placeId = game.getOrDefault("place_id", "");
        launcher.LOG.fine("[bloxbox] Loading thumbnail for " + placeId);

        BufferedImage img = launcher.fetchThumbnailImage(placeId);

        if (img == null) {
            // API failed -- show a simple text fallback instead of missing image
            SwingUtilities.invokeLater(() -> setPlaceholder("[no image]"));
            launcher.LOG.warning("[bloxbox] No thumbnail for " + placeId);
            return;
        }

        // Scale to card dimensions -- SCALE_SMOOTH mirrors PIL LANCZOS quality
        Image scaled = img.getScaledInstance(launcher.THUMB_SIZE, launcher.THUMB_SIZE, Image.SCALE_SMOOTH);
        launcher.LOG.fine("[bloxbox] Thumbnail scaled for " + placeId);

        // Schedule the label swap on the EDT -- never mutate Swing components off-EDT
        SwingUtilities.invokeLater(() -> {
            thumbLabel.setIcon(new ImageIcon(scaled));
            thumbLabel.setText("");
            launcher.LOG.fine("[bloxbox] Thumbnail displayed for " + placeId);
        });
    }

    /**
     * Replace the loading placeholder with a simple text fallback.
     * Called when ImageIO or the Roblox API returns nothing useful.
     * Must be called on the EDT.
     */
    private void setPlaceholder(String text) {
        thumbLabel.setFont(new Font("Dialog", Font.PLAIN, 11));
        thumbLabel.setText(text);
        thumbLabel.setIcon(null);
    }

    // ── Launch handler ────────────────────────────────────────────────────────

    /**
     * Play button click -- kill any running Sober instance first to avoid
     * conflicts, then launch the new game via roblox:// URI.
     */
    private void onLaunch() {
        // Check if Sober is already running before trying to kill it
        try {
            String[] checkCmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? new String[]{"tasklist", "/FI", "IMAGENAME eq sober.exe"}
                : new String[]{"pgrep", "sober"};
            Process check = Runtime.getRuntime().exec(checkCmd);
            if (check.waitFor() == 0) launcher.terminateSober();
        } catch (Exception ignored) {}

        launcher.launchGame(
            game.getOrDefault("place_id", ""),
            game.getOrDefault("name", "Game"),  // Use raw name for launch (not display name)
            parentFrame
        );
    }

    // ── Hover highlight ───────────────────────────────────────────────────────

    /**
     * Bind enter/exit mouse events to lighten the card on hover.
     * Applied to the card panel and all direct child components so
     * hovering over a label or button still highlights the whole card.
     */
    private void bindHover() {
        MouseAdapter hover = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { setCardBg(launcher.HOVER_COLOR); }
            @Override public void mouseExited (MouseEvent e) { setCardBg(launcher.CARD_COLOR);  }
        };
        addMouseListener(hover);
        for (Component c : getComponents()) c.addMouseListener(hover);
    }

    /** Apply a background colour to this panel and all opaque children. */
    private void setCardBg(Color bg) {
        setBackground(bg);
        for (Component c : getComponents())
            if (c.isOpaque()) c.setBackground(bg);
        repaint();
    }
}
