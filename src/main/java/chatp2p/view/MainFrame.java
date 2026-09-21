package chatp2p.view;

import chatp2p.client.ChatClient;
import chatp2p.client.ClientEventListener;
import chatp2p.client.ConnectionState;
import chatp2p.client.TransferHandle;
import chatp2p.model.ChatMessage;
import chatp2p.model.FileInfo;
import chatp2p.model.PeerInfo;
import chatp2p.model.TransferDirection;
import chatp2p.model.TransferProgress;
import chatp2p.model.TransferState;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/** Main client UI with live user discovery, direct P2P chat and file transfer. */
public final class MainFrame extends JFrame {
    private static final DateTimeFormatter MESSAGE_TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final ChatClient client;
    private final String host;
    private final int port;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final DefaultListModel<PeerInfo> userModel = new DefaultListModel<>();
    private final JList<PeerInfo> userList = new JList<>(userModel);
    private final JLabel userCount = new JLabel("0 online");
    private final JLabel chatTitle = new JLabel("Chọn người dùng để trò chuyện");
    private final JLabel chatSubtitle = new JLabel("Danh sách online được cập nhật từ Server");
    private final JPanel messagesPanel = new JPanel();
    private final JScrollPane messagesScroll = new JScrollPane(messagesPanel);
    private final JTextField messageField = new JTextField();
    private final JButton fileButton = new JButton("FILE");
    private final JButton sendButton = new JButton("GỬI");
    private final JPanel transferPanel = new JPanel(new BorderLayout(10, 5));
    private final JLabel transferTitle = new JLabel();
    private final JLabel transferDetail = new JLabel();
    private final JProgressBar transferProgress = new JProgressBar(0, 100);
    private final JButton cancelTransferButton = new JButton("HỦY");
    private final Map<String, List<ChatMessage>> histories = new HashMap<>();
    private final Map<UUID, TransferHandle> outgoingTransfers = new HashMap<>();
    private final Timer connectionTimer;
    private volatile PeerInfo selectedPeer;
    private volatile UUID displayedTransferId;

