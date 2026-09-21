package chatp2p.view;

import chatp2p.client.ChatClient;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

/** Phase 1 connection screen shown when ClientMain is launched without arguments. */
public final class ConnectionFrame extends JFrame {
    private final JTextField hostField = new JTextField("localhost");
    private final JTextField portField = new JTextField("5000");
    private final JTextField nameField = new JTextField("client-a");
    private final JButton connectButton = new JButton("KẾT NỐI");
    private final JLabel statusLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();
    private volatile ChatClient connectingClient;
    private volatile SwingWorker<ChatClient, Void> connectWorker;

    public ConnectionFrame() {
        super("Chat P2P - Kết nối");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(860, 540));
        setSize(920, 580);
        setLocationRelativeTo(null);
        setContentPane(createContent());
        getRootPane().setDefaultButton(connectButton);
        connectButton.addActionListener(event -> connect());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                cancelConnectionAttempt();
            }
        });
    }

    public static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing's cross-platform look and feel remains a safe fallback.
        }
        UIManager.put("Label.font", UiTheme.BODY);
        UIManager.put("Button.font", UiTheme.BODY_BOLD);
        UIManager.put("TextField.font", UiTheme.BODY);
    }

    private JPanel createContent() {
        JPanel root = UiTheme.panel(new GridBagLayout(), UiTheme.BACKGROUND);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;

        constraints.gridx = 0;
        constraints.weightx = 0.9;
        root.add(createBrandPanel(), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1.1;
        root.add(createFormPanel(), constraints);
        return root;
    }

    private JPanel createBrandPanel() {
        JPanel brand = UiTheme.panel(new GridBagLayout(), UiTheme.NAVY);
        brand.setBorder(BorderFactory.createEmptyBorder(50, 48, 50, 48));

        JPanel content = UiTheme.panel(null, UiTheme.NAVY);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel mark = UiTheme.label("●  CHAT P2P", new Font("Segoe UI", Font.BOLD, 17),
                new Color(96, 165, 250));
        JLabel title = UiTheme.label(
                "<html>Kết nối trực tiếp.<br>Chia sẻ nhanh chóng.</html>",
                new Font("Segoe UI", Font.BOLD, 31), UiTheme.WHITE);
        JLabel description = UiTheme.label(
                "<html>Ứng dụng chat Client–Server kết hợp P2P",
                UiTheme.SUBTITLE, new Color(203, 213, 225));

        for (JLabel label : new JLabel[]{mark, title, description}) {
            UiTheme.leftAlign(label);
        }
        content.add(mark);
        UiTheme.addVerticalGap(content, 52);
        content.add(title);
        UiTheme.addVerticalGap(content, 22);
        content.add(description);
        UiTheme.addVerticalGap(content, 70);
        brand.add(content);
        return brand;
    }

    private JPanel createFormPanel() {
        JPanel wrapper = UiTheme.panel(new GridBagLayout(), UiTheme.WHITE);
        JPanel form = UiTheme.panel(null, UiTheme.WHITE);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setPreferredSize(new Dimension(340, 430));

        JLabel heading = UiTheme.label("Kết nối máy chủ", UiTheme.TITLE, UiTheme.NAVY);
        JLabel subtitle = UiTheme.label("Nhập thông tin Central Server để bắt đầu.",
                UiTheme.SUBTITLE, UiTheme.SLATE);
        UiTheme.leftAlign(heading);
        UiTheme.leftAlign(subtitle);
        form.add(heading);
        UiTheme.addVerticalGap(form, 8);
        form.add(subtitle);
        UiTheme.addVerticalGap(form, 28);

        addField(form, "SERVER IP / HOST", hostField);
        UiTheme.addVerticalGap(form, 15);
        addField(form, "SERVER PORT", portField);
        UiTheme.addVerticalGap(form, 15);
        addField(form, "TÊN CLIENT", nameField);
        UiTheme.addVerticalGap(form, 24);

        UiTheme.stylePrimaryButton(connectButton);
        connectButton.setAlignmentX(LEFT_ALIGNMENT);
        connectButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        form.add(connectButton);
        UiTheme.addVerticalGap(form, 12);

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        progressBar.setBorderPainted(false);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
        progressBar.setForeground(UiTheme.PRIMARY);
        form.add(progressBar);
        UiTheme.addVerticalGap(form, 7);

        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        statusLabel.setForeground(UiTheme.DANGER);
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        form.add(statusLabel);
        wrapper.add(form);
        return wrapper;
    }

    private void addField(JPanel form, String labelText, JTextField field) {
        JLabel label = UiTheme.label(labelText, new Font("Segoe UI", Font.BOLD, 12), UiTheme.SLATE);
        UiTheme.leftAlign(label);
        UiTheme.styleTextField(field);
        field.setAlignmentX(LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        form.add(label);
        UiTheme.addVerticalGap(form, 7);
        form.add(field);
    }

    private void connect() {
        if (connectWorker != null && !connectWorker.isDone()) {
            return;
        }

        String host = hostField.getText().trim();
        String clientName = nameField.getText().trim();
        int port;
        try {
            port = parsePort(portField.getText().trim());
            if (host.isBlank()) {
                throw new IllegalArgumentException("Server host không được để trống.");
            }
            if (clientName.isBlank()) {
                throw new IllegalArgumentException("Tên client không được để trống.");
            }
        } catch (IllegalArgumentException invalidInput) {
            showError(invalidInput.getMessage());
            return;
        }

        setBusy(true);
        statusLabel.setForeground(UiTheme.SLATE);
        statusLabel.setText("Đang kết nối tới " + host + ":" + port + "...");
        ChatClient attempt = new ChatClient();
        connectingClient = attempt;
        connectWorker = new SwingWorker<>() {
            @Override
            protected ChatClient doInBackground() throws Exception {
                attempt.connect(host, port, clientName);
                return attempt;
            }

            @Override
            protected void done() {
                setBusy(false);
                if (isCancelled()) {
                    attempt.close();
                    return;
                }
                try {
                    ChatClient connectedClient = get();
                    connectingClient = null;
                    dispose();
                    new MainFrame(connectedClient, host, port).setVisible(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    attempt.close();
                    showError("Quá trình kết nối bị gián đoạn.");
                } catch (ExecutionException failure) {
                    attempt.close();
                    showError(friendlyMessage(failure.getCause()));
                }
            }
        };
        connectWorker.execute();
    }

    private void setBusy(boolean busy) {
        hostField.setEnabled(!busy);
        portField.setEnabled(!busy);
        nameField.setEnabled(!busy);
        connectButton.setEnabled(!busy);
        connectButton.setText(busy ? "ĐANG KẾT NỐI..." : "KẾT NỐI");
        progressBar.setVisible(busy);
    }

    private void showError(String message) {
        statusLabel.setForeground(UiTheme.DANGER);
        statusLabel.setText("<html>" + escapeHtml(message) + "</html>");
    }

    private void cancelConnectionAttempt() {
        SwingWorker<ChatClient, Void> worker = connectWorker;
        if (worker != null) {
            worker.cancel(true);
        }
        ChatClient attempt = connectingClient;
        if (attempt != null) {
            attempt.close();
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Port phải nằm trong khoảng 1–65535.");
            }
            return port;
        } catch (NumberFormatException invalidPort) {
            throw new IllegalArgumentException("Port phải là một số nguyên.", invalidPort);
        }
    }

    private static String friendlyMessage(Throwable failure) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return "Không thể kết nối tới Server.";
        }
        return "Không thể kết nối: " + failure.getMessage();
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

