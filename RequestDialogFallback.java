/*
 * RequestDialogFallback.java — Manual place-ID request dialog.
 *
 * Used when JavaFX WebView is not available (module path missing, headless env, etc.).
 * Opens roblox.com/charts in the system browser, then the child copies the numeric
 * place ID from the URL bar and pastes it here to look up and request the game.
 *
 * Flow:
 *   1. System browser opens automatically to Roblox charts.
 *   2. Child finds a game, copies the ID from the URL.
 *   3. Child pastes ID → clicks "Look Up" → name + thumbnail appear.
 *   4. Child adds an optional note → "Send Request" saves to the requests file.
 *
 * Mirrors Python RequestDialogFallback(tk.Toplevel).
 */

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
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
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.time.Duration;

public class RequestDialogFallback extends JDialog {

    // ── State — set after a successful lookup ─────────────────────────────────
    private String fetchedPlaceId  = null; // Validated, digit-only place ID
    private String fetchedGameName = null; // Display name from Roblox API

    // ── UI components ─────────────────────────────────────────────────────────
    private JTextField idEntry;     // Child types or pastes the place ID here
    private JLabel     thumbLabel;  // Thumbnail preview (spinner → image)
    private JLabel     nameLabel;   // Game display name
    private JLabel     statusLabel; // "Looking up…" / "✅ Found!" / error messages
    private JTextField noteEntry;   // Optional note from the child
    private JButton    submitBtn;   // Disabled until lookup succeeds

    // ── Constructor ───────────────────────────────────────────────────────────
    RequestDialogFallback(JFrame parent) {
        super(parent, "Request a Game", true); // true = modal
        setResizable(false);
        getContentPane().setBackground(launcher.BG_COLOR);

        buildUI();
        pack();
        setLocationRelativeTo(parent);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(launcher.BG_COLOR);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        // ── Title ─────────────────────────────────────────────────────────────
        JLabel title = new JLabel("Request a New Game");
        title.setFont(new java.awt.Font("Georgia", java.awt.Font.BOLD, 16));
        title.setForeground(launcher.TEXT_COLOR);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(4));

        // ── Instructions — tell child how to find the place ID ────────────────
        JLabel instructions = new JLabel(
            "<html><div style='text-align:center'>" +
            "Find a game in the browser that just opened.<br>" +
            "Copy the number from the URL bar:<br>" +
            "<span style='color:#888888'>roblox.com/games/<b>185655149</b>/...</span>" +
            "</div></html>",
            SwingConstants.CENTER
        );
        instructions.setFont(launcher.FONT_SMALL);
        instructions.setForeground(launcher.SUBTEXT_COLOR);
        instructions.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(instructions);
        panel.add(Box.createVerticalStrut(10));

        // ── Place ID entry + Look Up button ───────────────────────────────────
        JPanel entryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        entryRow.setBackground(launcher.BG_COLOR);

        idEntry = new JTextField(20);
        idEntry.setFont(launcher.FONT_SMALL);
        idEntry.setBackground(Color.decode("#252540"));
        idEntry.setForeground(launcher.TEXT_COLOR);
        idEntry.setCaretColor(launcher.TEXT_COLOR);
        idEntry.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        // Enter key triggers lookup — convenience for keyboard users
        idEntry.addActionListener(e -> onLookup());

        JButton lookupBtn = launcher.makeButton("Look Up", launcher.REQUEST_COLOR);
        lookupBtn.addActionListener(e -> onLookup());

        entryRow.add(idEntry);
        entryRow.add(Box.createHorizontalStrut(8));
        entryRow.add(lookupBtn);
        panel.add(entryRow);
        panel.add(Box.createVerticalStrut(10));

        // ── Thumbnail preview — empty until lookup succeeds ───────────────────
        thumbLabel = new JLabel("", SwingConstants.CENTER);
        thumbLabel.setFont(new java.awt.Font("Segoe UI Emoji", java.awt.Font.PLAIN, 28));
        thumbLabel.setForeground(launcher.SUBTEXT_COLOR);
        thumbLabel.setBackground(launcher.BG_COLOR);
        thumbLabel.setOpaque(true);
        thumbLabel.setPreferredSize(new Dimension(100, 100));
        thumbLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(thumbLabel);
        panel.add(Box.createVerticalStrut(4));

        // ── Game name from API ────────────────────────────────────────────────
        nameLabel = new JLabel("", SwingConstants.CENTER);
        nameLabel.setFont(launcher.FONT_CARD);
        nameLabel.setForeground(launcher.TEXT_COLOR);
        nameLabel.setBackground(launcher.BG_COLOR);
        nameLabel.setOpaque(true);
        nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(nameLabel);

        // ── Status feedback label ─────────────────────────────────────────────
        statusLabel = new JLabel("", SwingConstants.CENTER);
        statusLabel.setFont(launcher.FONT_SMALL);
        statusLabel.setForeground(launcher.SUBTEXT_COLOR);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(statusLabel);
        panel.add(Box.createVerticalStrut(10));

        // ── Optional note ─────────────────────────────────────────────────────
        JLabel noteLbl = new JLabel("Why do you want to play it? (optional):");
        noteLbl.setFont(launcher.FONT_SMALL);
        noteLbl.setForeground(launcher.TEXT_COLOR);
        panel.add(noteLbl);
        panel.add(Box.createVerticalStrut(4));

