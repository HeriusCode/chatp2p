package chatp2p.view;

import chatp2p.client.ChatClient;
import chatp2p.client.LanServerDiscovery;

import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

/** Dedicated modern login page with P2P server discovery and seamless registration switch. */
public final class LoginFrame extends JFrame {
    private final JTextField hostField;
    private final JTextField portField;
    private final JTextField usernameField;
    private final JPasswordField passwordField = new JPasswordField();
    private final JButton loginButton = new JButton("ĐĂNG NHẬP");
    private final JButton openRegisterButton = new JButton("Tạo tài khoản ngay");
    private final JButton discoverButton = new JButton("TÌM LAN");
    private final JLabel statusLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();
    private volatile ChatClient connectingClient;
    private volatile SwingWorker<ChatClient, Void> worker;

    public LoginFrame() {
        this("localhost", "5000", "");
    }

    LoginFrame(String host, String port, String username) {
        super("Chat P2P - Đăng nhập");
        hostField = new JTextField(host);
        portField = new JTextField(port);
        usernameField = new JTextField(username);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 640));
        setSize(940, 670);
        setLocationRelativeTo(null);
        setContentPane(AuthView.createShell(createForm(), "Đăng nhập an toàn",
                "Chat riêng và truyền file trực tiếp qua P2P."));
        getRootPane().setDefaultButton(loginButton);
        loginButton.addActionListener(event -> login());
        openRegisterButton.addActionListener(event -> openRegister());
        discoverButton.addActionListener(event -> discoverServer());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                cancelAttempt();
            }
        });
    }

    public static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        UIManager.put("Label.font", UiTheme.BODY);
        UIManager.put("Button.font", UiTheme.BODY_BOLD);
        UIManager.put("TextField.font", UiTheme.BODY);
        UIManager.put("PasswordField.font", UiTheme.BODY);
    }

    private JPanel createForm() {
        JPanel form = AuthView.form("Chào mừng trở lại!", "Đăng nhập tài khoản của bạn để tiếp tục.");

        // 1. Sleek Compact Server Row
        JPanel serverBar = AuthView.createServerBar(hostField, portField, discoverButton);
        form.add(serverBar);
        UiTheme.addVerticalGap(form, 14);

        // 2. Username & Password Fields
        AuthView.addField(form, "TÊN ĐĂNG NHẬP", usernameField);
        AuthView.addField(form, "MẬT KHẨU", passwordField);
        UiTheme.addVerticalGap(form, 4);

        // 3. Primary Action Button
        AuthView.styleModernPrimaryButton(loginButton);
        form.add(loginButton);
        UiTheme.addVerticalGap(form, 12);

        // 4. Switcher Link
        JPanel switcher = AuthView.createSwitcherRow("Chưa có tài khoản?", openRegisterButton);
        form.add(switcher);

        // 5. Progress and Status message
        AuthView.addProgressAndStatus(form, progressBar, statusLabel);
        return form;
    }

    private void login() {
        if (worker != null && !worker.isDone()) {
            return;
        }
        String host = hostField.getText().trim();
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());
        int port;
        try {
            port = AuthView.validate(host, portField.getText().trim(), username, password);
        } catch (IllegalArgumentException invalid) {
            AuthView.showStatus(statusLabel, invalid.getMessage(), UiTheme.DANGER);
            return;
        }

        setBusy(true);
        AuthView.showStatus(statusLabel, "Đang kết nối tới " + host + ":" + port + "...", UiTheme.SLATE);
        ChatClient attempt = new ChatClient();
        connectingClient = attempt;
        worker = new SwingWorker<>() {
            @Override
            protected ChatClient doInBackground() throws Exception {
                attempt.connect(host, port, username, password);
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
                    ChatClient client = get();
                    connectingClient = null;
                    dispose();
                    new MainFrame(client).setVisible(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    attempt.close();
                    AuthView.showStatus(statusLabel, "Quá trình đăng nhập bị gián đoạn.", UiTheme.DANGER);
                } catch (ExecutionException failure) {
                    attempt.close();
                    AuthView.showStatus(statusLabel, AuthView.friendly(failure.getCause()), UiTheme.DANGER);
                }
            }
        };
        worker.execute();
    }

    private void openRegister() {
        dispose();
        new RegisterFrame(
                hostField.getText().trim(), portField.getText().trim(),
                usernameField.getText().trim()).setVisible(true);
    }

    private void discoverServer() {
        discoverButton.setEnabled(false);
        discoverButton.setText("Đang quét...");
        AuthView.showStatus(statusLabel, "Đang phát UDP Broadcast tìm server trong mạng LAN...", UiTheme.SLATE);
        new SwingWorker<LanServerDiscovery.ServerEndpoint, Void>() {
            @Override
            protected LanServerDiscovery.ServerEndpoint doInBackground() throws Exception {
                return LanServerDiscovery.discover(Duration.ofSeconds(3));
            }

            @Override
            protected void done() {
                discoverButton.setEnabled(true);
                discoverButton.setText("TÌM LAN");
                try {
                    LanServerDiscovery.ServerEndpoint endpoint = get();
                    hostField.setText(endpoint.host());
                    portField.setText(Integer.toString(endpoint.port()));
                    AuthView.showStatus(statusLabel,
                            "Đã tìm thấy Server: " + endpoint.host() + ":" + endpoint.port(),
                            UiTheme.SUCCESS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    AuthView.showStatus(statusLabel, AuthView.friendly(failure.getCause()), UiTheme.DANGER);
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        for (java.awt.Component component : new java.awt.Component[]{
                hostField, portField, usernameField, passwordField,
                loginButton, openRegisterButton, discoverButton}) {
            component.setEnabled(!busy);
        }
        loginButton.setText(busy ? "ĐANG ĐĂNG NHẬP..." : "ĐĂNG NHẬP");
        progressBar.setVisible(busy);
    }

    private void cancelAttempt() {
        if (worker != null) {
            worker.cancel(true);
        }
        if (connectingClient != null) {
            connectingClient.close();
        }
    }
}
