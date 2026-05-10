/*
 * LauncherApp.java — Main launcher window.
 *
 * Layout:
 *   ┌──────────────────────────────────────────────┐
 *   │  Header: title  |  "＋ Request a Game" btn   │
 *   ├────────┬─────────────────────────────────────┤
 *   │Sidebar │  Scrollable game card grid           │
 *   │(hover  │  (filtered by selected category)     │
 *   │expand) │                                      │
 *   └────────┴─────────────────────────────────────┘
 *
 * Sidebar:
 *   - Collapsed: 52 px, shows emoji icon only
 *   - Expanded:  200 px, shows emoji + category label
 *   - Animation: javax.swing.Timer drives pixel-per-frame width interpolation
 *   - Expands on mouse-enter, collapses on mouse-exit (whole sidebar)
 *   - Selected category row highlighted in SIDEBAR_SEL colour
 *   - "All" entry always at the top; one row per unique category found in JSON
 *   - Games with no/unknown category fall into GAME_UNSORTED_CATEGORY bucket
 *
 * Grid:
 *   - Redrawn when the selected category changes
 *   - Uses GridLayout(0, COLS) — auto-expanding rows
 *   - Each tile is a GameCard instance
 */

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.Timer;

public class LauncherApp extends JFrame {

    // ── State ─────────────────────────────────────────────────────────────────
    private String                    selectedCategory = "All"; // Currently active filter
    private List<Map<String, String>> allGames;                 // Full game list from config
    private JPanel                    gridHolder;               // Repopulated on category change
    private JScrollPane               scrollPane;               // Wraps gridHolder

    // ── Sidebar animation state ────────────────────────────────────────────────
    private JPanel sidebarPanel;    // The sidebar container — its width is animated
    private Timer  sidebarTimer;    // javax.swing.Timer drives the animation (~60fps)
    private int    sidebarTargetW = launcher.SIDEBAR_COLLAPSED_W; // Current animation target
    private List<SidebarRow> sidebarRows = new ArrayList<>();    // All sidebar row components