        noteEntry = new JTextField(30);
        noteEntry.setFont(launcher.FONT_SMALL);
        noteEntry.setBackground(Color.decode("#252540"));
        noteEntry.setForeground(launcher.TEXT_COLOR);
        noteEntry.setCaretColor(launcher.TEXT_COLOR);
        noteEntry.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(noteEntry);
        panel.add(Box.createVerticalStrut(16));

        // ── Buttons — Submit disabled until a valid lookup has succeeded ──────
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        btnRow.setBackground(launcher.BG_COLOR);

        // Start disabled + grey — enabled with blue once game is found
        submitBtn = launcher.makeButton("Send Request", Color.decode("#444444"));
        submitBtn.setForeground(Color.decode("#888888"));
        submitBtn.setEnabled(false);
        submitBtn.addActionListener(e -> onSubmit());

        JButton cancelBtn = launcher.makeButton("Cancel", Color.decode("#333333"));
        cancelBtn.addActionListener(e -> dispose());

        btnRow.add(submitBtn);
        btnRow.add(cancelBtn);
        panel.add(btnRow);

        add(panel);
    }

    // ── Lookup flow ───────────────────────────────────────────────────────────

    /**
     * Validate the typed place ID and kick off async name + thumbnail lookup.
     * Shows a spinner state while the background thread runs.
     */
    private void onLookup() {
        String placeId = idEntry.getText().strip();

        // Place IDs are always numeric — reject anything else immediately
        if (!placeId.matches("\\d+")) {
            statusLabel.setForeground(launcher.ACCENT_COLOR);
            statusLabel.setText("⚠️  Place ID must be a number (digits only).");
            return;
        }

        // Reset preview area to "loading" state
        statusLabel.setForeground(launcher.SUBTEXT_COLOR);
        statusLabel.setText("Looking up game…");
        nameLabel.setText("");
        thumbLabel.setIcon(null);
        thumbLabel.setText("⏳");
        disableSubmit();

        // Run network calls off the EDT — keep the dialog responsive
        CompletableFuture.runAsync(() -> {
            String thumbUrl  = launcher.fetchThumbnailUrl(placeId);
            String gameName  = launcher.fetchGameName(placeId);
            // Return to EDT to update UI
            SwingUtilities.invokeLater(() -> showPreview(placeId, gameName, thumbUrl));
        });
    }

    /**
     * Update the preview area with lookup results.
     * Enables Submit if at least a name was found; shows error if nothing found.
     * Must be called on the EDT.
     */
    private void showPreview(String placeId, String gameName, String thumbUrl) {
        if (gameName == null && thumbUrl == null) {
            // Roblox returned nothing — bad ID or network failure
            statusLabel.setForeground(launcher.ACCENT_COLOR);
            statusLabel.setText("⚠️  Game not found. Double-check the Place ID.");
            thumbLabel.setIcon(null);
            thumbLabel.setText("❓");
            return;
        }

        // Stash validated result — used by onSubmit()
        fetchedPlaceId  = placeId;
        fetchedGameName = (gameName != null) ? gameName : "Game " + placeId;
        nameLabel.setText(fetchedGameName);

        if (thumbUrl != null) {
            // Download and display thumbnail in the background
            final String url = thumbUrl;
            CompletableFuture.runAsync(() -> {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(8)).GET().build();
                    byte[] data = launcher.HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray()).body();
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                    if (img != null) {
                        Image scaled = img.getScaledInstance(100, 100, Image.SCALE_SMOOTH);
                        SwingUtilities.invokeLater(() -> {
                            thumbLabel.setIcon(new ImageIcon(scaled));
                            thumbLabel.setText("");
                        });
                    }
                } catch (Exception e) {
                    // Thumbnail failed — emoji fallback, don't block the submit
                    SwingUtilities.invokeLater(() -> { thumbLabel.setIcon(null); thumbLabel.setText("🎮"); });
                }
            });
        } else {
            // No thumbnail URL — show emoji placeholder
            thumbLabel.setIcon(null);
            thumbLabel.setText("🎮");
        }

        // Success — enable Submit button
        statusLabel.setForeground(Color.decode("#4caf50"));
        statusLabel.setText("✅  Game found!");
        enableSubmit();
    }

    // ── Submit flow ───────────────────────────────────────────────────────────

    /**
     * Save the request and close the dialog.
     * Only reachable if a valid lookup has already succeeded (submitBtn enabled).
     */
    private void onSubmit() {
        if (fetchedPlaceId == null) return; // Guard — shouldn't happen but be safe

        boolean ok = launcher.saveRequest(
            fetchedPlaceId,
            fetchedGameName,
            noteEntry.getText().strip(),
            "" // No URL in the manual flow
        );
        dispose();

        if (ok) {
            JOptionPane.showMessageDialog(getParent(),
                "'" + fetchedGameName + "' has been requested.\nAsk a parent to review it!",
                "Request Sent! 🎮", JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(getParent(),
                "Permission error saving your request.\nAsk a parent to check the requests file.",
                "Could Not Save", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Submit button state helpers ───────────────────────────────────────────

    /** Enable Submit with the blue active colour. */
    private void enableSubmit() {
        submitBtn.setEnabled(true);
        submitBtn.setBackground(launcher.REQUEST_COLOR);
        submitBtn.setForeground(Color.WHITE);
    }

    /** Disable Submit with the grey inactive colour. */
    private void disableSubmit() {
        submitBtn.setEnabled(false);
        submitBtn.setBackground(Color.decode("#444444"));
        submitBtn.setForeground(Color.decode("#888888"));
    }
}
