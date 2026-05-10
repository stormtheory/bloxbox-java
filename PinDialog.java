/*
 * PinDialog.java — Modal PIN entry dialog shown before the request flow.
 *
 * Verifies the entered PIN against the stored SHA-256 hash via launcher.verifyPin().
 * After setVisible(true) returns, callers check dialog.verified to decide whether
 * to proceed.
 *
 * Mirrors Python PinDialog(tk.Toplevel).
 */

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

public class PinDialog extends JDialog {

    // ── Result flag — read by caller after dialog closes ─────────────────────
    // True only if the correct PIN was entered and confirmed.
    public boolean verified = false;

    // ── Constructor ───────────────────────────────────────────────────────────
    PinDialog(JFrame parent) {
        super(parent, "Enter Passcode", true); // true = modal — blocks parent
        setResizable(false);
        getContentPane().setBackground(launcher.BG_COLOR);

        buildUI();

        pack();
        setLocationRelativeTo(parent); // Centre over the launcher window
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(launcher.BG_COLOR);
        panel.setBorder(BorderFactory.createEmptyBorder(24, 30, 20, 30));

        // ── Heading ───────────────────────────────────────────────────────────
        JLabel heading = new JLabel("🔒  Enter Passcode to Request a Game");
        heading.setFont(new Font("Georgia", Font.BOLD, 14));
        heading.setForeground(launcher.TEXT_COLOR);
        heading.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(heading);
        panel.add(Box.createVerticalStrut(6));

        // ── Sub-label ─────────────────────────────────────────────────────────
        JLabel sub = new JLabel("Ask a parent if you don't know the Passcode.");
        sub.setFont(launcher.FONT_SMALL);
        sub.setForeground(launcher.SUBTEXT_COLOR);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(sub);
        panel.add(Box.createVerticalStrut(14));

        // ── PIN field — masked with ● ─────────────────────────────────────────
        // JPasswordField handles secure char[] storage; getPassword() preferred over getText()
        JPasswordField pinField = new JPasswordField(12);
        pinField.setFont(new Font("Georgia", Font.PLAIN, 18));
        pinField.setBackground(Color.decode("#252540"));
        pinField.setForeground(launcher.TEXT_COLOR);
        pinField.setCaretColor(launcher.TEXT_COLOR);
        pinField.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        pinField.setEchoChar('●'); // Mask characters — mirrors Python show="●"
        pinField.setAlignmentX(Component.CENTER_ALIGNMENT);
        pinField.setMaximumSize(new Dimension(200, 44));
        panel.add(pinField);
        panel.add(Box.createVerticalStrut(6));

        // ── Error label — hidden until a wrong PIN is entered ─────────────────
        JLabel errorLbl = new JLabel(" "); // Non-empty to reserve layout space
        errorLbl.setFont(launcher.FONT_SMALL);
        errorLbl.setForeground(launcher.ACCENT_COLOR);
        errorLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(errorLbl);
        panel.add(Box.createVerticalStrut(12));

        // ── Buttons ───────────────────────────────────────────────────────────
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        btnRow.setBackground(launcher.BG_COLOR);

        JButton confirmBtn = launcher.makeButton("Confirm", launcher.REQUEST_COLOR);
        JButton cancelBtn  = launcher.makeButton("Cancel",  Color.decode("#333333"));

        btnRow.add(confirmBtn);
        btnRow.add(cancelBtn);
        panel.add(btnRow);

        add(panel);

        // ── Event handling ────────────────────────────────────────────────────

        // Shared submit logic — used by both the button and the Enter key binding
        Runnable onSubmit = () -> {
            String pin = new String(pinField.getPassword()).strip();

            if (pin.isEmpty()) {
                errorLbl.setText("⚠️  Please enter a PIN.");
                return;
            }

            if (launcher.verifyPin(pin)) {
                verified = true; // Correct — signal success to the caller
                dispose();       // Close dialog; setVisible() returns to caller
            } else {
                // Wrong PIN — clear field and let the child try again
                pinField.setText("");
                errorLbl.setText("❌  Incorrect PIN. Try again.");
                pinField.requestFocus();
            }
        };

        confirmBtn.addActionListener(e -> onSubmit.run());
        cancelBtn.addActionListener(e -> dispose()); // Cancel leaves verified=false

        // Enter key submits — mirrors Python self.bind("<Return>", ...)
        pinField.addActionListener(e -> onSubmit.run());

        // Auto-focus the PIN field when the dialog appears
        SwingUtilities.invokeLater(pinField::requestFocusInWindow);
    }
}