    // ── Constructor ───────────────────────────────────────────────────────────
    LauncherApp() {
        super(launcher.WINDOW_TITLE);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        getContentPane().setBackground(launcher.BG_COLOR);
        setLayout(new BorderLayout());

        // Load games once — shared by sidebar (for category list) and grid
        allGames = launcher.loadConfig();

        buildUI();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN UI ASSEMBLY
    // ══════════════════════════════════════════════════════════════════════════

    private void buildUI() {
        add(buildHeader(),  BorderLayout.NORTH);
        add(buildSidebar(), BorderLayout.WEST);
        add(buildGrid(),    BorderLayout.CENTER);
    }

    // ── Header ────────────────────────────────────────────────────────────────

    /** Build the top header bar: app title on the left, request button on the right. */
    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(launcher.BG_COLOR);
        header.setBorder(BorderFactory.createEmptyBorder(20, 20, 8, 20));

        // Left: title + subtitle
        JPanel titleGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4));
        titleGroup.setBackground(launcher.BG_COLOR);

        JLabel titleLbl = new JLabel("🎮 BloxBox");
        titleLbl.setFont(launcher.FONT_TITLE);
        titleLbl.setForeground(launcher.TEXT_COLOR);
        titleGroup.add(titleLbl);

        JLabel subLbl = new JLabel("Approved games only");
        subLbl.setFont(launcher.FONT_SMALL);
        subLbl.setForeground(launcher.SUBTEXT_COLOR);
        titleGroup.add(subLbl);

        // Right: request button
        JButton requestBtn = launcher.makeButton("＋  Request a Game", launcher.REQUEST_COLOR);
        requestBtn.addActionListener(e -> openRequestDialog());

        header.add(titleGroup, BorderLayout.WEST);
        header.add(requestBtn, BorderLayout.EAST);
        return header;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIDEBAR — animated hover-expand category filter
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Build the animated sidebar.
     *
     * The sidebar starts collapsed (SIDEBAR_COLLAPSED_W px).
     * Hovering anywhere on the sidebar fires the expand animation.
     * Moving the mouse off the sidebar fires the collapse animation.
     * A javax.swing.Timer interpolates the preferred width each tick.
     *
     * Category rows are built dynamically from the game list — one row per
     * unique category value found, plus a hardcoded "All" row at the top.
     * Games without a recognised category are grouped under GAME_UNSORTED_CATEGORY.
     */
    private JPanel buildSidebar() {
        sidebarPanel = new JPanel();
        sidebarPanel.setLayout(new BoxLayout(sidebarPanel, BoxLayout.Y_AXIS));
        sidebarPanel.setBackground(launcher.SIDEBAR_COLOR);
        sidebarPanel.setPreferredSize(new Dimension(launcher.SIDEBAR_COLLAPSED_W, 0));
        sidebarPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.decode("#2a2a4a")));

        // ── Collect unique categories from the game list ──────────────────────
        // LinkedHashSet preserves insertion order (order games appear in JSON)
        Set<String> categories = new LinkedHashSet<>();
        for (Map<String, String> game : allGames) {
            String cat = game.getOrDefault("category", "").strip();
            if (!cat.isEmpty()) {
                categories.add(cat);
            }
        }
        // Add the unsorted bucket if any games lack a category
        boolean hasUnsorted = allGames.stream()
            .anyMatch(g -> g.getOrDefault("category", "").strip().isEmpty());
        if (hasUnsorted) categories.add(launcher.GAME_UNSORTED_CATEGORY);

        // ── Top spacer ────────────────────────────────────────────────────────
        sidebarPanel.add(Box.createVerticalStrut(16));

        // ── "All" row — always first ──────────────────────────────────────────
        addSidebarRow("All", "All", true); // isSelected=true on startup

        // ── One row per category ──────────────────────────────────────────────
        for (String cat : categories) {
            // Map to short text icon -- emoji cannot render in Java 2D on Linux
            String icon = categoryIcon(cat);
            addSidebarRow(icon, cat, false);
        }

        sidebarPanel.add(Box.createVerticalGlue()); // Push rows to the top

        // ── Hover animation — whole sidebar expand/collapse ───────────────────
        // Timer ticks at ~60fps and nudges the preferred width toward the target
        sidebarTimer = new Timer(launcher.SIDEBAR_ANIM_MS, e -> animateSidebar());

        MouseAdapter sidebarHover = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { expandSidebar(); }
            @Override public void mouseExited (MouseEvent e) {
                // Only collapse when the mouse truly leaves the sidebar component tree
                Component dest = e.getComponent().getComponentAt(e.getPoint());
                if (dest == null || !isAncestorOf(dest)) collapseSidebar();
            }
        };
        // Bind to the panel itself — child rows also get it via propagation
        sidebarPanel.addMouseListener(sidebarHover);

        return sidebarPanel;
    }

    /**
     * Add a single row to the sidebar.
     *
     * Each row is a fixed-height JPanel containing:
     *   - An emoji label (always visible, centred in the collapsed icon zone)
     *   - A text label (hidden width when collapsed, visible when expanded)
     *
     * @param emoji      Leading emoji character(s) extracted from the category string
     * @param category   Full category string (e.g. "📚 Education") or "All"
     * @param isSelected Whether this row should start highlighted
     */
    private void addSidebarRow(String emoji, String category, boolean isSelected) {
        SidebarRow row = new SidebarRow(emoji, category, isSelected);

        row.addMouseListener(new MouseAdapter() {
            // Re-trigger sidebar expand when hovering a child row
            @Override public void mouseEntered(MouseEvent e) { expandSidebar(); }
            @Override public void mouseExited (MouseEvent e) {
                // getDeepestComponentAt checks if the mouse moved to another sidebar child
                // rather than truly leaving the sidebar — prevents flicker on row boundaries
                Component dest = javax.swing.SwingUtilities.getDeepestComponentAt(
                    sidebarPanel, e.getX() + row.getX(), e.getY() + row.getY());
                if (dest == null || !sidebarPanel.isAncestorOf(dest)) collapseSidebar();
            }

            @Override public void mouseClicked(MouseEvent e) {
                // Update selection state across all rows
                for (SidebarRow r : sidebarRows) r.setSelected(false);
                row.setSelected(true);
                selectedCategory = category;
                refreshGrid(); // Redraw the game grid for the new filter
            }
        });

        sidebarRows.add(row);
        sidebarPanel.add(row);
        sidebarPanel.add(Box.createVerticalStrut(2));
    }

    // ── Sidebar animation helpers ─────────────────────────────────────────────

    /** Start expanding the sidebar toward SIDEBAR_EXPANDED_W. */
    private void expandSidebar() {
        sidebarTargetW = launcher.SIDEBAR_EXPANDED_W;
        if (!sidebarTimer.isRunning()) sidebarTimer.start();
    }

    /** Start collapsing the sidebar toward SIDEBAR_COLLAPSED_W. */
    private void collapseSidebar() {
        sidebarTargetW = launcher.SIDEBAR_COLLAPSED_W;
        if (!sidebarTimer.isRunning()) sidebarTimer.start();
    }

    /**
     * Called each timer tick (~60fps).
     * Nudges the sidebar's preferred width one step toward the target.
     * Stops the timer once the target is reached.
     */
    private void animateSidebar() {
        int current = sidebarPanel.getPreferredSize().width;
        int diff    = sidebarTargetW - current;

        if (Math.abs(diff) <= launcher.SIDEBAR_ANIM_STEP) {
            // Close enough — snap to target and stop
            sidebarPanel.setPreferredSize(new Dimension(sidebarTargetW, sidebarPanel.getHeight()));
            sidebarTimer.stop();
        } else {
            // Step toward target
            int next = current + (int) Math.signum(diff) * launcher.SIDEBAR_ANIM_STEP;
            sidebarPanel.setPreferredSize(new Dimension(next, sidebarPanel.getHeight()));
        }

        // Trigger layout reflow so the grid resizes alongside the sidebar
        sidebarPanel.getParent().revalidate();
    }

    // ── Sidebar row component ─────────────────────────────────────────────────

    /**
     * A single sidebar row: emoji icon + category label.
     *
     * The label clips naturally as the sidebar collapses — no manual show/hide needed.
     * Selection highlight is drawn as a filled background rectangle.
     */
    private static class SidebarRow extends JPanel {

        private final String  emoji;
        private final String  label;
        private       boolean selected;

        SidebarRow(String emoji, String label, boolean selected) {
            this.emoji    = emoji;
            this.label    = label;
            this.selected = selected;

            setOpaque(false); // We draw the background ourselves in paintComponent
            setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44)); // Fixed row height
            setPreferredSize(new Dimension(launcher.SIDEBAR_EXPANDED_W, 44));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        void setSelected(boolean sel) {
            this.selected = sel;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Background — highlight if selected, otherwise transparent
            if (selected) {
                g2.setColor(launcher.SIDEBAR_SEL);
                g2.fillRoundRect(4, 2, getWidth() - 8, getHeight() - 4, 8, 8);
            }

            // Left accent bar on selected row
            if (selected) {
                g2.setColor(launcher.ACCENT_COLOR);
                g2.fillRoundRect(0, 6, 3, getHeight() - 12, 3, 3);
            }

            int iconZoneW = launcher.SIDEBAR_COLLAPSED_W; // Icon always in the left 52px

            // Emoji icon — centred in the icon zone
            g2.setFont(new Font("Monospaced", Font.BOLD, 10));
            FontMetrics fm    = g2.getFontMetrics();
            int         iconX  = (iconZoneW - fm.stringWidth(emoji)) / 2;
            int         textY  = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g2.setColor(selected ? launcher.ACCENT_COLOR : launcher.SUBTEXT_COLOR);
            g2.drawString(emoji, iconX, textY);

            // Category label — drawn to the right of the icon zone
            // Naturally invisible when the sidebar is collapsed (clipped by container)
            g2.setFont(new Font("Dialog",  Font.BOLD, 11)); // Dialog chains to emoji fonts on all platforms
            fm = g2.getFontMetrics();
            g2.setColor(selected ? launcher.TEXT_COLOR : launcher.SUBTEXT_COLOR);
            g2.drawString(launcher.cleanGameName(label), iconZoneW + 6, textY);

            g2.dispose();
            super.paintComponent(g); // Paint any child components (none currently)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GAME GRID
    // ══════════════════════════════════════════════════════════════════════════

    /** Build the initial scrollable grid container. */
    private JScrollPane buildGrid() {
        gridHolder = new JPanel();
        gridHolder.setBackground(launcher.BG_COLOR);

        populateGrid();

        scrollPane = new JScrollPane(gridHolder);
        scrollPane.setBackground(launcher.BG_COLOR);
        scrollPane.getViewport().setBackground(launcher.BG_COLOR);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(20); // Smooth scroll speed

        return scrollPane;
    }

    /**
     * Re-filter the game list and repopulate the grid.
     * Called on startup and whenever the selected category changes.
     * Resets the scroll position to the top after each filter change.
     */
    private void populateGrid() {
        gridHolder.removeAll(); // Clear existing cards
        gridHolder.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        // ── Filter games by selected category ────────────────────────────────
        List<Map<String, String>> filtered = new ArrayList<>();
        for (Map<String, String> game : allGames) {
            if (selectedCategory.equals("All")) {
                filtered.add(game); // "All" shows everything
            } else if (selectedCategory.equals(launcher.GAME_UNSORTED_CATEGORY)) {
                // Unsorted: games with missing or empty category field
                String cat = game.getOrDefault("category", "").strip();
                if (cat.isEmpty()) filtered.add(game);
            } else {
                // Normal category match
                if (selectedCategory.equals(game.getOrDefault("category", "").strip()))
                    filtered.add(game);
            }
        }

        if (filtered.isEmpty()) {
            // Empty state — friendly message pointing to the request button
            gridHolder.setLayout(new BorderLayout());
            String msg = allGames.isEmpty()
                ? "No games approved yet.<br>Use '＋ Request a Game' above!"
                : "No games in this category yet.";
            JLabel empty = new JLabel(
                "<html><div style='text-align:center'>" + msg + "</div></html>",
                SwingConstants.CENTER
            );
            empty.setFont(new Font("Georgia", Font.PLAIN, 16));
            empty.setForeground(launcher.SUBTEXT_COLOR);
            empty.setBorder(BorderFactory.createEmptyBorder(80, 40, 80, 40));
            gridHolder.add(empty, BorderLayout.CENTER);
        } else {
            // Grid layout: 0 = auto-expand rows, COLS columns, 12px gaps
            gridHolder.setLayout(new GridLayout(0, launcher.COLS, 12, 12));
            for (Map<String, String> game : filtered) {
                gridHolder.add(new GameCard(game, this));
            }
        }

        // Force layout refresh and reset scroll to top
        gridHolder.revalidate();
        gridHolder.repaint();
        if (scrollPane != null) {
            scrollPane.getVerticalScrollBar().setValue(0);
        }
    }

    /** Rebuild the grid after a category selection change. */
    private void refreshGrid() {
        populateGrid();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REQUEST DIALOG — open WebView or fallback
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Open the game request dialog after PIN verification.
     *
     * Priority:
     *   1. RequestDialogWebView — uses JavaFX WebView (requires javafx.web module)
     *   2. RequestDialogFallback — manual place-ID entry if JavaFX unavailable
     *
     * WebView detection: attempt to load the class at runtime.
     * If the javafx.web module is absent the Class.forName() call throws and
     * we fall through to the Fallback silently.
     */
    private void openRequestDialog() {
        // Gate: verify PIN before showing any request UI
        PinDialog pin = new PinDialog(this);
        pin.setVisible(true); // Blocks until the modal dialog closes
        if (!pin.verified) return; // Cancelled or wrong PIN — abort

        // Try WebView first — gracefully degrade to fallback if JavaFX unavailable
        boolean webViewAvailable = false;
        try {
            Class.forName("javafx.scene.web.WebView"); // Probe for javafx.web module
            webViewAvailable = true;
        } catch (ClassNotFoundException e) {
            launcher.LOG.warning("[bloxbox] javafx.web not found — using fallback dialog");
        }

        if (webViewAvailable) {
            // Full embedded browser — non-modal, stays open for multiple requests
            RequestDialogWebView dialog = new RequestDialogWebView(this);
            dialog.setVisible(true);
        } else {
            // Open system browser for browsing, show manual ID entry dialog
            openSystemBrowser(launcher.ROBLOX_GAME_SEARCH_URL);
            RequestDialogFallback dialog = new RequestDialogFallback(this);
            dialog.setVisible(true); // Modal — blocks until child submits or cancels
        }
    }

    /**
     * Open a URL in the system default browser.
     * Tries java.awt.Desktop first (works on Windows/macOS), then falls back
     * to Firefox and xdg-open for Linux.
     */
    private void openSystemBrowser(String url) {
        // Strategy 1: java.awt.Desktop — standard cross-platform
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
            return;
        } catch (Exception ignored) {}

        // Strategy 2: Firefox (Linux fallback)
        try {
            new ProcessBuilder("firefox", url)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            return;
        } catch (IOException ignored) {}

        // Strategy 3: xdg-open (any Linux desktop)
        try {
            new ProcessBuilder("xdg-open", url)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        } catch (IOException ignored) {} // No browser found — just show the dialog anyway
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UTILITY
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Map a category string to a short text icon for the collapsed sidebar.
     * Text icons replace emoji because Java 2D on Linux cannot render color emoji fonts.
     * Known categories matched by keyword; unknown categories use first 4 chars.
     */
    private static String categoryIcon(String category) {
        if (category == null || category.isEmpty()) return "?";
        String clean = launcher.cleanGameName(category).toLowerCase().strip();
        if (clean.contains("role"))                return "Role";
        if (clean.contains("racing") || clean.contains("race")) return "Race";
        if (clean.contains("family") || clean.contains("fam"))  return "Fam";
        if (clean.contains("education") || clean.contains("edu")) return "Edu";
        if (clean.contains("world") || clean.contains("explor")) return "World";
        if (clean.contains("sport"))               return "Sport";
        if (clean.contains("unsorted") || clean.contains("other")) return "???";
        // Fallback: first 4 chars of the cleaned name, title-cased
        return clean.length() >= 4
            ? clean.substring(0, 1).toUpperCase() + clean.substring(1, 4)
            : clean;
    }
}
