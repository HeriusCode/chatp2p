package chatp2p.view;

import chatp2p.client.ChatClient;
import chatp2p.client.ConnectionState;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

public final class MainFrame extends JFrame {
    private final ChatClient client;
    private final String host;
    private final int port;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final JLabel connectionDot = new JLabel("●");
    private final JLabel connectionText = new JLabel();
    private final JLabel detailText = new JLabel();
    private final JButton pingButton = new JButton("Kiểm tra Ping");
    private final Timer connectionTimer;

    public MainFrame(ChatClient client, String host, int port) {
        super("Chat P2P - " + client.clientName());
        this.client = client;
        this.host = host;
        this.port = port;
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));
        setSize(1080, 690);
        setLocationRelativeTo(null);
        setContentPane(createContent());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeClient();
            }
        });
        connectionTimer = new Timer(1_000, event -> refreshConnectionState());
        connectionTimer.start();
        refreshConnectionState();
    }

    private JPanel createContent() {
        JPanel root = UiTheme.panel(new BorderLayout(), UiTheme.BACKGROUND);
        root.add(createHeader(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, createUsersPanel(), createWorkspace());
        split.setDividerLocation(250);
        split.setDividerSize(1);
        split.setBorder(null);
        split.setEnabled(false);
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    private JPanel createHeader() {
        JPanel header = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(15, 24, 15, 24)));

        JPanel brand = UiTheme.panel(new FlowLayout(FlowLayout.LEFT, 10, 0), UiTheme.WHITE);
        JLabel logo = UiTheme.label("●", new Font("Segoe UI", Font.BOLD, 22), UiTheme.PRIMARY);
        JLabel title = UiTheme.label("Chat P2P", new Font("Segoe UI", Font.BOLD, 20), UiTheme.NAVY);
        brand.add(logo);
        brand.add(title);

        JPanel account = UiTheme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 1), UiTheme.WHITE);
        JLabel onlineDot = UiTheme.label("●", UiTheme.BODY, UiTheme.SUCCESS);
        JLabel username = UiTheme.label(client.clientName(), UiTheme.BODY_BOLD, UiTheme.NAVY);
        account.add(username);
        account.add(onlineDot);
        header.add(brand, BorderLayout.WEST);
        header.add(account, BorderLayout.EAST);
        return header;
    }

    private JPanel createUsersPanel() {
        JPanel panel = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UiTheme.BORDER));

        JLabel heading = UiTheme.label("NGƯỜI DÙNG", new Font("Segoe UI", Font.BOLD, 12), UiTheme.SLATE);
        heading.setBorder(BorderFactory.createEmptyBorder(22, 20, 14, 20));
        panel.add(heading, BorderLayout.NORTH);

        JPanel list = UiTheme.panel(null, UiTheme.WHITE);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.add(createUserRow(client.clientName(), "Thiết bị này", true));
        JPanel notice = UiTheme.panel(new BorderLayout(), new Color(248, 250, 252));
        notice.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(14, 18, 14, 18)));
        list.add(notice);
        panel.add(new JScrollPane(list,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createUserRow(String name, String description, boolean online) {
        JPanel row = UiTheme.panel(new BorderLayout(12, 0), UiTheme.WHITE);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
        row.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));

        JLabel avatar = new JLabel(name.substring(0, 1).toUpperCase(), SwingConstants.CENTER);
        avatar.setFont(new Font("Segoe UI", Font.BOLD, 16));
        avatar.setForeground(UiTheme.PRIMARY_DARK);
        avatar.setOpaque(true);
        avatar.setBackground(new Color(219, 234, 254));
        avatar.setPreferredSize(new Dimension(42, 42));

        JPanel labels = UiTheme.panel(null, UiTheme.WHITE);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        labels.add(UiTheme.label(name, UiTheme.BODY_BOLD, UiTheme.NAVY));
        labels.add(UiTheme.label(description, new Font("Segoe UI", Font.PLAIN, 12), UiTheme.MUTED));
        JLabel dot = UiTheme.label("●", new Font("Segoe UI", Font.BOLD, 11),
                online ? UiTheme.SUCCESS : UiTheme.MUTED);
        row.add(avatar, BorderLayout.WEST);
        row.add(labels, BorderLayout.CENTER);
        row.add(dot, BorderLayout.EAST);
        return row;
    }

    private JPanel createWorkspace() {
        JPanel workspace = UiTheme.panel(new BorderLayout(), UiTheme.BACKGROUND);
        workspace.add(createChatHeader(), BorderLayout.NORTH);
        workspace.add(createConnectionCard(), BorderLayout.CENTER);
        workspace.add(createDisabledComposer(), BorderLayout.SOUTH);
        return workspace;
    }

    private JPanel createChatHeader() {
        JPanel header = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(14, 20, 14, 20)));
        JLabel title = UiTheme.label("Kết nối mạng", new Font("Segoe UI", Font.BOLD, 17), UiTheme.NAVY);
        JLabel subtitle = UiTheme.label(host + ":" + port, new Font("Segoe UI", Font.PLAIN, 12), UiTheme.MUTED);
        JPanel labels = UiTheme.panel(null, UiTheme.WHITE);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        labels.add(title);
        labels.add(subtitle);
        header.add(labels, BorderLayout.WEST);

        JButton disconnect = new JButton("Ngắt kết nối");
        UiTheme.styleSecondaryButton(disconnect);
        disconnect.setForeground(UiTheme.DANGER);
        disconnect.addActionListener(event -> disconnectAndReturn());
        header.add(disconnect, BorderLayout.EAST);
        return header;
    }

    private JPanel createConnectionCard() {
        JPanel area = UiTheme.panel(new GridBagLayout(), UiTheme.BACKGROUND);
        JPanel card = UiTheme.panel(null, UiTheme.WHITE);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiTheme.BORDER),
                BorderFactory.createEmptyBorder(34, 48, 34, 48)));
        card.setPreferredSize(new Dimension(470, 310));

        connectionDot.setFont(new Font("Segoe UI", Font.BOLD, 46));
        connectionDot.setAlignmentX(CENTER_ALIGNMENT);
        connectionText.setFont(new Font("Segoe UI", Font.BOLD, 22));
        connectionText.setForeground(UiTheme.NAVY);
        connectionText.setAlignmentX(CENTER_ALIGNMENT);
        detailText.setFont(UiTheme.SUBTITLE);
        detailText.setForeground(UiTheme.SLATE);
        detailText.setAlignmentX(CENTER_ALIGNMENT);


        UiTheme.stylePrimaryButton(pingButton);
        pingButton.setAlignmentX(CENTER_ALIGNMENT);
        pingButton.addActionListener(event -> pingServer());

        card.add(connectionDot);
        UiTheme.addVerticalGap(card, 8);
        card.add(connectionText);
        UiTheme.addVerticalGap(card, 6);
        card.add(detailText);
        UiTheme.addVerticalGap(card, 24);
        UiTheme.addVerticalGap(card, 25);
        card.add(pingButton);
        area.add(card);
        return area;
    }

    private JPanel createDisabledComposer() {
        JPanel composer = UiTheme.panel(new BorderLayout(10, 0), UiTheme.WHITE);
        composer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(14, 18, 14, 18)));
        JTextField message = new JTextField("Nhập tin nhắn...");
        UiTheme.styleTextField(message);
        message.setEnabled(false);
        JButton file = new JButton("FILE");
        JButton send = new JButton("GỬI");
        UiTheme.styleSecondaryButton(file);
        UiTheme.stylePrimaryButton(send);
        file.setEnabled(false);
        send.setEnabled(false);
        JPanel actions = UiTheme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0), UiTheme.WHITE);
        actions.add(file);
        actions.add(send);
        composer.add(message, BorderLayout.CENTER);
        composer.add(actions, BorderLayout.EAST);
        return composer;
    }

    private void pingServer() {
        UiTheme.setEnabled(pingButton, false);
        pingButton.setText("Đang ping...");
        new SwingWorker<Duration, Void>() {
            @Override
            protected Duration doInBackground() throws Exception {
                return client.ping();
            }

            @Override
            protected void done() {
                pingButton.setText("Kiểm tra Ping");
                try {
                    Duration roundTrip = get();
                    detailText.setText("PONG • " + roundTrip.toMillis() + " ms • " + host + ":" + port);
                    refreshConnectionState();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    markDisconnected("Ping bị gián đoạn");
                } catch (ExecutionException failure) {
                    markDisconnected(friendlyMessage(failure.getCause()));
                }
            }
        }.execute();
    }

    private void refreshConnectionState() {
        if (client.state() == ConnectionState.CONNECTED) {
            connectionDot.setForeground(UiTheme.SUCCESS);
            connectionText.setText("Đã kết nối Server");
            if (detailText.getText().isBlank()) {
                detailText.setText(host + ":" + port);
            }
            UiTheme.setEnabled(pingButton, true);
        } else {
            markDisconnected("Kết nối đã đóng");
        }
    }

    private void markDisconnected(String detail) {
        connectionDot.setForeground(UiTheme.DANGER);
        connectionText.setText("Mất kết nối Server");
        detailText.setText(detail);
        UiTheme.setEnabled(pingButton, false);
    }

    private void disconnectAndReturn() {
        closeClient();
        dispose();
        SwingUtilities.invokeLater(() -> new ConnectionFrame().setVisible(true));
    }

    private void closeClient() {
        if (closed.compareAndSet(false, true)) {
            connectionTimer.stop();
            client.close();
        }
    }

    private static String friendlyMessage(Throwable failure) {
        if (failure instanceof IOException && failure.getMessage() != null) {
            return failure.getMessage();
        }
        return "Không nhận được phản hồi từ Server";
    }
}

