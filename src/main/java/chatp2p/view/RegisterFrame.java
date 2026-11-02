package chatp2p.view;

import chatp2p.client.ChatClient;
import chatp2p.client.LanServerDiscovery;

import java.awt.Dimension;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

/** Dedicated modern account registration page with password confirmation and instant login switch. */
public final class RegisterFrame extends JFrame {
    private final JTextField hostField;
    private final JTextField portField;
    private final JTextField usernameField;
    private final JPasswordField passwordField = new JPasswordField();
    private final JPasswordField confirmationField = new JPasswordField();
    private final JButton registerButton = new JButton("ĐĂNG KÝ TÀI KHOẢN");
    private final JButton backButton = new JButton("Đăng nhập ngay");
    private final JButton discoverButton = new JButton("TÌM LAN");
    private final JLabel statusLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();
    private volatile SwingWorker<Void, Void> worker;

    RegisterFrame(String host, String port, String username) {
        super("Chat P2P - Đăng ký");
        hostField = new JTextField(host);
        portField = new JTextField(port);
        usernameField = new JTextField(username);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 640));
        setSize(940, 670);
        setLocationRelativeTo(null);
        setContentPane(AuthView.createShell(createForm(), "Tạo tài khoản mới",
                "Mật khẩu được bảo vệ an toàn bằng PBKDF2 và muối salt."));
        getRootPane().setDefaultButton(registerButton);
        registerButton.addActionListener(event -> register());
        backButton.addActionListener(event -> openLogin());
        discoverButton.addActionListener(event -> discoverServer());
    }

    private JPanel createForm() {
        JPanel form = AuthView.form("Tạo tài khoản mới", "Đăng ký để bắt đầu kết nối P2P với bạn bè.");

        // 1. Compact Server Bar
        JPanel serverBar = AuthView.createServerBar(hostField, portField, discoverButton);
        form.add(serverBar);
        UiTheme.addVerticalGap(form, 12);

        // 2. Form fields
        AuthView.addField(form, "TÊN ĐĂNG NHẬP", usernameField);
        AuthView.addField(form, "MẬT KHẨU", passwordField);
        AuthView.addField(form, "XÁC NHẬN MẬT KHẨU", confirmationField);
        UiTheme.addVerticalGap(form, 4);

        // 3. Primary Register Button
        AuthView.styleModernPrimaryButton(registerButton);
        form.add(registerButton);
        UiTheme.addVerticalGap(form, 12);

        // 4. Switcher Link back to login
        JPanel switcher = AuthView.createSwitcherRow("Đã có tài khoản?", backButton);
        form.add(switcher);

        // 5. Progress & Status
        AuthView.addProgressAndStatus(form, progressBar, statusLabel);
        return form;
    }

    private void register() {
        if (worker != null && !worker.isDone()) {
            return;
        }
        String host = hostField.getText().trim();
        String username = usernameField.getText().trim();
        char[] passwordChars = passwordField.getPassword();
        char[] confirmation = confirmationField.getPassword();
        String password = new String(passwordChars);
        int port;
        try {
            port = AuthView.validate(host, portField.getText().trim(), username, password);
            if (!Arrays.equals(passwordChars, confirmation)) {
                throw new IllegalArgumentException("Mật khẩu xác nhận không khớp.");
            }
        } catch (IllegalArgumentException invalid) {
            AuthView.showStatus(statusLabel, invalid.getMessage(), UiTheme.DANGER);
            Arrays.fill(passwordChars, '\0');
            Arrays.fill(confirmation, '\0');
            return;
        }
        Arrays.fill(passwordChars, '\0');
        Arrays.fill(confirmation, '\0');

        setBusy(true);
        AuthView.showStatus(statusLabel, "Đang đăng ký tài khoản...", UiTheme.SLATE);
        worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                ChatClient.registerAccount(host, port, username, password);
                return null;
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    get();
                    dispose();
                    LoginFrame login = new LoginFrame(host, Integer.toString(port), username);
                    login.setVisible(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    AuthView.showStatus(statusLabel, "Quá trình đăng ký bị gián đoạn.", UiTheme.DANGER);
                } catch (ExecutionException failure) {
                    AuthView.showStatus(statusLabel, AuthView.friendly(failure.getCause()), UiTheme.DANGER);
                }
            }
        };
        worker.execute();
    }

    private void openLogin() {
        dispose();
        new LoginFrame(hostField.getText().trim(), portField.getText().trim(),
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
                hostField, portField, usernameField, passwordField, confirmationField,
                registerButton, backButton, discoverButton}) {
            component.setEnabled(!busy);
        }
        registerButton.setText(busy ? "ĐANG ĐĂNG KÝ..." : "ĐĂNG KÝ TÀI KHOẢN");
        progressBar.setVisible(busy);
    }
}
