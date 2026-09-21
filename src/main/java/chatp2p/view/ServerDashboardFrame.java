package chatp2p.view;

import chatp2p.server.ChatServer;
import chatp2p.server.ServerEvent;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** Server UI organized as configuration, online users and live server log. */
public final class ServerDashboardFrame extends JFrame {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JTextField serverIpField = new JTextField(detectServerIp());
    private final JTextField portField = new JTextField("5000");
    private final JButton startButton = new JButton("START");
    private final JButton stopButton = new JButton("STOP");
    private final JLabel statusDot = new JLabel("●");
    private final JLabel statusText = new JLabel("Server stopped");
    private final JTextArea eventLog = new JTextArea();
    private final DefaultTableModel userModel = new DefaultTableModel(
            new Object[]{"ID", "CLIENT", "ĐỊA CHỈ IP : PORT", "TRẠNG THÁI", "THỜI GIAN"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable userTable = new JTable(userModel);
    private final Map<Long, ClientView> clients = new LinkedHashMap<>();
    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "server-main-loop");
        thread.setDaemon(false);
        return thread;
    });
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private volatile ChatServer server;

    public ServerDashboardFrame() {
        super("Chat P2P - Central Server");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 600));
        setSize(900, 700);
        setLocationRelativeTo(null);
        setContentPane(createContent());
        configureActions();
        setRunningUi(false);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                shutdownAndDispose();
            }
        });
    }

    private JPanel createContent() {
        JPanel root = UiTheme.panel(new BorderLayout(), UiTheme.BACKGROUND);
        root.add(createHeader(), BorderLayout.NORTH);

        JPanel body = UiTheme.panel(new BorderLayout(0, 16), UiTheme.BACKGROUND);
        body.setBorder(BorderFactory.createEmptyBorder(18, 22, 22, 22));
        body.add(createServerConfiguration(), BorderLayout.NORTH);
        body.add(createDataArea(), BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);
        return root;
    }

    private JPanel createHeader() {
        JPanel header = UiTheme.panel(new BorderLayout(), UiTheme.NAVY);
        header.setBorder(BorderFactory.createEmptyBorder(17, 24, 17, 24));

        JPanel identity = UiTheme.panel(new FlowLayout(FlowLayout.LEFT, 11, 0), UiTheme.NAVY);
        identity.add(UiTheme.label("◆", new Font("Segoe UI", Font.BOLD, 22),
                new Color(96, 165, 250)));
        JPanel labels = UiTheme.panel(null, UiTheme.NAVY);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        labels.add(UiTheme.label("CHAT P2P  •  CENTRAL SERVER",
                new Font("Segoe UI", Font.BOLD, 17), UiTheme.WHITE));
        labels.add(UiTheme.label("TCP Control & Connection Monitoring",
                new Font("Segoe UI", Font.PLAIN, 12), new Color(148, 163, 184)));
        identity.add(labels);

        JPanel state = UiTheme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 8), UiTheme.NAVY);
        statusDot.setFont(new Font("Segoe UI", Font.BOLD, 13));
        statusText.setFont(UiTheme.BODY_BOLD);
        statusText.setForeground(UiTheme.WHITE);
        state.add(statusDot);
        state.add(statusText);
        header.add(identity, BorderLayout.WEST);
        header.add(state, BorderLayout.EAST);
        return header;
    }

    private JPanel createServerConfiguration() {
        JPanel section = sectionPanel("SERVER CONFIGURATION");
        JPanel form = UiTheme.panel(new GridBagLayout(), UiTheme.WHITE);
        form.setBorder(BorderFactory.createEmptyBorder(15, 18, 17, 18));

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.insets = new Insets(0, 0, 11, 12);
        constraints.anchor = GridBagConstraints.WEST;
        form.add(fieldLabel("Server IP:"), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        serverIpField.setEditable(false);
        UiTheme.styleTextField(serverIpField);
        serverIpField.setBackground(new Color(248, 250, 252));
        form.add(serverIpField, constraints);

        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        constraints.insets = new Insets(0, 0, 0, 12);
        form.add(fieldLabel("Port:"), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        UiTheme.styleTextField(portField);
        form.add(portField, constraints);

        JPanel controls = UiTheme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0), UiTheme.WHITE);
        UiTheme.stylePrimaryButton(startButton);
        UiTheme.stylePrimaryButton(stopButton);
        Dimension controlSize = new Dimension(132, 44);
        startButton.setPreferredSize(controlSize);
        stopButton.setPreferredSize(controlSize);
        controls.add(startButton);
        controls.add(stopButton);
        constraints.gridx = 2;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        constraints.insets = new Insets(0, 8, 0, 0);
        form.add(controls, constraints);

        section.add(form, BorderLayout.CENTER);
        return section;
    }

    private JSplitPane createDataArea() {
        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, createClientConnectionsPanel(), createServerLogPanel());
        split.setResizeWeight(0.42);
        split.setDividerLocation(220);
        split.setDividerSize(8);
        split.setBorder(null);
        split.setBackground(UiTheme.BACKGROUND);
        return split;
    }

    private JPanel createClientConnectionsPanel() {
        JPanel section = sectionPanel("KẾT NỐI CLIENT");
        JLabel description = UiTheme.label("Mỗi TCP socket được xử lý độc lập",
                new Font("Segoe UI", Font.PLAIN, 11), UiTheme.MUTED);
        ((JPanel) section.getComponent(0)).add(description, BorderLayout.EAST);

        userTable.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        userTable.setForeground(UiTheme.NAVY);
        userTable.setBackground(UiTheme.WHITE);
        userTable.setGridColor(UiTheme.BORDER);
        userTable.setRowHeight(34);
        userTable.setSelectionBackground(new Color(219, 234, 254));
        userTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 11));
        userTable.getTableHeader().setForeground(UiTheme.SLATE);
        userTable.getTableHeader().setBackground(new Color(248, 250, 252));
        userTable.setFillsViewportHeight(true);
        DefaultTableCellRenderer left = new DefaultTableCellRenderer();
        left.setHorizontalAlignment(SwingConstants.LEFT);
        left.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        userTable.getColumnModel().getColumn(0).setCellRenderer(center);
        userTable.getColumnModel().getColumn(1).setCellRenderer(left);
        userTable.getColumnModel().getColumn(2).setCellRenderer(left);
        userTable.getColumnModel().getColumn(3).setCellRenderer(center);
        userTable.getColumnModel().getColumn(4).setCellRenderer(center);
        userTable.getColumnModel().getColumn(0).setPreferredWidth(55);
        userTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        userTable.getColumnModel().getColumn(2).setPreferredWidth(280);
        userTable.getColumnModel().getColumn(3).setPreferredWidth(130);
        userTable.getColumnModel().getColumn(4).setPreferredWidth(110);

        JScrollPane scroll = new JScrollPane(userTable);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UiTheme.BORDER));
        section.add(scroll, BorderLayout.CENTER);
        return section;
    }

    private JPanel createServerLogPanel() {
        JPanel section = sectionPanel("SERVER LOG");
        JLabel logPath = UiTheme.label("data/logs/server.log",
                new Font("Segoe UI", Font.PLAIN, 11), UiTheme.MUTED);
        ((JPanel) section.getComponent(0)).add(logPath, BorderLayout.EAST);

        eventLog.setEditable(false);
        eventLog.setFont(new Font("Consolas", Font.PLAIN, 12));
        eventLog.setForeground(new Color(226, 232, 240));
        eventLog.setBackground(new Color(15, 23, 42));
        eventLog.setMargin(new Insets(12, 14, 12, 14));
        eventLog.setLineWrap(true);
        eventLog.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(eventLog);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UiTheme.BORDER));
        section.add(scroll, BorderLayout.CENTER);
        return section;
    }

    private JPanel sectionPanel(String title) {
        JPanel section = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        section.setBorder(BorderFactory.createLineBorder(UiTheme.BORDER));
        JPanel heading = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        heading.setBorder(BorderFactory.createEmptyBorder(11, 15, 11, 15));
        heading.add(UiTheme.label(title, new Font("Segoe UI", Font.BOLD, 12), UiTheme.NAVY),
                BorderLayout.WEST);
        section.add(heading, BorderLayout.NORTH);
        return section;
    }

    private JLabel fieldLabel(String text) {
        return UiTheme.label(text, new Font("Segoe UI", Font.BOLD, 13), UiTheme.SLATE);
    }

    private void configureActions() {
        startButton.addActionListener(event -> startServer());
        stopButton.addActionListener(event -> stopServer());
        getRootPane().setDefaultButton(startButton);
    }

    private void startServer() {
        if (server != null && server.isRunning()) {
            return;
        }

        int port;
        try {
            port = parsePort(portField.getText().trim());
        } catch (IllegalArgumentException invalidPort) {
            appendLog("ERROR", invalidPort.getMessage());
            return;
        }

        clients.clear();
        refreshClientConnections();
        setStartingUi();
        appendLog("INFO", "Starting server on " + serverIpField.getText() + ":" + port);

        try {
            ChatServer newServer = new ChatServer(port, this::receiveEvent);
            server = newServer;
            serverExecutor.execute(() -> {
                try {
                    newServer.start();
                } catch (IOException failure) {
                    SwingUtilities.invokeLater(() -> {
                        appendLog("ERROR", "Cannot start server: " + failure.getMessage());
                        setRunningUi(false);
                    });
                } finally {
                    if (server == newServer) {
                        server = null;
                    }
                }
            });
        } catch (IOException failure) {
            appendLog("ERROR", "Cannot initialize logger: " + failure.getMessage());
            setRunningUi(false);
        }
    }

    private void stopServer() {
        ChatServer current = server;
        if (current == null) {
            return;
        }
        stopButton.setEnabled(false);
        appendLog("INFO", "Stopping server...");
        Thread stopper = new Thread(current::close, "server-stop-request");
        stopper.setDaemon(true);
        stopper.start();
    }

    private void receiveEvent(ServerEvent event) {
        SwingUtilities.invokeLater(() -> applyEvent(event));
    }

    private void applyEvent(ServerEvent event) {
        appendLog(event.type().name(), event.message()
                + clientSuffix(event.clientName(), event.remoteAddress()));

        switch (event.type()) {
            case SERVER_STARTED -> setRunningUi(true);
            case SERVER_STOPPED -> {
                clients.values().forEach(client -> client.status = "OFFLINE");
                refreshClientConnections();
                setRunningUi(false);
            }
            case CONNECTION_ACCEPTED -> {
                clients.put(event.connectionId(),
                        new ClientView(
                                "Đang bắt tay",
                                displayAddress(event.remoteAddress()),
                                "CONNECTED",
                                TIME_FORMAT.format(event.timestamp())));
                refreshClientConnections();
            }
            case HANDSHAKE_COMPLETED -> {
                ClientView client = clients.get(event.connectionId());
                if (client == null) {
                    clients.put(event.connectionId(),
                            new ClientView(
                                    event.clientName(),
                                    displayAddress(event.remoteAddress()),
                                    "ONLINE",
                                    TIME_FORMAT.format(event.timestamp())));
                } else {
                    client.username = event.clientName();
                    client.status = "ONLINE";
                }
                refreshClientConnections();
            }
            case CLIENT_DISCONNECTED -> {
                ClientView client = clients.get(event.connectionId());
                if (client == null) {
                    clients.put(event.connectionId(), new ClientView(
                            event.clientName().isBlank() ? "Unknown" : event.clientName(),
                            displayAddress(event.remoteAddress()),
                            "OFFLINE",
                            TIME_FORMAT.format(event.timestamp())));
                } else {
                    if (!event.clientName().isBlank()) {
                        client.username = event.clientName();
                    }
                    client.status = "OFFLINE";
                }
                refreshClientConnections();
            }
            case ERROR -> { }
        }
    }

    private void refreshClientConnections() {
        userModel.setRowCount(0);
        for (Map.Entry<Long, ClientView> entry : clients.entrySet()) {
            ClientView client = entry.getValue();
            userModel.addRow(new Object[]{
                    entry.getKey(),
                    client.username,
                    client.remoteAddress,
                    client.status,
                    client.connectedAt});
        }
    }

    private void appendLog(String level, String message) {
        eventLog.append("[" + TIME_FORMAT.format(java.time.Instant.now()) + "] "
                + level + "  " + message + System.lineSeparator());
        if (eventLog.getDocument().getLength() > 80_000) {
            eventLog.replaceRange("", 0, 20_000);
        }
        eventLog.setCaretPosition(eventLog.getDocument().getLength());
    }

    private void setStartingUi() {
        portField.setEnabled(false);
        startButton.setEnabled(false);
        startButton.setText("STARTING...");
        stopButton.setEnabled(false);
        stopButton.setBackground(new Color(203, 213, 225));
        stopButton.setForeground(UiTheme.SLATE);
        statusDot.setForeground(new Color(245, 158, 11));
        statusText.setText("Server starting");
    }

    private void setRunningUi(boolean running) {
        portField.setEnabled(!running);
        startButton.setEnabled(!running);
        startButton.setText("START");
        stopButton.setEnabled(running);
        startButton.setBackground(running ? new Color(203, 213, 225) : UiTheme.PRIMARY);
        startButton.setForeground(running ? UiTheme.SLATE : UiTheme.WHITE);
        stopButton.setBackground(running ? UiTheme.DANGER : new Color(203, 213, 225));
        stopButton.setForeground(running ? UiTheme.WHITE : UiTheme.SLATE);
        statusDot.setForeground(running ? UiTheme.SUCCESS : UiTheme.MUTED);
        statusText.setText(running ? "Server running" : "Server stopped");
    }

    private void shutdownAndDispose() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        setVisible(false);
        Thread shutdown = new Thread(() -> {
            ChatServer current = server;
            if (current != null) {
                current.close();
            }
            serverExecutor.shutdownNow();
            SwingUtilities.invokeLater(this::dispose);
        }, "server-window-shutdown");
        shutdown.setDaemon(true);
        shutdown.start();
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535.");
            }
            return port;
        } catch (NumberFormatException invalidNumber) {
            throw new IllegalArgumentException("Port must be an integer.", invalidNumber);
        }
    }

    private static String detectServerIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (SocketException | java.net.UnknownHostException failure) {
            return "127.0.0.1";
        }
    }

    private static String clientSuffix(String clientName, String remoteAddress) {
        StringBuilder suffix = new StringBuilder();
        if (!clientName.isBlank()) {
            suffix.append(" | user=").append(clientName);
        }
        if (!remoteAddress.isBlank()) {
            suffix.append(" | remote=").append(remoteAddress);
        }
        return suffix.toString();
    }

    private static String displayAddress(String remoteAddress) {
        return remoteAddress.startsWith("/") ? remoteAddress.substring(1) : remoteAddress;
    }

    private static final class ClientView {
        private String username;
        private final String remoteAddress;
        private String status;
        private final String connectedAt;

        private ClientView(
                String username, String remoteAddress, String status, String connectedAt) {
            this.username = username;
            this.remoteAddress = remoteAddress;
            this.status = status;
            this.connectedAt = connectedAt;
        }
    }
}