    private final ClientEventListener eventListener = new ClientEventListener() {
        @Override
        public void onUsersChanged(List<PeerInfo> users) {
            SwingUtilities.invokeLater(() -> updateUsers(users));
        }

        @Override
        public void onChatReceived(ChatMessage message) {
            SwingUtilities.invokeLater(() -> addMessage(message));
        }

        @Override
        public boolean onFileOffered(FileInfo file) {
            AtomicBoolean accepted = new AtomicBoolean(false);
            Runnable prompt = () -> {
                String message = "File: " + file.fileName()
                        + "\nKích thước: " + formatBytes(file.size())
                        + "\nTừ: " + file.sender()
                        + "\n\nBạn có muốn nhận file này không?";
                accepted.set(JOptionPane.showConfirmDialog(
                        MainFrame.this,
                        message,
                        "File đến từ " + file.sender(),
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION);
            };
            if (SwingUtilities.isEventDispatchThread()) {
                prompt.run();
            } else {
                try {
                    SwingUtilities.invokeAndWait(prompt);
                } catch (Exception failure) {
                    return false;
                }
            }
            return accepted.get();
        }

        @Override
        public void onTransferProgress(TransferProgress progress) {
            SwingUtilities.invokeLater(() -> showTransferProgress(progress));
        }

        @Override
        public void onTransferCompleted(FileInfo file, Path savedPath) {
            SwingUtilities.invokeLater(() -> {
                if (savedPath != null) {
                    JOptionPane.showMessageDialog(
                            MainFrame.this,
                            "Đã nhận file thành công:\n" + savedPath.toAbsolutePath(),
                            "Nhận file hoàn tất",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            });
        }

        @Override
        public void onTransferFailed(FileInfo file, String reason) {
            SwingUtilities.invokeLater(() -> {
                transferTitle.setForeground(UiTheme.DANGER);
                transferDetail.setText(reason);
            });
        }

        @Override
        public void onConnectionError(String message) {
            SwingUtilities.invokeLater(() -> showError(message));
        }
    };

    public MainFrame(ChatClient client, String host, int port) {
        super("Chat P2P - " + client.clientName());
        this.client = client;
        this.host = host;
        this.port = port;
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(920, 620));
        setSize(1100, 720);
        setLocationRelativeTo(null);
        setContentPane(createContent());
        configureActions();
        client.addEventListener(eventListener);
        connectionTimer = new Timer(1_000, event -> refreshConnectionState());
        connectionTimer.start();
        refreshConnectionState();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeClient();
            }
        });
    }

    private JPanel createContent() {
        JPanel root = UiTheme.panel(new BorderLayout(), UiTheme.BACKGROUND);
        root.add(createHeader(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, createUsersPanel(), createChatWorkspace());
        split.setDividerLocation(270);
        split.setDividerSize(1);
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    private JPanel createHeader() {
        JPanel header = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(14, 22, 14, 22)));

        JPanel brand = UiTheme.panel(new FlowLayout(FlowLayout.LEFT, 9, 0), UiTheme.WHITE);
        brand.add(UiTheme.label("●", new Font("Segoe UI", Font.BOLD, 22), UiTheme.PRIMARY));
        brand.add(UiTheme.label("Chat P2P", new Font("Segoe UI", Font.BOLD, 20), UiTheme.NAVY));
        JLabel peerBadge = UiTheme.label(
                "PEER : " + client.peerPort(), new Font("Segoe UI", Font.BOLD, 10), UiTheme.PRIMARY_DARK);
        peerBadge.setOpaque(true);
        peerBadge.setBackground(new Color(219, 234, 254));
        peerBadge.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        brand.add(peerBadge);

        JPanel account = UiTheme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 1), UiTheme.WHITE);
        account.add(UiTheme.label(client.clientName(), UiTheme.BODY_BOLD, UiTheme.NAVY));
        account.add(UiTheme.label("●", UiTheme.BODY, UiTheme.SUCCESS));
        JButton disconnect = new JButton("Đăng xuất");
        UiTheme.styleSecondaryButton(disconnect);
        disconnect.setForeground(UiTheme.DANGER);
        disconnect.addActionListener(event -> disconnectAndReturn());
        account.add(disconnect);
        header.add(brand, BorderLayout.WEST);
        header.add(account, BorderLayout.EAST);
        return header;
    }

    private JPanel createUsersPanel() {
        JPanel panel = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UiTheme.BORDER));

        JPanel heading = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        heading.setBorder(BorderFactory.createEmptyBorder(18, 18, 12, 18));
        heading.add(UiTheme.label("NGƯỜI DÙNG ONLINE",
                new Font("Segoe UI", Font.BOLD, 12), UiTheme.SLATE), BorderLayout.WEST);
        userCount.setFont(new Font("Segoe UI", Font.BOLD, 11));
        userCount.setForeground(UiTheme.SUCCESS);
        heading.add(userCount, BorderLayout.EAST);

        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setFixedCellHeight(64);
        userList.setBackground(UiTheme.WHITE);
        userList.setSelectionBackground(new Color(239, 246, 255));
        userList.setCellRenderer(new UserRenderer());
        JScrollPane scroll = new JScrollPane(userList);
        scroll.setBorder(null);
        panel.add(heading, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        JLabel server = UiTheme.label(
                "Server: " + host + ":" + port,
                new Font("Segoe UI", Font.PLAIN, 11), UiTheme.MUTED);
        server.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(11, 16, 11, 16)));
        panel.add(server, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createChatWorkspace() {
        JPanel workspace = UiTheme.panel(new BorderLayout(), UiTheme.BACKGROUND);
        workspace.add(createChatHeader(), BorderLayout.NORTH);

        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(UiTheme.BACKGROUND);
        messagesPanel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        messagesScroll.setBorder(null);
        messagesScroll.getVerticalScrollBar().setUnitIncrement(18);
        workspace.add(messagesScroll, BorderLayout.CENTER);

        JPanel bottom = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        bottom.add(createTransferPanel(), BorderLayout.NORTH);
        bottom.add(createComposer(), BorderLayout.SOUTH);
        workspace.add(bottom, BorderLayout.SOUTH);
        return workspace;
    }

    private JPanel createChatHeader() {
        JPanel header = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(13, 20, 13, 20)));
        JPanel labels = UiTheme.panel(null, UiTheme.WHITE);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        chatTitle.setFont(new Font("Segoe UI", Font.BOLD, 17));
        chatTitle.setForeground(UiTheme.NAVY);
        chatSubtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        chatSubtitle.setForeground(UiTheme.MUTED);
        labels.add(chatTitle);
        labels.add(chatSubtitle);
        header.add(labels, BorderLayout.WEST);
        return header;
    }

    private JPanel createTransferPanel() {
        transferPanel.setBackground(new Color(239, 246, 255));
        transferPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(191, 219, 254)),
                BorderFactory.createEmptyBorder(9, 16, 9, 16)));
        JPanel labels = UiTheme.panel(null, transferPanel.getBackground());
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        transferTitle.setFont(UiTheme.BODY_BOLD);
        transferTitle.setForeground(UiTheme.NAVY);
        transferDetail.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        transferDetail.setForeground(UiTheme.SLATE);
        labels.add(transferTitle);
        labels.add(transferDetail);
        transferProgress.setStringPainted(true);
        transferProgress.setForeground(UiTheme.PRIMARY);
        transferProgress.setPreferredSize(new Dimension(250, 19));
        UiTheme.styleSecondaryButton(cancelTransferButton);
        cancelTransferButton.setForeground(UiTheme.DANGER);
        JPanel right = UiTheme.panel(new FlowLayout(FlowLayout.RIGHT, 9, 4), transferPanel.getBackground());
        right.add(transferProgress);
        right.add(cancelTransferButton);
        transferPanel.add(labels, BorderLayout.CENTER);
        transferPanel.add(right, BorderLayout.EAST);
        transferPanel.setVisible(false);
        return transferPanel;
    }

    private JPanel createComposer() {
        JPanel composer = UiTheme.panel(new BorderLayout(10, 0), UiTheme.WHITE);
        composer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(13, 16, 13, 16)));
        messageField.setText("");
        UiTheme.styleTextField(messageField);
        JPanel actions = UiTheme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0), UiTheme.WHITE);
        UiTheme.styleSecondaryButton(fileButton);
        UiTheme.stylePrimaryButton(sendButton);
        actions.add(fileButton);
        actions.add(sendButton);
        composer.add(messageField, BorderLayout.CENTER);
        composer.add(actions, BorderLayout.EAST);
        setComposerEnabled(false);
        return composer;
    }

    private void configureActions() {
        userList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selectPeer(userList.getSelectedValue());
            }
        });
        sendButton.addActionListener(event -> sendMessage());
        messageField.addActionListener(event -> sendMessage());
        fileButton.addActionListener(event -> chooseAndSendFile());
        cancelTransferButton.addActionListener(event -> cancelDisplayedTransfer());
    }

    private void updateUsers(List<PeerInfo> users) {
        String selectedName = selectedPeer == null ? null : selectedPeer.username();
        userModel.clear();
        PeerInfo restored = null;
        for (PeerInfo peer : users) {
            userModel.addElement(peer);
            if (peer.username().equals(selectedName)) {
                restored = peer;
            }
        }
        userCount.setText(users.size() + " online");
        if (restored != null) {
            userList.setSelectedValue(restored, true);
            selectedPeer = restored;
        } else if (selectedName != null) {
            selectPeer(null);
        }
    }

    private void selectPeer(PeerInfo peer) {
        selectedPeer = peer;
        if (peer == null) {
            chatTitle.setText("Chọn người dùng để trò chuyện");
            chatSubtitle.setText("Danh sách online được cập nhật từ Server");
            setComposerEnabled(false);
        } else {
            chatTitle.setText("Chat với " + peer.username());
            chatSubtitle.setText("P2P TCP • " + peer.host() + ":" + peer.port());
            setComposerEnabled(client.state() == ConnectionState.CONNECTED);
        }
        renderHistory();
    }

    private void sendMessage() {
        PeerInfo peer = selectedPeer;
        String text = messageField.getText().trim();
        if (peer == null || text.isEmpty()) {
            return;
        }
        sendButton.setEnabled(false);
        new SwingWorker<ChatMessage, Void>() {
            @Override
            protected ChatMessage doInBackground() throws Exception {
                return client.sendChat(peer.username(), text);
            }

            @Override
            protected void done() {
                sendButton.setEnabled(selectedPeer != null);
                try {
                    ChatMessage sent = get();
                    messageField.setText("");
                    addMessage(sent);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    showError(friendlyMessage(failure.getCause()));
                }
            }
        }.execute();
    }

    private void chooseAndSendFile() {
        PeerInfo peer = selectedPeer;
        if (peer == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn file gửi cho " + peer.username());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        fileButton.setEnabled(false);
        new SwingWorker<TransferHandle, Void>() {
            @Override
            protected TransferHandle doInBackground() throws Exception {
                return client.sendFile(peer.username(), file);
            }

            @Override
            protected void done() {
                fileButton.setEnabled(selectedPeer != null);
                try {
                    TransferHandle handle = get();
                    outgoingTransfers.put(handle.fileId(), handle);
                    displayedTransferId = handle.fileId();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    showError(friendlyMessage(failure.getCause()));
                }
            }
        }.execute();
    }

    private void addMessage(ChatMessage message) {
        String peerName = message.sender().equals(client.clientName())
                ? message.receiver() : message.sender();
        histories.computeIfAbsent(peerName, ignored -> new ArrayList<>()).add(message);
        if (selectedPeer != null && selectedPeer.username().equals(peerName)) {
            messagesPanel.add(createMessageBubble(message));
            messagesPanel.add(Box.createVerticalStrut(8));
            messagesPanel.revalidate();
            messagesPanel.repaint();
            scrollToBottom();
        }
    }

    private void renderHistory() {
        messagesPanel.removeAll();
        PeerInfo peer = selectedPeer;
        if (peer == null) {
            JLabel empty = UiTheme.label(
                    "Chọn một người dùng online ở bên trái để bắt đầu chat.",
                    UiTheme.SUBTITLE, UiTheme.MUTED);
            empty.setAlignmentX(Component.CENTER_ALIGNMENT);
            messagesPanel.add(Box.createVerticalGlue());
            messagesPanel.add(empty);
            messagesPanel.add(Box.createVerticalGlue());
        } else {
            for (ChatMessage message : histories.getOrDefault(peer.username(), List.of())) {
                messagesPanel.add(createMessageBubble(message));
                messagesPanel.add(Box.createVerticalStrut(8));
            }
            messagesPanel.add(Box.createVerticalGlue());
        }
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    private JPanel createMessageBubble(ChatMessage message) {
        boolean mine = message.sender().equals(client.clientName());
        JPanel row = UiTheme.panel(
                new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0),
                UiTheme.BACKGROUND);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        JPanel bubble = UiTheme.panel(null, mine ? UiTheme.PRIMARY : UiTheme.WHITE);
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(mine ? UiTheme.PRIMARY : UiTheme.BORDER),
                BorderFactory.createEmptyBorder(8, 11, 8, 11)));
        JLabel sender = UiTheme.label(
                mine ? "Bạn" : message.sender(),
                new Font("Segoe UI", Font.BOLD, 11),
                mine ? new Color(219, 234, 254) : UiTheme.SLATE);
        JLabel content = UiTheme.label(
                "<html><div style='width:320px'>" + escapeHtml(message.content()) + "</div></html>",
                UiTheme.BODY, mine ? UiTheme.WHITE : UiTheme.NAVY);
        JLabel time = UiTheme.label(
                MESSAGE_TIME.format(message.timestamp()),
                new Font("Segoe UI", Font.PLAIN, 10),
                mine ? new Color(219, 234, 254) : UiTheme.MUTED);
        if (mine) {
            sender.setAlignmentX(Component.RIGHT_ALIGNMENT);
            content.setAlignmentX(Component.RIGHT_ALIGNMENT);
            time.setAlignmentX(Component.RIGHT_ALIGNMENT);
        }
        bubble.add(sender);
        bubble.add(Box.createVerticalStrut(3));
        bubble.add(content);
        bubble.add(Box.createVerticalStrut(3));
        bubble.add(time);
        row.add(bubble);
        return row;
    }

    private void showTransferProgress(TransferProgress progress) {
        displayedTransferId = progress.file().fileId();
        transferPanel.setVisible(true);
        transferTitle.setForeground(UiTheme.NAVY);
        String action = progress.direction() == TransferDirection.SENDING ? "Đang gửi: " : "Đang nhận: ";
        transferTitle.setText(action + progress.file().fileName());
        transferProgress.setValue(progress.percent());
        transferDetail.setText(formatBytes(progress.transferredBytes()) + " / "
                + formatBytes(progress.file().size()) + "  •  "
                + formatSpeed(progress.bytesPerSecond()) + "  •  "
                + formatRemaining(progress.estimatedSeconds()));
        boolean cancellable = progress.direction() == TransferDirection.SENDING
                && (progress.state() == TransferState.WAITING
                || progress.state() == TransferState.TRANSFERRING);
        cancelTransferButton.setVisible(cancellable);
        if (progress.state() == TransferState.SUCCESS) {
            transferTitle.setText("Hoàn tất: " + progress.file().fileName());
            transferProgress.setValue(100);
            outgoingTransfers.remove(progress.file().fileId());
        } else if (progress.state() == TransferState.CANCELLED
                || progress.state() == TransferState.FAILED) {
            transferTitle.setForeground(UiTheme.DANGER);
            outgoingTransfers.remove(progress.file().fileId());
        }
        transferPanel.revalidate();
    }

    private void cancelDisplayedTransfer() {
        TransferHandle handle = outgoingTransfers.get(displayedTransferId);
        if (handle != null) {
            handle.cancel();
            cancelTransferButton.setEnabled(false);
        }
    }

    private void refreshConnectionState() {
        boolean connected = client.state() == ConnectionState.CONNECTED;
        if (!connected) {
            setComposerEnabled(false);
            chatSubtitle.setText("Mất kết nối tới Central Server");
            chatSubtitle.setForeground(UiTheme.DANGER);
        }
    }

    private void setComposerEnabled(boolean enabled) {
        messageField.setEnabled(enabled);
        sendButton.setEnabled(enabled);
        fileButton.setEnabled(enabled);
        messageField.setToolTipText(enabled ? null : "Hãy chọn một người dùng online");
    }

    private void disconnectAndReturn() {
        closeClient();
        dispose();
        SwingUtilities.invokeLater(() -> new ConnectionFrame().setVisible(true));
    }

    private void closeClient() {
        if (closed.compareAndSet(false, true)) {
            client.removeEventListener(eventListener);
            connectionTimer.stop();
            client.close();
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(
                this, message, "Lỗi mạng", JOptionPane.ERROR_MESSAGE);
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> messagesScroll.getVerticalScrollBar()
                .setValue(messagesScroll.getVerticalScrollBar().getMaximum()));
    }

    private static String friendlyMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null
                ? "Không thể hoàn thành thao tác mạng"
                : failure.getMessage();
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format("%.1f %s", value, units[unit]);
    }

    private static String formatSpeed(double bytesPerSecond) {
        return bytesPerSecond <= 0 ? "--" : formatBytes((long) bytesPerSecond) + "/s";
    }

    private static String formatRemaining(long seconds) {
        return seconds < 0 ? "Đang tính..." : "Còn " + seconds + " giây";
    }

    private static final class UserRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            PeerInfo peer = (PeerInfo) value;
            JPanel row = UiTheme.panel(new BorderLayout(11, 0),
                    isSelected ? new Color(239, 246, 255) : UiTheme.WHITE);
            row.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
            JLabel avatar = new JLabel(
                    peer.username().substring(0, 1).toUpperCase(), SwingConstants.CENTER);
            avatar.setFont(new Font("Segoe UI", Font.BOLD, 15));
            avatar.setForeground(UiTheme.PRIMARY_DARK);
            avatar.setOpaque(true);
            avatar.setBackground(new Color(219, 234, 254));
            avatar.setPreferredSize(new Dimension(40, 40));
            JPanel text = UiTheme.panel(null, row.getBackground());
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.add(UiTheme.label(peer.username(), UiTheme.BODY_BOLD, UiTheme.NAVY));
            text.add(UiTheme.label(peer.host() + ":" + peer.port(),
                    new Font("Segoe UI", Font.PLAIN, 11), UiTheme.MUTED));
            row.add(avatar, BorderLayout.WEST);
            row.add(text, BorderLayout.CENTER);
            row.add(UiTheme.label("●", new Font("Segoe UI", Font.BOLD, 10), UiTheme.SUCCESS),
                    BorderLayout.EAST);
            return row;
        }
    }
}
