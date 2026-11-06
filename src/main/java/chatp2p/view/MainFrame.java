package chatp2p.view;

import chatp2p.client.AudioRecorder;
import chatp2p.client.ChatClient;
import chatp2p.client.ChatHistoryStore;
import chatp2p.client.ClientEventListener;
import chatp2p.client.ConnectionState;
import chatp2p.client.TransferHandle;
import chatp2p.model.ChatMessage;
import chatp2p.model.FileInfo;
import chatp2p.model.GroupInfo;
import chatp2p.model.GroupMessage;
import chatp2p.model.PeerInfo;
import chatp2p.model.TransferDirection;
import chatp2p.model.TransferProgress;
import chatp2p.model.TransferState;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.KeyStroke;
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
import javax.swing.border.AbstractBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Modern Zalo/Messenger-inspired client interface with live P2P chat and file transfer. */
public final class MainFrame extends JFrame {
    private static final DateTimeFormatter MESSAGE_TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final ChatClient client;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Data models & raw cache for live search filtering
    private final List<PeerInfo> rawUsers = new ArrayList<>();
    private final List<GroupInfo> rawGroups = new ArrayList<>();
    private final DefaultListModel<PeerInfo> userModel = new DefaultListModel<>();
    private final JList<PeerInfo> userList = new JList<>(userModel);
    private final DefaultListModel<GroupInfo> groupModel = new DefaultListModel<>();
    private final JList<GroupInfo> groupList = new JList<>(groupModel);

    // Navigation & Search controls
    private final PlaceholderTextField searchField =
            new PlaceholderTextField("Tìm kiếm...");
    private final JButton tabUsersButton = new JButton("Bạn bè");
    private final JButton tabGroupsButton = new JButton("Nhóm");
    private final CardLayout listCardLayout = new CardLayout();
    private final JPanel listContainer = new JPanel(listCardLayout);
    private final JTextField groupNameField = new JTextField();
    private final JButton joinGroupButton = new JButton("Tham gia");
    private final JButton createGroupButton = new JButton("+ Tạo nhóm");

    // Chat Header components
    private final JPanel headerAvatarPanel = new JPanel() {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                if (selectedGroup != null) {
                    UiTheme.drawAvatar(g2, selectedGroup.name(), 0, 0, 42, true, false);
                } else if (selectedPeer != null) {
                    UiTheme.drawAvatar(g2, selectedPeer.username(), 0, 0, 42, false, true);
                }
            } finally {
                g2.dispose();
            }
        }
    };
    private final JLabel chatTitle = new JLabel("Chọn một cuộc trò chuyện");
    private final JLabel chatSubtitle = new JLabel("Kết nối trực tiếp qua P2P TCP");
    private final JButton leaveGroupButton = new JButton("Rời nhóm");

    // Chat Area components
    private final JPanel messagesPanel = new JPanel();
    private final JScrollPane messagesScroll = new JScrollPane(messagesPanel);
    private final EmojiTextPane messageField = new EmojiTextPane("Nhập tin nhắn... (Enter để gửi)");
    private final ComposerIconButton emojiButton =
            new ComposerIconButton(ComposerIcon.SMILE, "Sticker & Biểu cảm (Zalo)");
    private final ComposerIconButton gifButton =
            new ComposerIconButton(ComposerIcon.GIF, "Ảnh động GIF");
    private final ComposerIconButton fileButton =
            new ComposerIconButton(ComposerIcon.ATTACH, "Gửi file P2P");
    private final ComposerIconButton audioButton =
            new ComposerIconButton(ComposerIcon.MICROPHONE, "Thu và gửi âm thanh");
    private final ComposerIconButton videoButton =
            new ComposerIconButton(ComposerIcon.VIDEO, "Chụp ảnh hoặc quay video trực tiếp");
    private final ComposerIconButton sendButton =
            new ComposerIconButton(ComposerIcon.SEND, "Gửi tin nhắn");

    // History tracking
    private final Map<String, List<ConversationEntry>> histories = new HashMap<>();
    private final Map<String, List<GroupMessage>> groupHistories = new HashMap<>();
    private final Set<String> historyPeers = new HashSet<>();
    private final Set<UUID> displayedMessageIds = new HashSet<>();
    private final Set<UUID> displayedGroupIds = new HashSet<>();
    private final Map<UUID, FileEntry> fileEntries = new HashMap<>();
    private final Map<UUID, TransferHandle> outgoingTransfers = new HashMap<>();
    private final Map<UUID, Path> outgoingSourcePaths = new HashMap<>();
    private final Map<UUID, Clip> playingAudio = new HashMap<>();
    private final Map<Path, ImageIcon> imageThumbnails = new HashMap<>();
    private final Set<Path> loadingThumbnails = new HashSet<>();
    private final Set<Path> failedThumbnails = new HashSet<>();
    private final AudioRecorder audioRecorder = new AudioRecorder();

    private final Timer connectionTimer;
    private volatile PeerInfo selectedPeer;
    private volatile GroupInfo selectedGroup;
    private volatile PeerInfo audioRecordingPeer;

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
        public void onGroupsChanged(List<GroupInfo> groups) {
            SwingUtilities.invokeLater(() -> updateGroups(groups));
        }

        @Override
        public void onGroupChatReceived(GroupMessage message) {
            SwingUtilities.invokeLater(() -> addGroupMessage(message));
        }

        @Override
        public boolean onFileOffered(FileInfo file) {
            TransferProgress offered = new TransferProgress(
                    file,
                    TransferDirection.RECEIVING,
                    TransferState.WAITING,
                    0,
                    0,
                    -1,
                    "Chuẩn bị nhận file...");
            SwingUtilities.invokeLater(() -> updateFileEntry(offered));
            return true;
        }

        @Override
        public void onTransferProgress(TransferProgress progress) {
            SwingUtilities.invokeLater(() -> updateFileEntry(progress));
        }

        @Override
        public void onTransferCompleted(FileInfo file, Path savedPath) {
            SwingUtilities.invokeLater(() -> markFileCompleted(file, savedPath));
        }

        @Override
        public void onTransferFailed(FileInfo file, String reason) {
            SwingUtilities.invokeLater(() -> markFileFailed(file, reason));
        }

        @Override
        public void onConnectionError(String message) {
            SwingUtilities.invokeLater(() -> showError(message));
        }
    };

    public MainFrame(ChatClient client, ChatHistoryStore.Snapshot savedHistory) {
        super("Chat P2P - " + client.clientName());
        this.client = client;
        restoreHistory(savedHistory);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));
        setSize(1140, 750);
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
        JPanel root = UiTheme.panel(new BorderLayout(), UiTheme.CHAT_BG);
        // 1. Navigation Rail (Leftmost bar ~64px wide, Zalo style)
        root.add(createRailPanel(), BorderLayout.WEST);

        // 2. Middle & Right: SplitPane between Conversation List and Chat Workspace
        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, createSidebarPanel(), createChatWorkspace());
        split.setDividerLocation(310);
        split.setDividerSize(1);
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    /** Modern Left Rail (dock) containing current user avatar, tab icons, and logout button. */
    private JPanel createRailPanel() {
        JPanel rail = UiTheme.panel(new BorderLayout(), UiTheme.RAIL_BG);
        rail.setPreferredSize(new Dimension(64, 0));
        rail.setBorder(BorderFactory.createEmptyBorder(16, 8, 16, 8));

        // Top: Current user avatar
        JPanel topPanel = UiTheme.panel(new FlowLayout(FlowLayout.CENTER, 0, 0), UiTheme.RAIL_BG);
        JPanel userAvatar = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    UiTheme.drawAvatar(g2, client.clientName(), 0, 0, 44, false, false);
                } finally {
                    g2.dispose();
                }
            }
        };
        userAvatar.setPreferredSize(new Dimension(44, 44));
        userAvatar.setOpaque(false);
        userAvatar.setToolTipText("Tài khoản: " + client.clientName() + " (Peer Port: " + client.peerPort() + ")");
        topPanel.add(userAvatar);

        // Center: Navigation Icons (Chat, Groups)
        JPanel navPanel = UiTheme.panel(null, UiTheme.RAIL_BG);
        navPanel.setLayout(new BoxLayout(navPanel, BoxLayout.Y_AXIS));
        UiTheme.addVerticalGap(navPanel, 20);

        RailIconButton chatIcon = new RailIconButton(
                RailIcon.CHAT, Color.WHITE, "Tin nhắn", true);
        RailIconButton groupIcon = new RailIconButton(
                RailIcon.GROUP, new Color(165, 243, 252), "Nhóm chat", false);

        chatIcon.addActionListener(e -> {
            chatIcon.setSelected(true);
            groupIcon.setSelected(false);
            switchTab("USERS");
        });
        groupIcon.addActionListener(e -> {
            groupIcon.setSelected(true);
            chatIcon.setSelected(false);
            switchTab("GROUPS");
        });

        navPanel.add(chatIcon);
        UiTheme.addVerticalGap(navPanel, 12);
        navPanel.add(groupIcon);

        // Bottom: Status & Logout
        JPanel bottomPanel = UiTheme.panel(null, UiTheme.RAIL_BG);
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));

        RailIconButton logoutBtn = new RailIconButton(
                RailIcon.LOGOUT, new Color(254, 202, 202), "Đăng xuất", false);
        logoutBtn.addActionListener(e -> disconnectAndReturn());
        bottomPanel.add(logoutBtn);

        rail.add(topPanel, BorderLayout.NORTH);
        rail.add(navPanel, BorderLayout.CENTER);
        rail.add(bottomPanel, BorderLayout.SOUTH);
        return rail;
    }

    /** Sidebar with real-time search bar, pill switchers, and user/group lists. */
    private JPanel createSidebarPanel() {
        JPanel sidebar = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UiTheme.BORDER));

        // Top Search & Tabs
        JPanel topArea = UiTheme.panel(null, UiTheme.WHITE);
        topArea.setLayout(new BoxLayout(topArea, BoxLayout.Y_AXIS));
        topArea.setBorder(BorderFactory.createEmptyBorder(14, 14, 10, 14));

        JLabel titleLabel = UiTheme.label("Đoạn chat", new Font("Segoe UI", Font.BOLD, 20), UiTheme.NAVY);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topArea.add(titleLabel);
        UiTheme.addVerticalGap(topArea, 12);

        // Search Box (Pill-shaped)
        searchField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        searchField.setForeground(UiTheme.NAVY);
        searchField.setBackground(new Color(241, 243, 246));
        searchField.setCaretColor(UiTheme.PRIMARY);
        searchField.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(new Color(226, 230, 238), 16),
                BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        searchField.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        searchField.setPreferredSize(new Dimension(280, 36));
        searchField.setToolTipText("Tìm kiếm bạn bè hoặc nhóm...");
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                filterLists();
            }
        });
        topArea.add(searchField);
        UiTheme.addVerticalGap(topArea, 12);

        // Pill Switcher Tabs: [Bạn bè] [Nhóm]
        JPanel pillPanel = UiTheme.panel(new GridLayout(1, 2, 6, 0), UiTheme.WHITE);
        pillPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        pillPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        pillPanel.setPreferredSize(new Dimension(280, 32));

        stylePillButton(tabUsersButton, true);
        stylePillButton(tabGroupsButton, false);

        tabUsersButton.addActionListener(e -> switchTab("USERS"));
        tabGroupsButton.addActionListener(e -> switchTab("GROUPS"));

        pillPanel.add(tabUsersButton);
        pillPanel.add(tabGroupsButton);
        topArea.add(pillPanel);

        // Lists Container
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setFixedCellHeight(68);
        userList.setBackground(UiTheme.WHITE);
        userList.setCellRenderer(new UserRenderer());
        JScrollPane userScroll = new JScrollPane(userList);
        UiTheme.applyModernScrollBar(userScroll);

        JPanel groupsPanel = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setFixedCellHeight(68);
        groupList.setBackground(UiTheme.WHITE);
        groupList.setCellRenderer(new GroupRenderer());
        JScrollPane groupScroll = new JScrollPane(groupList);
        UiTheme.applyModernScrollBar(groupScroll);

        // Quick join group bar at the bottom of groups list
        JPanel joinPanel = UiTheme.panel(new BorderLayout(6, 0), new Color(248, 250, 252));
        joinPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        groupNameField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        groupNameField.setBackground(UiTheme.WHITE);
        groupNameField.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(UiTheme.BORDER, 14),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        groupNameField.setToolTipText("Tên nhóm đã có");
        joinGroupButton.setFont(new Font("Segoe UI", Font.BOLD, 12));
        joinGroupButton.setForeground(UiTheme.WHITE);
        joinGroupButton.setBackground(UiTheme.PRIMARY);
        joinGroupButton.setFocusPainted(false);
        joinGroupButton.setBorderPainted(false);
        joinGroupButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        joinGroupButton.setPreferredSize(new Dimension(84, 34));
        joinPanel.add(groupNameField, BorderLayout.CENTER);
        joinPanel.add(joinGroupButton, BorderLayout.EAST);

        createGroupButton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        createGroupButton.setForeground(UiTheme.WHITE);
        createGroupButton.setBackground(UiTheme.PRIMARY);
        createGroupButton.setFocusPainted(false);
        createGroupButton.setBorderPainted(false);
        createGroupButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        createGroupButton.setPreferredSize(new Dimension(280, 38));
        JPanel groupActions = UiTheme.panel(new BorderLayout(0, 4), UiTheme.WHITE);
        groupActions.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        groupActions.add(createGroupButton, BorderLayout.NORTH);
        groupActions.add(joinPanel, BorderLayout.SOUTH);

        groupsPanel.add(groupScroll, BorderLayout.CENTER);
        groupsPanel.add(groupActions, BorderLayout.SOUTH);

        listContainer.add(userScroll, "USERS");
        listContainer.add(groupsPanel, "GROUPS");

        sidebar.add(topArea, BorderLayout.NORTH);
        sidebar.add(listContainer, BorderLayout.CENTER);
        return sidebar;
    }

    private void switchTab(String tabName) {
        listCardLayout.show(listContainer, tabName);
        boolean isUsers = "USERS".equals(tabName);
        stylePillButton(tabUsersButton, isUsers);
        stylePillButton(tabGroupsButton, !isUsers);
    }

    private void stylePillButton(JButton button, boolean active) {
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setOpaque(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (active) {
            button.setBackground(new Color(231, 243, 255));
            button.setForeground(UiTheme.PRIMARY);
        } else {
            button.setBackground(new Color(241, 243, 246));
            button.setForeground(UiTheme.SLATE);
        }
    }

    /** Filter both userModel and groupModel based on searchField text. */
    private void filterLists() {
        String query = searchField.getText().trim().toLowerCase();
        String currentSelectedUser = selectedPeer == null ? null : selectedPeer.username();
        userModel.clear();
        for (PeerInfo peer : rawUsers) {
            if (query.isEmpty() || peer.username().toLowerCase().contains(query)) {
                userModel.addElement(peer);
                if (peer.username().equals(currentSelectedUser)) {
                    userList.setSelectedValue(peer, false);
                }
            }
        }
        for (String name : historyPeers.stream().sorted().toList()) {
            if (!isPeerOnline(name) && (query.isEmpty() || name.toLowerCase().contains(query))) {
                PeerInfo offline = new PeerInfo(name, "offline", 1);
                userModel.addElement(offline);
                if (name.equals(currentSelectedUser)) {
                    userList.setSelectedValue(offline, false);
                }
            }
        }

        String currentSelectedGroupName = selectedGroup == null ? null : selectedGroup.name();
        groupModel.clear();
        for (GroupInfo group : rawGroups) {
            if (query.isEmpty() || group.name().toLowerCase().contains(query)) {
                groupModel.addElement(group);
                if (group.name().equals(currentSelectedGroupName)) {
                    groupList.setSelectedValue(group, false);
                }
            }
        }
        for (String name : groupHistories.keySet().stream().sorted().toList()) {
            if (rawGroups.stream().noneMatch(group -> group.name().equals(name))
                    && (query.isEmpty() || name.toLowerCase().contains(query))) {
                GroupInfo archived = new GroupInfo(name, 0, false);
                groupModel.addElement(archived);
                if (name.equals(currentSelectedGroupName)) {
                    groupList.setSelectedValue(archived, false);
                }
            }
        }
    }

    /** Right workspace: Chat header, message bubbles scroll, and pill composer. */
    private JPanel createChatWorkspace() {
        JPanel workspace = UiTheme.panel(new BorderLayout(), UiTheme.CHAT_BG);
        workspace.add(createChatHeader(), BorderLayout.NORTH);

        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(UiTheme.CHAT_BG);
        messagesPanel.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        UiTheme.applyModernScrollBar(messagesScroll);
        workspace.add(messagesScroll, BorderLayout.CENTER);

        workspace.add(createComposer(), BorderLayout.SOUTH);
        renderHistory();
        return workspace;
    }

    private JPanel createChatHeader() {
        JPanel header = UiTheme.panel(new BorderLayout(), UiTheme.WHITE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(12, 20, 12, 20)));

        JPanel leftBox = UiTheme.panel(new FlowLayout(FlowLayout.LEFT, 12, 0), UiTheme.WHITE);
        headerAvatarPanel.setPreferredSize(new Dimension(42, 42));
        headerAvatarPanel.setOpaque(false);

        JPanel labelBox = UiTheme.panel(null, UiTheme.WHITE);
        labelBox.setLayout(new BoxLayout(labelBox, BoxLayout.Y_AXIS));
        chatTitle.setFont(new Font("Segoe UI", Font.BOLD, 16));
        chatTitle.setForeground(UiTheme.NAVY);
        chatSubtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        chatSubtitle.setForeground(UiTheme.MUTED);
        labelBox.add(chatTitle);
        labelBox.add(Box.createVerticalStrut(2));
        labelBox.add(chatSubtitle);

        leftBox.add(headerAvatarPanel);
        leftBox.add(labelBox);

        JPanel rightBox = UiTheme.panel(new FlowLayout(FlowLayout.RIGHT, 10, 0), UiTheme.WHITE);
        leaveGroupButton.setFont(new Font("Segoe UI", Font.BOLD, 12));
        leaveGroupButton.setForeground(UiTheme.DANGER);
        leaveGroupButton.setBackground(new Color(254, 242, 242));
        leaveGroupButton.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(new Color(254, 202, 202), 14),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)));
        leaveGroupButton.setFocusPainted(false);
        leaveGroupButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        leaveGroupButton.setVisible(false);
        rightBox.add(leaveGroupButton);

        header.add(leftBox, BorderLayout.WEST);
        header.add(rightBox, BorderLayout.EAST);
        return header;
    }

    private JPanel createComposer() {
        JPanel composer = UiTheme.panel(new BorderLayout(10, 0), UiTheme.WHITE);
        composer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)));

        // Left action buttons
        JPanel leftActions = UiTheme.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), UiTheme.WHITE);
        leftActions.add(fileButton);
        leftActions.add(audioButton);
        leftActions.add(videoButton);
        leftActions.add(emojiButton);
        leftActions.add(gifButton);

        // Center input field
        messageField.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        messageField.setForeground(UiTheme.NAVY);
        messageField.setCaretColor(UiTheme.PRIMARY);
        messageField.setBackground(new Color(241, 243, 246));
        messageField.setOpaque(true);
        messageField.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(new Color(226, 230, 238), 20),
                BorderFactory.createEmptyBorder(9, 16, 9, 16)));

        // Right action button
        JPanel rightActions = UiTheme.panel(new FlowLayout(FlowLayout.RIGHT, 0, 0), UiTheme.WHITE);
        rightActions.add(sendButton);

        composer.add(leftActions, BorderLayout.WEST);
        composer.add(messageField, BorderLayout.CENTER);
        composer.add(rightActions, BorderLayout.EAST);
        setComposerEnabled(false);
        return composer;
    }

    private void configureActions() {
        userList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                PeerInfo selected = userList.getSelectedValue();
                if (selected != null) {
                    selectPeer(selected);
                }
            }
        });
        groupList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                GroupInfo selected = groupList.getSelectedValue();
                if (selected != null) {
                    selectGroup(selected);
                }
            }
        });
        joinGroupButton.addActionListener(event -> joinGroup());
        groupNameField.addActionListener(event -> joinGroup());
        createGroupButton.addActionListener(event -> showCreateGroupDialog());
        leaveGroupButton.addActionListener(event -> leaveSelectedGroup());
        emojiButton.addActionListener(event -> showMediaPopover(emojiButton, ZaloMediaPopover.TabType.EMOJI));
        gifButton.addActionListener(event -> showMediaPopover(gifButton, ZaloMediaPopover.TabType.GIF));
        sendButton.addActionListener(event -> sendMessage());
        messageField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "sendMessage");
        messageField.getActionMap().put("sendMessage", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                sendMessage();
            }
        });
        fileButton.addActionListener(event -> chooseAndSendFile());
        audioButton.addActionListener(event -> toggleAudioRecording());
        videoButton.addActionListener(event -> openCamera());
    }

    private void updateUsers(List<PeerInfo> users) {
        rawUsers.clear();
        rawUsers.addAll(users);
        filterLists();

        String selectedName = selectedPeer == null ? null : selectedPeer.username();
        PeerInfo restored = null;
        for (int index = 0; index < userModel.size(); index++) {
            PeerInfo peer = userModel.get(index);
            if (peer.username().equals(selectedName)) {
                restored = peer;
                break;
            }
        }
        if (restored != null) {
            userList.setSelectedValue(restored, true);
            selectedPeer = restored;
            selectPeer(restored);
        } else if (selectedName != null) {
            selectPeer(null);
        }
        long offlineCount = historyPeers.stream().filter(name -> !isPeerOnline(name)).count();
        tabUsersButton.setText("Bạn bè (" + (users.size() + offlineCount) + ")");
    }

    private void selectPeer(PeerInfo peer) {
        selectedPeer = peer;
        selectedGroup = null;
        groupList.clearSelection();
        leaveGroupButton.setVisible(false);
        headerAvatarPanel.repaint();
        if (peer == null) {
            chatTitle.setText("Chọn một cuộc trò chuyện");
            chatSubtitle.setText("Kết nối trực tiếp qua P2P TCP");
            setComposerEnabled(false);
        } else {
            chatTitle.setText(peer.username());
            if (isPeerOnline(peer.username())) {
                chatSubtitle.setText("● Đang hoạt động • P2P: " + peer.host() + ":" + peer.port());
                chatSubtitle.setForeground(UiTheme.ONLINE_GREEN);
                setComposerEnabled(client.state() == ConnectionState.CONNECTED);
            } else {
                chatSubtitle.setText("Ngoại tuyến • Có thể xem lịch sử trò chuyện");
                chatSubtitle.setForeground(UiTheme.MUTED);
                setComposerEnabled(false);
            }
        }
        renderHistory();
    }

    private void updateGroups(List<GroupInfo> groups) {
        rawGroups.clear();
        rawGroups.addAll(groups);
        filterLists();

        String selectedName = selectedGroup == null ? null : selectedGroup.name();
        GroupInfo restored = null;
        for (int index = 0; index < groupModel.size(); index++) {
            GroupInfo group = groupModel.get(index);
            if (group.name().equals(selectedName)) {
                restored = group;
                break;
            }
        }
        if (restored != null) {
            groupList.setSelectedValue(restored, true);
            selectGroup(restored);
        } else if (selectedName != null) {
            selectedGroup = null;
            leaveGroupButton.setVisible(false);
            renderHistory();
        }
        long archivedCount = groupHistories.keySet().stream()
                .filter(name -> groups.stream().noneMatch(group -> group.name().equals(name))).count();
        tabGroupsButton.setText("Nhóm (" + (groups.size() + archivedCount) + ")");
    }

    private void selectGroup(GroupInfo group) {
        if (group == null) {
            return;
        }
        if (!group.joined()) {
            selectedGroup = group;
            selectedPeer = null;
            userList.clearSelection();
            groupNameField.setText(group.name());
            chatTitle.setText("Nhóm #" + group.name());
            chatSubtitle.setForeground(UiTheme.SLATE);
            chatSubtitle.setText("Lịch sử nhóm • Nhấn Tham gia để gửi tin");
            leaveGroupButton.setVisible(false);
            headerAvatarPanel.repaint();
            setComposerEnabled(false);
            renderHistory();
            return;
        }
        selectedGroup = group;
        selectedPeer = null;
        userList.clearSelection();
        chatTitle.setText("Nhóm #" + group.name());
        chatSubtitle.setForeground(UiTheme.MUTED);
        chatSubtitle.setText(group.memberCount() + " thành viên • "
                + (client.multicastAvailable()
                        ? "UDP Multicast + Server fallback"
                        : "Server fallback"));
        leaveGroupButton.setVisible(true);
        headerAvatarPanel.repaint();
        setComposerEnabled(client.state() == ConnectionState.CONNECTED);
        fileButton.setEnabled(false);
        audioButton.setEnabled(audioRecorder.isRecording());
        videoButton.setEnabled(false);
        renderHistory();
    }

    private void joinGroup() {
        String groupName = groupNameField.getText().trim();
        if (groupName.isEmpty()) {
            chatSubtitle.setForeground(UiTheme.DANGER);
            chatSubtitle.setText("Hãy nhập tên nhóm cần tham gia");
            return;
        }
        joinGroupButton.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                client.joinGroup(groupName);
                return null;
            }

            @Override
            protected void done() {
                joinGroupButton.setEnabled(true);
                try {
                    get();
                    groupNameField.setText("");
                    client.groups().stream()
                            .filter(group -> group.name().equalsIgnoreCase(groupName) && group.joined())
                            .findFirst().ifPresent(MainFrame.this::selectGroup);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    chatSubtitle.setForeground(UiTheme.DANGER);
                    chatSubtitle.setText(friendlyMessage(failure.getCause()));
                }
            }
        }.execute();
    }

    private void showCreateGroupDialog() {
        if (!client.createGroupSupported()) {
            JOptionPane.showMessageDialog(this,
                    "Server đang chạy bản cũ. Hãy dừng và chạy lại ServerMain, "
                            + "sau đó đăng nhập lại trước khi tạo nhóm.",
                    "Cần khởi động lại server", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JTextField nameField = new JTextField(24);
        nameField.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        DefaultListModel<String> candidates = new DefaultListModel<>();
        rawUsers.stream().map(PeerInfo::username)
                .filter(username -> !username.equals(client.clientName()))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(candidates::addElement);
        JList<String> inviteList = new JList<>(candidates);
        inviteList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        inviteList.setVisibleRowCount(5);
        JScrollPane inviteScroll = new JScrollPane(inviteList);
        inviteScroll.setPreferredSize(new Dimension(320, 120));
        JTextField otherUsers = new JTextField(24);
        otherUsers.setToolTipText("Tên đăng nhập, ngăn cách bằng dấu phẩy");

        JPanel form = UiTheme.panel(null, UiTheme.WHITE);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(UiTheme.label("Tên nhóm", UiTheme.BODY_BOLD, UiTheme.NAVY));
        form.add(Box.createVerticalStrut(5));
        form.add(nameField);
        form.add(Box.createVerticalStrut(14));
        form.add(UiTheme.label("Mời người đang online (chọn nhiều người)",
                UiTheme.BODY_BOLD, UiTheme.NAVY));
        form.add(Box.createVerticalStrut(5));
        form.add(inviteScroll);
        form.add(Box.createVerticalStrut(12));
        form.add(UiTheme.label("Tài khoản khác, kể cả offline (cách nhau bằng dấu phẩy)",
                new Font("Segoe UI", Font.PLAIN, 12), UiTheme.SLATE));
        form.add(Box.createVerticalStrut(5));
        form.add(otherUsers);

        int choice = JOptionPane.showConfirmDialog(this, form, "Tạo nhóm mới",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        String name = nameField.getText().trim();
        List<String> invitees = new ArrayList<>(inviteList.getSelectedValuesList());
        for (String username : otherUsers.getText().split(",")) {
            if (!username.isBlank()) {
                invitees.add(username.trim());
            }
        }
        if (name.isBlank()) {
            JOptionPane.showMessageDialog(this, "Hãy nhập tên nhóm.",
                    "Tạo nhóm", JOptionPane.WARNING_MESSAGE);
            return;
        }
        createGroupButton.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                client.createGroup(name, invitees);
                return null;
            }

            @Override
            protected void done() {
                createGroupButton.setEnabled(true);
                try {
                    get();
                    searchField.setText("");
                    updateGroups(client.groups());
                    client.groups().stream()
                            .filter(group -> group.name().equals(name) && group.joined())
                            .findFirst().ifPresent(group -> {
                                groupList.setSelectedValue(group, true);
                                selectGroup(group);
                            });
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    JOptionPane.showMessageDialog(MainFrame.this,
                            friendlyMessage(failure.getCause()), "Không thể tạo nhóm",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void leaveSelectedGroup() {
        GroupInfo group = selectedGroup;
        if (group == null) {
            return;
        }
        leaveGroupButton.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                client.leaveGroup(group.name());
                return null;
            }

            @Override
            protected void done() {
                leaveGroupButton.setEnabled(true);
                try {
                    get();
                    selectedGroup = null;
                    groupList.clearSelection();
                    chatTitle.setText("Chọn một cuộc trò chuyện");
                    chatSubtitle.setText("Bạn đã rời nhóm #" + group.name());
                    leaveGroupButton.setVisible(false);
                    headerAvatarPanel.repaint();
                    setComposerEnabled(false);
                    renderHistory();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    chatSubtitle.setForeground(UiTheme.DANGER);
                    chatSubtitle.setText(friendlyMessage(failure.getCause()));
                }
            }
        }.execute();
    }

    private void sendMessage() {
        PeerInfo peer = selectedPeer;
        GroupInfo group = selectedGroup;
        String text = messageField.messageText().trim();
        if ((peer == null && group == null) || text.isEmpty()) {
            return;
        }
        sendButton.setEnabled(false);
        new SwingWorker<Object, Void>() {
            @Override
            protected Object doInBackground() throws Exception {
                return group != null
                        ? client.sendGroupChat(group.name(), text)
                        : client.sendChat(peer.username(), text);
            }

            @Override
            protected void done() {
                sendButton.setEnabled(selectedPeer != null || selectedGroup != null);
                try {
                    Object sent = get();
                    messageField.setText("");
                    if (sent instanceof ChatMessage chatMessage) {
                        addMessage(chatMessage);
                    } else if (sent instanceof GroupMessage groupMessage) {
                        addGroupMessage(groupMessage);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    showError(friendlyMessage(failure.getCause()));
                }
            }
        }.execute();
    }

    private void showMediaPopover(Component anchor, ZaloMediaPopover.TabType defaultTab) {
        PeerInfo peer = selectedPeer;
        GroupInfo group = selectedGroup;
        if (peer == null && group == null) {
            return;
        }
        ZaloMediaPopover popover = new ZaloMediaPopover(
                this::sendMediaMessage,
                emojiId -> {
                    messageField.insertEmoji(emojiId);
                    messageField.requestFocusInWindow();
                });
        popover.selectTab(defaultTab);
        popover.show(anchor, 0, -popover.getPreferredSize().height - 10);
    }

    private void sendMediaMessage(String payload) {
        PeerInfo peer = selectedPeer;
        GroupInfo group = selectedGroup;
        if ((peer == null && group == null) || payload == null || payload.isBlank()) {
            return;
        }
        new SwingWorker<Object, Void>() {
            @Override
            protected Object doInBackground() throws Exception {
                return group != null
                        ? client.sendGroupChat(group.name(), payload)
                        : client.sendChat(peer.username(), payload);
            }

            @Override
            protected void done() {
                try {
                    Object sent = get();
                    if (sent instanceof ChatMessage chatMessage) {
                        addMessage(chatMessage);
                    } else if (sent instanceof GroupMessage groupMessage) {
                        addGroupMessage(groupMessage);
                    }
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
        chooser.setDialogTitle("Chọn file gửi trực tiếp cho " + peer.username());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        sendPath(peer, file, fileButton);
    }

    private void openCamera() {
        PeerInfo peer = selectedPeer;
        if (peer == null) {
            return;
        }
        videoButton.setEnabled(false);
        try {
            new CameraCaptureDialog(this, peer.username(),
                    file -> sendPath(peer, file, videoButton),
                    this::chooseAndSendVideoFile,
                    () -> videoButton.setEnabled(selectedPeer != null
                            && client.state() == ConnectionState.CONNECTED))
                    .setVisible(true);
        } catch (LinkageError missingCameraLibrary) {
            videoButton.setEnabled(true);
            showError("Thiếu thư viện camera. Hãy chạy scripts/build.ps1 rồi mở lại client.");
        }
    }

    private void chooseAndSendVideoFile() {
        PeerInfo peer = selectedPeer;
        if (peer == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn video gửi trực tiếp cho " + peer.username());
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Video (*.mp4, *.mov, *.avi, *.mkv, *.webm)",
                "mp4", "mov", "avi", "mkv", "webm"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path video = chooser.getSelectedFile().toPath();
        if (mediaKind(video.getFileName().toString()) != MediaKind.VIDEO) {
            showError("Hãy chọn file video MP4, MOV, AVI, MKV hoặc WEBM.");
            return;
        }
        sendPath(peer, video, videoButton);
    }

    private void toggleAudioRecording() {
        if (audioRecorder.isRecording()) {
            stopAudioRecording();
            return;
        }
        PeerInfo peer = selectedPeer;
        if (peer == null) {
            return;
        }
        audioButton.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                audioRecorder.start();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    audioRecordingPeer = peer;
                    audioButton.setActive(true);
                    audioButton.setToolTipText("Dừng thu và gửi âm thanh");
                    audioButton.setEnabled(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    audioButton.setEnabled(selectedPeer != null);
                } catch (ExecutionException failure) {
                    audioButton.setEnabled(selectedPeer != null);
                    showError(friendlyMessage(failure.getCause()));
                }
            }
        }.execute();
    }

    private void stopAudioRecording() {
        PeerInfo peer = audioRecordingPeer;
        audioButton.setEnabled(false);
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                return audioRecorder.stop();
            }

            @Override
            protected void done() {
                audioButton.setActive(false);
                audioButton.setToolTipText("Thu và gửi âm thanh");
                audioRecordingPeer = null;
                try {
                    Path audio = get();
                    if (peer == null) {
                        throw new IOException("Không còn người nhận âm thanh");
                    }
                    sendPath(peer, audio, audioButton);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    audioButton.setEnabled(selectedPeer != null);
                } catch (ExecutionException | IOException failure) {
                    audioButton.setEnabled(selectedPeer != null);
                    Throwable cause = failure instanceof ExecutionException
                            ? failure.getCause() : failure;
                    showError(friendlyMessage(cause));
                }
            }
        }.execute();
    }

    private void sendPath(PeerInfo peer, Path file, JButton sourceButton) {
        sourceButton.setEnabled(false);
        new SwingWorker<TransferHandle, Void>() {
            @Override
            protected TransferHandle doInBackground() throws Exception {
                return client.sendFile(peer.username(), file);
            }

            @Override
            protected void done() {
                sourceButton.setEnabled(
                        selectedPeer != null && client.state() == ConnectionState.CONNECTED);
                try {
                    TransferHandle handle = get();
                    outgoingSourcePaths.put(handle.fileId(), file);
                    FileEntry entry = fileEntries.get(handle.fileId());
                    if (entry != null) {
                        entry.sourcePath = file;
                    }
                    if (entry == null || entry.isActive()) {
                        outgoingTransfers.put(handle.fileId(), handle);
                    }
                    renderSelectedConversation();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    addFailedFileAttempt(peer, file, friendlyMessage(failure.getCause()));
                }
            }
        }.execute();
    }

    private void addMessage(ChatMessage message) {
        if (!displayedMessageIds.add(message.messageId())) {
            return;
        }
        String peerName = message.sender().equals(client.clientName())
                ? message.receiver() : message.sender();
        historyPeers.add(peerName);
        histories.computeIfAbsent(peerName, ignored -> new ArrayList<>())
                .add(new TextEntry(message));
        if (selectedPeer != null && selectedPeer.username().equals(peerName)) {
            messagesPanel.add(createMessageBubble(message));
            messagesPanel.add(Box.createVerticalStrut(10));
            messagesPanel.revalidate();
            messagesPanel.repaint();
            scrollToBottom();
        }
    }

    private void addGroupMessage(GroupMessage message) {
        if (!displayedGroupIds.add(message.messageId())) {
            return;
        }
        groupHistories.computeIfAbsent(message.groupName(), ignored -> new ArrayList<>())
                .add(message);
        if (selectedGroup != null && selectedGroup.name().equals(message.groupName())) {
            messagesPanel.add(createGroupMessageBubble(message));
            messagesPanel.add(Box.createVerticalStrut(10));
            messagesPanel.revalidate();
            messagesPanel.repaint();
            scrollToBottom();
        }
    }

    private void restoreHistory(ChatHistoryStore.Snapshot saved) {
        if (saved == null) {
            return;
        }
        for (ChatMessage message : saved.directMessages()) {
            if (displayedMessageIds.add(message.messageId())) {
                String peer = message.sender().equals(client.clientName())
                        ? message.receiver() : message.sender();
                historyPeers.add(peer);
                histories.computeIfAbsent(peer, ignored -> new ArrayList<>()).add(new TextEntry(message));
            }
        }
        for (GroupMessage message : saved.groupMessages()) {
            if (displayedGroupIds.add(message.messageId())) {
                groupHistories.computeIfAbsent(message.groupName(), ignored -> new ArrayList<>())
                        .add(message);
            }
        }
        for (ChatHistoryStore.StoredFile stored : saved.files()) {
            FileEntry entry = new FileEntry(stored.file(), stored.direction(), stored.createdAt());
            entry.progress = new TransferProgress(stored.file(), stored.direction(), stored.state(),
                    stored.transferredBytes(), 0, 0, "Đã khôi phục từ lịch sử");
            entry.sourcePath = stored.sourcePath();
            entry.savedPath = stored.savedPath();
            entry.downloadedPath = stored.downloadedPath();
            entry.failureReason = stored.failureReason();
            fileEntries.put(entry.file.fileId(), entry);
            String peer = stored.direction() == TransferDirection.SENDING
                    ? entry.file.receiver() : entry.file.sender();
            historyPeers.add(peer);
            histories.computeIfAbsent(peer, ignored -> new ArrayList<>()).add(entry);
        }
        for (List<ConversationEntry> conversation : histories.values()) {
            conversation.sort(Comparator.comparing(entry -> entry instanceof TextEntry text
                    ? text.message().timestamp() : ((FileEntry) entry).createdAt));
        }
    }

    private boolean isPeerOnline(String username) {
        return rawUsers.stream().anyMatch(peer -> peer.username().equals(username));
    }

    private void renderHistory() {
        messagesPanel.removeAll();
        PeerInfo peer = selectedPeer;
        GroupInfo group = selectedGroup;

        if (group != null) {
            for (GroupMessage message : groupHistories.getOrDefault(group.name(), List.of())) {
                messagesPanel.add(createGroupMessageBubble(message));
                messagesPanel.add(Box.createVerticalStrut(10));
            }
            messagesPanel.add(Box.createVerticalGlue());
        } else if (peer == null) {
            // Empty State view (Messenger style)
            JPanel emptyBox = UiTheme.panel(null, UiTheme.CHAT_BG);
            emptyBox.setLayout(new BoxLayout(emptyBox, BoxLayout.Y_AXIS));
            emptyBox.setAlignmentX(Component.CENTER_ALIGNMENT);

            JPanel emptyIcon = new EmptyChatIcon();
            emptyIcon.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel emptyTitle = UiTheme.label(
                    "Chào mừng đến với Chat P2P",
                    new Font("Segoe UI", Font.BOLD, 18), UiTheme.NAVY);
            emptyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel emptySub = UiTheme.label(
                    "Chọn một người dùng hoặc nhóm từ danh sách bên trái để bắt đầu trò chuyện.",
                    UiTheme.SUBTITLE, UiTheme.MUTED);
            emptySub.setAlignmentX(Component.CENTER_ALIGNMENT);

            emptyBox.add(emptyIcon);
            emptyBox.add(Box.createVerticalStrut(14));
            emptyBox.add(emptyTitle);
            emptyBox.add(Box.createVerticalStrut(6));
            emptyBox.add(emptySub);

            messagesPanel.add(Box.createVerticalGlue());
            messagesPanel.add(emptyBox);
            messagesPanel.add(Box.createVerticalGlue());
        } else {
            for (ConversationEntry entry : histories.getOrDefault(peer.username(), List.of())) {
                if (entry instanceof TextEntry textEntry) {
                    messagesPanel.add(createMessageBubble(textEntry.message()));
                } else if (entry instanceof FileEntry fileEntry) {
                    messagesPanel.add(createFileBubble(fileEntry));
                }
                messagesPanel.add(Box.createVerticalStrut(10));
            }
            messagesPanel.add(Box.createVerticalGlue());
        }
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    private JPanel createGroupMessageBubble(GroupMessage message) {
        boolean mine = message.sender().equals(client.clientName());
        if (ZaloMedia.isEmoji(message.content())) {
            return createMediaBubble(message.sender(), message.timestamp(), mine,
                    ZaloMedia.extractMediaId(message.content()), ChatMediaType.EMOJI, true);
        }
        if (ZaloMedia.isSticker(message.content())) {
            return createMediaBubble(message.sender(), message.timestamp(), mine,
                    ZaloMedia.extractMediaId(message.content()), ChatMediaType.STICKER, true);
        }
        if (ZaloMedia.isGif(message.content())) {
            return createMediaBubble(message.sender(), message.timestamp(), mine,
                    ZaloMedia.extractMediaId(message.content()), ChatMediaType.GIF, true);
        }
        JPanel row = UiTheme.panel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0), UiTheme.CHAT_BG);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));

        if (!mine) {
            // Left circular avatar for peer
            JPanel miniAvatar = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        UiTheme.drawAvatar(g2, message.sender(), 0, 0, 30, false, false);
                    } finally {
                        g2.dispose();
                    }
                }
            };
            miniAvatar.setPreferredSize(new Dimension(30, 30));
            miniAvatar.setOpaque(false);
            row.add(miniAvatar);
            row.add(Box.createHorizontalStrut(8));
        }

        JPanel bubble = new RoundedPanel(mine ? UiTheme.PRIMARY : UiTheme.WHITE, 18);
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));

        if (!mine) {
            JLabel sender = UiTheme.label(
                    message.sender(),
                    new Font("Segoe UI", Font.BOLD, 11),
                    UiTheme.getAvatarColor(message.sender()));
            bubble.add(sender);
            bubble.add(Box.createVerticalStrut(2));
        }

        EmojiTextPane content = createRichMessageContent(message.content(), mine);
        JLabel time = UiTheme.label(
                MESSAGE_TIME.format(message.timestamp()),
                new Font("Segoe UI", Font.PLAIN, 10),
                mine ? new Color(219, 234, 254) : UiTheme.MUTED);

        if (mine) {
            content.setAlignmentX(Component.RIGHT_ALIGNMENT);
            time.setAlignmentX(Component.RIGHT_ALIGNMENT);
        }

        bubble.add(content);
        bubble.add(Box.createVerticalStrut(3));
        bubble.add(time);
        row.add(bubble);
        return row;
    }

    private JPanel createMediaBubble(
            String sender, Instant timestamp, boolean mine, String mediaId, ChatMediaType mediaType, boolean isGroup) {
        JPanel row = UiTheme.panel(
                new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0),
                UiTheme.CHAT_BG);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, mediaType == ChatMediaType.EMOJI ? 90 : 170));

        if (!mine) {
            JPanel miniAvatar = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        UiTheme.drawAvatar(g2, sender, 0, 0, 30, false, false);
                    } finally {
                        g2.dispose();
                    }
                }
            };
            miniAvatar.setPreferredSize(new Dimension(30, 30));
            miniAvatar.setOpaque(false);
            row.add(miniAvatar);
            row.add(Box.createHorizontalStrut(8));
        }

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setOpaque(false);

        if (!mine && isGroup) {
            JLabel senderLabel = UiTheme.label(
                    sender,
                    new Font("Segoe UI", Font.BOLD, 11),
                    UiTheme.getAvatarColor(sender));
            senderLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(senderLabel);
            card.add(Box.createVerticalStrut(3));
        }

        Icon icon = switch (mediaType) {
            case EMOJI -> ZaloMedia.getEmojiIcon(mediaId);
            case STICKER -> ZaloMedia.getStickerIcon(mediaId);
            case GIF -> ZaloMedia.getGifIcon(mediaId);
        };
        JLabel mediaLabel;
        if (icon != null) {
            mediaLabel = new JLabel(icon);
        } else {
            mediaLabel = UiTheme.label(
                    "[" + mediaType.name() + ": " + mediaId + "]",
                    UiTheme.BODY, mine ? UiTheme.PRIMARY : UiTheme.NAVY);
        }
        mediaLabel.setAlignmentX(mine ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        card.add(mediaLabel);

        JLabel time = UiTheme.label(
                MESSAGE_TIME.format(timestamp),
                new Font("Segoe UI", Font.PLAIN, 10),
                UiTheme.MUTED);
        time.setAlignmentX(mine ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        card.add(Box.createVerticalStrut(3));
        card.add(time);

        row.add(card);
        return row;
    }

    private JPanel createMessageBubble(ChatMessage message) {
        boolean mine = message.sender().equals(client.clientName());
        if (ZaloMedia.isEmoji(message.content())) {
            return createMediaBubble(message.sender(), message.timestamp(), mine,
                    ZaloMedia.extractMediaId(message.content()), ChatMediaType.EMOJI, false);
        }
        if (ZaloMedia.isSticker(message.content())) {
            return createMediaBubble(message.sender(), message.timestamp(), mine,
                    ZaloMedia.extractMediaId(message.content()), ChatMediaType.STICKER, false);
        }
        if (ZaloMedia.isGif(message.content())) {
            return createMediaBubble(message.sender(), message.timestamp(), mine,
                    ZaloMedia.extractMediaId(message.content()), ChatMediaType.GIF, false);
        }
        JPanel row = UiTheme.panel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0), UiTheme.CHAT_BG);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

        if (!mine) {
            // Left circular avatar for peer
            JPanel miniAvatar = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        UiTheme.drawAvatar(g2, message.sender(), 0, 0, 30, false, false);
                    } finally {
                        g2.dispose();
                    }
                }
            };
            miniAvatar.setPreferredSize(new Dimension(30, 30));
            miniAvatar.setOpaque(false);
            row.add(miniAvatar);
            row.add(Box.createHorizontalStrut(8));
        }

        JPanel bubble = new RoundedPanel(mine ? UiTheme.PRIMARY : UiTheme.WHITE, 18);
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));

        EmojiTextPane content = createRichMessageContent(message.content(), mine);
        JLabel time = UiTheme.label(
                MESSAGE_TIME.format(message.timestamp()),
                new Font("Segoe UI", Font.PLAIN, 10),
                mine ? new Color(219, 234, 254) : UiTheme.MUTED);

        if (mine) {
            content.setAlignmentX(Component.RIGHT_ALIGNMENT);
            time.setAlignmentX(Component.RIGHT_ALIGNMENT);
        }

        bubble.add(content);
        bubble.add(Box.createVerticalStrut(3));
        bubble.add(time);
        row.add(bubble);
        return row;
    }

    private EmojiTextPane createRichMessageContent(String text, boolean mine) {
        EmojiTextPane content = new EmojiTextPane(null);
        content.setFont(UiTheme.BODY);
        content.showMessage(text, mine ? UiTheme.WHITE : UiTheme.NAVY);
        content.setEditable(false);
        content.setFocusable(false);
        content.setBorder(null);
        content.setSize(310, Short.MAX_VALUE);
        Dimension preferred = content.getPreferredSize();
        content.setPreferredSize(new Dimension(310, Math.max(28, preferred.height)));
        return content;
    }

    private JPanel createFileBubble(FileEntry entry) {
        boolean mine = entry.direction == TransferDirection.SENDING;
        MediaKind kind = mediaKind(entry.file.fileName());
        Path playable = playablePath(entry);
        boolean visualPreview = (kind == MediaKind.IMAGE || kind == MediaKind.VIDEO)
                && playable != null && Files.isRegularFile(playable);
        Color bubbleColor = mine ? new Color(239, 246, 255) : UiTheme.WHITE;
        JPanel row = UiTheme.panel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0), UiTheme.CHAT_BG);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, visualPreview ? 430 : 200));

        if (!mine) {
            JPanel miniAvatar = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        UiTheme.drawAvatar(g2, entry.file.sender(), 0, 0, 30, false, false);
                    } finally {
                        g2.dispose();
                    }
                }
            };
            miniAvatar.setPreferredSize(new Dimension(30, 30));
            miniAvatar.setOpaque(false);
            row.add(miniAvatar);
            row.add(Box.createHorizontalStrut(8));
        }

        JPanel bubble = new RoundedPanel(bubbleColor, 18);
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        bubble.setPreferredSize(new Dimension(440, visualPreview ? 390 : 158));

        JLabel owner = UiTheme.label(
                transferOwnerText(mine, entry.file.sender(), kind),
                new Font("Segoe UI", Font.BOLD, 11), UiTheme.SLATE);

        // File/media card row with a vector icon.
        JPanel cardHeader = UiTheme.panel(new BorderLayout(10, 0), bubbleColor);
        JPanel fileIcon = new MediaFileIcon(kind);

        JPanel nameAndSize = UiTheme.panel(null, bubbleColor);
        nameAndSize.setLayout(new BoxLayout(nameAndSize, BoxLayout.Y_AXIS));
        JLabel name = UiTheme.label(
                entry.file.fileName(), new Font("Segoe UI", Font.BOLD, 14), UiTheme.NAVY);
        JLabel size = UiTheme.label(
                formatBytes(entry.file.size()), new Font("Segoe UI", Font.PLAIN, 12), UiTheme.MUTED);
        nameAndSize.add(name);
        nameAndSize.add(Box.createVerticalStrut(2));
        nameAndSize.add(size);

        JPanel cardContent;
        if (visualPreview) {
            cardContent = UiTheme.panel(null, bubbleColor);
            cardContent.setLayout(new BoxLayout(cardContent, BoxLayout.Y_AXIS));
            JPanel preview = kind == MediaKind.IMAGE
                    ? createImagePreview(entry, playable)
                    : createVideoPreview(entry, bubbleColor);
            preview.setAlignmentX(Component.LEFT_ALIGNMENT);
            nameAndSize.setAlignmentX(Component.LEFT_ALIGNMENT);
            cardContent.add(preview);
            cardContent.add(Box.createVerticalStrut(5));
            cardContent.add(nameAndSize);
        } else {
            cardHeader.add(fileIcon, BorderLayout.WEST);
            cardHeader.add(nameAndSize, BorderLayout.CENTER);
            cardContent = cardHeader;
        }

        // Progress bar
        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setForeground(entry.failureReason == null ? UiTheme.PRIMARY : UiTheme.DANGER);
        progressBar.setBackground(new Color(226, 232, 240));
        progressBar.setBorder(BorderFactory.createEmptyBorder());
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 12));
        progressBar.setPreferredSize(new Dimension(320, 12));

        TransferProgress progress = entry.progress;
        int percent = progress == null ? 0 : progress.percent();
        progressBar.setValue(percent);
        progressBar.setString(percent + "%");

        String statusText;
        Color statusColor = UiTheme.SLATE;
        if (entry.downloadError != null) {
            statusText = "Lỗi lưu file: " + entry.downloadError;
            statusColor = UiTheme.DANGER;
        } else if (entry.downloadedPath != null) {
            statusText = "Đã lưu: " + entry.downloadedPath.getFileName();
            statusColor = UiTheme.SUCCESS;
        } else if (entry.failureReason != null) {
            statusText = "Thất bại: " + entry.failureReason;
            statusColor = UiTheme.DANGER;
            progressBar.setString("Lỗi");
        } else if (progress == null || progress.state() == TransferState.WAITING) {
            statusText = mine ? "Đang chờ đối phương nhận..." : "Đang kết nối nhận file...";
        } else if (progress.state() == TransferState.SUCCESS) {
            statusText = mine ? "Đã gửi thành công qua P2P" : "Đã nhận hoàn tất";
            statusColor = UiTheme.SUCCESS;
            progressBar.setValue(100);
            progressBar.setString("100%");
        } else if (progress.state() == TransferState.CANCELLED) {
            statusText = "Đã hủy truyền file";
            statusColor = UiTheme.DANGER;
        } else {
            statusText = formatBytes(progress.transferredBytes()) + " / "
                    + formatBytes(entry.file.size()) + " • "
                    + formatSpeed(progress.bytesPerSecond()) + " • "
                    + formatRemaining(progress.estimatedSeconds());
        }
        JLabel status = UiTheme.label(statusText, new Font("Segoe UI", Font.PLAIN, 11), statusColor);

        JPanel actionRow = UiTheme.panel(new BorderLayout(), bubbleColor);
        JLabel time = UiTheme.label(
                MESSAGE_TIME.format(entry.createdAt), new Font("Segoe UI", Font.PLAIN, 10), UiTheme.MUTED);
        actionRow.add(time, BorderLayout.WEST);

        JPanel mediaActions = UiTheme.panel(
                new FlowLayout(FlowLayout.RIGHT, 6, 0), bubbleColor);

        boolean canDownload = !mine
                && entry.savedPath != null
                && progress != null
                && progress.state() == TransferState.SUCCESS;

        if (canDownload) {
            DownloadIconButton download = new DownloadIconButton();
            download.setEnabled(!entry.downloading);
            download.setToolTipText("Lưu file về máy");
            download.addActionListener(event -> downloadReceivedFile(entry));
            mediaActions.add(download);
        }

        boolean completed = progress != null && progress.state() == TransferState.SUCCESS;
        if (completed && playable != null && Files.isRegularFile(playable)) {
            if (kind == MediaKind.AUDIO) {
                JButton play = createMediaActionButton(
                        playingAudio.containsKey(entry.file.fileId()) ? "DỪNG" : "PHÁT");
                play.setToolTipText("Phát hoặc dừng âm thanh");
                play.addActionListener(event -> toggleAudioPlayback(entry));
                mediaActions.add(play);
            } else if (kind == MediaKind.VIDEO) {
                JButton openVideo = createMediaActionButton("MỞ VIDEO");
                openVideo.setToolTipText("Phát video bằng ứng dụng mặc định");
                openVideo.addActionListener(event -> openVideo(entry));
                mediaActions.add(openVideo);
            }
        }

        TransferHandle handle = outgoingTransfers.get(entry.file.fileId());
        if (mine && entry.isActive() && handle != null) {
            JButton cancel = new JButton("Hủy gửi");
            UiTheme.styleSecondaryButton(cancel);
            cancel.setForeground(UiTheme.DANGER);
            cancel.addActionListener(event -> {
                handle.cancel();
                cancel.setEnabled(false);
                cancel.setText("Đang hủy...");
            });
            mediaActions.add(cancel);
        }
        if (mediaActions.getComponentCount() > 0) {
            actionRow.add(mediaActions, BorderLayout.EAST);
        }

        bubble.add(owner);
        bubble.add(Box.createVerticalStrut(6));
        bubble.add(cardContent);
        bubble.add(Box.createVerticalStrut(8));
        bubble.add(progressBar);
        bubble.add(Box.createVerticalStrut(4));
        bubble.add(status);
        bubble.add(Box.createVerticalStrut(6));
        bubble.add(actionRow);

        row.add(bubble);
        return row;
    }

    private JPanel createImagePreview(FileEntry entry, Path source) {
        Path key = source.toAbsolutePath().normalize();
        JPanel preview = UiTheme.panel(new BorderLayout(), new Color(226, 232, 240));
        preview.setBorder(new RoundedBorder(new Color(203, 213, 225), 14));
        preview.setPreferredSize(new Dimension(400, 220));
        preview.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));

        ImageIcon thumbnail = imageThumbnails.get(key);
        JLabel content;
        if (thumbnail != null) {
            content = new JLabel(thumbnail, SwingConstants.CENTER);
            content.setToolTipText("Ảnh: " + entry.file.fileName());
        } else {
            String text = failedThumbnails.contains(key)
                    ? "Không thể xem trước ảnh" : "Đang tải ảnh...";
            content = UiTheme.label(text, UiTheme.BODY, UiTheme.MUTED);
            content.setHorizontalAlignment(SwingConstants.CENTER);
            requestImageThumbnail(entry, key);
        }
        preview.add(content, BorderLayout.CENTER);
        return preview;
    }

    private JPanel createVideoPreview(FileEntry entry, Color bubbleColor) {
        JPanel preview = UiTheme.panel(new BorderLayout(), bubbleColor);
        preview.setPreferredSize(new Dimension(400, 220));
        preview.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
        VideoPreviewButton play = new VideoPreviewButton(entry.file.fileName());
        play.addActionListener(event -> openVideo(entry));
        preview.add(play, BorderLayout.CENTER);
        return preview;
    }

    private void requestImageThumbnail(FileEntry entry, Path source) {
        if (imageThumbnails.containsKey(source)
                || failedThumbnails.contains(source)
                || !loadingThumbnails.add(source)) {
            return;
        }
        new SwingWorker<ImageIcon, Void>() {
            @Override
            protected ImageIcon doInBackground() throws Exception {
                BufferedImage original = ImageIO.read(source.toFile());
                if (original == null) {
                    throw new IOException("Định dạng ảnh không được Java hỗ trợ");
                }
                double scale = Math.min(
                        1.0,
                        Math.min(390.0 / original.getWidth(), 210.0 / original.getHeight()));
                int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
                BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D canvas = scaled.createGraphics();
                try {
                    canvas.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    canvas.setRenderingHint(
                            RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_QUALITY);
                    canvas.drawImage(original, 0, 0, width, height, null);
                } finally {
                    canvas.dispose();
                }
                return new ImageIcon(scaled);
            }

            @Override
            protected void done() {
                loadingThumbnails.remove(source);
                try {
                    imageThumbnails.put(source, get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failedThumbnails.add(source);
                } catch (ExecutionException failure) {
                    failedThumbnails.add(source);
                }
                renderFileConversation(entry);
            }
        }.execute();
    }

    private void toggleAudioPlayback(FileEntry entry) {
        UUID fileId = entry.file.fileId();
        Clip current = playingAudio.remove(fileId);
        if (current != null) {
            current.stop();
            current.close();
            renderFileConversation(entry);
            return;
        }
        Path audio = playablePath(entry);
        if (audio == null || !Files.isRegularFile(audio)) {
            showError("File âm thanh không còn tồn tại.");
            return;
        }
        new SwingWorker<Clip, Void>() {
            @Override
            protected Clip doInBackground() throws Exception {
                try (AudioInputStream input = AudioSystem.getAudioInputStream(audio.toFile())) {
                    Clip clip = AudioSystem.getClip();
                    clip.open(input);
                    return clip;
                }
            }

            @Override
            protected void done() {
                try {
                    Clip clip = get();
                    playingAudio.put(fileId, clip);
                    clip.addLineListener(event -> {
                        if (event.getType() == LineEvent.Type.STOP
                                && clip.getFramePosition() >= clip.getFrameLength()) {
                            SwingUtilities.invokeLater(() -> {
                                if (playingAudio.remove(fileId, clip)) {
                                    clip.close();
                                    renderFileConversation(entry);
                                }
                            });
                        }
                    });
                    clip.start();
                    renderFileConversation(entry);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    showError("Không thể phát âm thanh: "
                            + friendlyMessage(failure.getCause()));
                }
            }
        }.execute();
    }

    private void openVideo(FileEntry entry) {
        Path video = playablePath(entry);
        if (video == null || !Files.isRegularFile(video)) {
            showError("File video không còn tồn tại.");
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (!Desktop.isDesktopSupported()
                        || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    throw new IOException("Máy không có ứng dụng phát video mặc định");
                }
                Desktop.getDesktop().open(video.toFile());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    showError("Không thể mở video: " + friendlyMessage(failure.getCause()));
                }
            }
        }.execute();
    }

    private JButton createMediaActionButton(String text) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 10));
        button.setForeground(UiTheme.PRIMARY);
        button.setBackground(new Color(219, 234, 254));
        button.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static Path playablePath(FileEntry entry) {
        return entry.direction == TransferDirection.SENDING
                ? entry.sourcePath : entry.savedPath;
    }

    private static String transferOwnerText(boolean mine, String sender, MediaKind kind) {
        String content = switch (kind) {
            case IMAGE -> "một ảnh";
            case AUDIO -> "một đoạn âm thanh";
            case VIDEO -> "một video";
            case FILE -> "một file";
        };
        return mine ? "Bạn đã gửi " + content + " trực tiếp"
                : sender + " đã gửi " + content;
    }

    private static MediaKind mediaKind(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                || lower.endsWith(".bmp")) {
            return MediaKind.IMAGE;
        }
        if (lower.endsWith(".wav") || lower.endsWith(".aiff")
                || lower.endsWith(".aif") || lower.endsWith(".au")) {
            return MediaKind.AUDIO;
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".mov")
                || lower.endsWith(".avi") || lower.endsWith(".mkv")
                || lower.endsWith(".webm")) {
            return MediaKind.VIDEO;
        }
        return MediaKind.FILE;
    }

    private void updateFileEntry(TransferProgress progress) {
        FileEntry entry = fileEntries.get(progress.file().fileId());
        if (entry == null) {
            entry = new FileEntry(progress.file(), progress.direction(), Instant.now());
            entry.sourcePath = outgoingSourcePaths.get(progress.file().fileId());
            fileEntries.put(progress.file().fileId(), entry);
            String peerName = progress.direction() == TransferDirection.SENDING
                    ? progress.file().receiver() : progress.file().sender();
            historyPeers.add(peerName);
            histories.computeIfAbsent(peerName, ignored -> new ArrayList<>()).add(entry);
        }
        entry.progress = progress;
        if (!entry.isActive()) {
            outgoingTransfers.remove(progress.file().fileId());
        }
        renderFileConversation(entry);
    }

    private void markFileCompleted(FileInfo file, Path savedPath) {
        FileEntry entry = fileEntries.get(file.fileId());
        if (entry != null) {
            if (savedPath != null) {
                entry.savedPath = savedPath;
            }
            renderFileConversation(entry);
        }
    }

    private void markFileFailed(FileInfo file, String reason) {
        FileEntry entry = fileEntries.get(file.fileId());
        if (entry == null) {
            TransferDirection direction = file.sender().equals(client.clientName())
                    ? TransferDirection.SENDING : TransferDirection.RECEIVING;
            entry = new FileEntry(file, direction, Instant.now());
            fileEntries.put(file.fileId(), entry);
            String peerName = direction == TransferDirection.SENDING
                    ? file.receiver() : file.sender();
            historyPeers.add(peerName);
            histories.computeIfAbsent(peerName, ignored -> new ArrayList<>()).add(entry);
        }
        if (entry.progress == null || entry.progress.state() != TransferState.CANCELLED) {
            entry.failureReason = reason;
        }
        outgoingTransfers.remove(file.fileId());
        renderFileConversation(entry);
    }

    private void addFailedFileAttempt(PeerInfo peer, Path file, String reason) {
        long size = 0;
        try {
            size = Files.size(file);
        } catch (IOException ignored) {
        }
        FileInfo info = new FileInfo(
                UUID.randomUUID(), file.getFileName().toString(), size,
                client.clientName(), peer.username());
        FileEntry entry = new FileEntry(info, TransferDirection.SENDING, Instant.now());
        entry.sourcePath = file;
        entry.failureReason = reason;
        fileEntries.put(info.fileId(), entry);
        historyPeers.add(peer.username());
        histories.computeIfAbsent(peer.username(), ignored -> new ArrayList<>()).add(entry);
        client.rememberFailedFile(info, file, reason);
        renderFileConversation(entry);
    }

    private void downloadReceivedFile(FileEntry entry) {
        Path source = entry.savedPath;
        if (source == null || !Files.isRegularFile(source)) {
            entry.downloadError = "File nhận không còn tồn tại trên đĩa";
            renderFileConversation(entry);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Lưu file " + entry.file.fileName());
        chooser.setSelectedFile(Path.of(entry.file.fileName()).toFile());
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path requestedTarget = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        entry.downloading = true;
        entry.downloadError = null;
        renderFileConversation(entry);
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                Path normalizedSource = source.toAbsolutePath().normalize();
                if (normalizedSource.equals(requestedTarget)) {
                    return normalizedSource;
                }
                Path target = availableDownloadTarget(requestedTarget);
                Files.copy(normalizedSource, target);
                return target;
            }

            @Override
            protected void done() {
                entry.downloading = false;
                try {
                    entry.downloadedPath = get();
                    client.rememberDownloadedFile(entry.file.fileId(), entry.downloadedPath);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    entry.downloadError = "Đã dừng tải file";
                } catch (ExecutionException failure) {
                    entry.downloadError = friendlyMessage(failure.getCause());
                }
                renderFileConversation(entry);
            }
        }.execute();
    }

    private static Path availableDownloadTarget(Path requested) throws IOException {
        if (!Files.exists(requested)) {
            return requested;
        }
        Path parent = requested.getParent();
        String fileName = requested.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 1; index <= 10_000; index++) {
            Path candidate = parent.resolve(base + " (" + index + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Không tìm được tên file trống trong thư mục");
    }

    private void renderFileConversation(FileEntry entry) {
        PeerInfo peer = selectedPeer;
        String peerName = entry.direction == TransferDirection.SENDING
                ? entry.file.receiver() : entry.file.sender();
        if (peer != null && peer.username().equals(peerName)) {
            renderHistory();
        }
    }

    private void renderSelectedConversation() {
        if (selectedPeer != null) {
            renderHistory();
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
        emojiButton.setEnabled(enabled);
        gifButton.setEnabled(enabled);
        fileButton.setEnabled(enabled && selectedPeer != null);
        audioButton.setEnabled(audioRecorder.isRecording()
                || (enabled && selectedPeer != null));
        videoButton.setEnabled(enabled && selectedPeer != null);
    }

    private void disconnectAndReturn() {
        int choice = JOptionPane.showConfirmDialog(
                this, "Bạn có chắc chắn muốn đăng xuất?", "Xác nhận", JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION) {
            closeClient();
            dispose();
            SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
        }
    }

    private void closeClient() {
        if (closed.compareAndSet(false, true)) {
            client.removeEventListener(eventListener);
            connectionTimer.stop();
            audioRecorder.close();
            for (Clip clip : playingAudio.values()) {
                clip.stop();
                clip.close();
            }
            playingAudio.clear();
            client.close();
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Thông báo lỗi", JOptionPane.ERROR_MESSAGE);
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> messagesScroll.getVerticalScrollBar()
                .setValue(messagesScroll.getVerticalScrollBar().getMaximum()));
    }

    private static String friendlyMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null
                ? "Thao tác mạng gặp sự cố"
                : failure.getMessage();
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
        return seconds < 0 ? "Đang tính..." : "Còn " + seconds + "s";
    }

    private sealed interface ConversationEntry permits TextEntry, FileEntry {
    }

    private record TextEntry(ChatMessage message) implements ConversationEntry {
    }

    private enum MediaKind {
        FILE,
        IMAGE,
        AUDIO,
        VIDEO
    }

    /** File-card icon with a stable color and shape for documents, audio, and video. */
    private static final class MediaFileIcon extends JPanel {
        private final MediaKind kind;

        private MediaFileIcon(MediaKind kind) {
            this.kind = kind;
            setOpaque(false);
            setPreferredSize(new Dimension(42, 42));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setStroke(new BasicStroke(
                        2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int centerX = getWidth() / 2;
                int centerY = getHeight() / 2;
                switch (kind) {
                    case FILE -> {
                        canvas.setColor(new Color(219, 234, 254));
                        canvas.fillRoundRect(4, 3, 34, 36, 10, 10);
                        canvas.setColor(UiTheme.PRIMARY);
                        canvas.drawRoundRect(12, 8, 19, 25, 3, 3);
                        canvas.drawLine(16, 16, 27, 16);
                        canvas.drawLine(16, 21, 27, 21);
                        canvas.drawLine(16, 26, 24, 26);
                    }
                    case IMAGE -> {
                        canvas.setColor(new Color(220, 252, 231));
                        canvas.fillRoundRect(3, 3, 36, 36, 11, 11);
                        canvas.setColor(new Color(22, 163, 74));
                        canvas.drawRoundRect(10, 10, 22, 22, 5, 5);
                        canvas.fillOval(23, 14, 4, 4);
                        Polygon mountains = new Polygon();
                        mountains.addPoint(12, 29);
                        mountains.addPoint(18, 21);
                        mountains.addPoint(22, 26);
                        mountains.addPoint(26, 22);
                        mountains.addPoint(31, 29);
                        canvas.drawPolyline(
                                mountains.xpoints, mountains.ypoints, mountains.npoints);
                    }
                    case AUDIO -> {
                        canvas.setColor(new Color(237, 233, 254));
                        canvas.fillOval(3, 3, 36, 36);
                        canvas.setColor(new Color(124, 58, 237));
                        canvas.drawRoundRect(centerX - 5, centerY - 12, 10, 18, 8, 8);
                        canvas.drawArc(centerX - 9, centerY - 6, 18, 16, 180, 180);
                        canvas.drawLine(centerX, centerY + 10, centerX, centerY + 14);
                    }
                    case VIDEO -> {
                        canvas.setColor(new Color(254, 226, 226));
                        canvas.fillRoundRect(3, 3, 36, 36, 11, 11);
                        canvas.setColor(new Color(239, 68, 68));
                        canvas.drawRoundRect(centerX - 12, centerY - 8, 18, 16, 5, 5);
                        Polygon lens = new Polygon();
                        lens.addPoint(centerX + 7, centerY - 5);
                        lens.addPoint(centerX + 13, centerY - 9);
                        lens.addPoint(centerX + 13, centerY + 9);
                        lens.addPoint(centerX + 7, centerY + 5);
                        canvas.drawPolygon(lens);
                    }
                }
            } finally {
                canvas.dispose();
            }
        }
    }

    private static final class FileEntry implements ConversationEntry {
        private final FileInfo file;
        private final TransferDirection direction;
        private final Instant createdAt;
        private TransferProgress progress;
        private Path sourcePath;
        private Path savedPath;
        private Path downloadedPath;
        private String failureReason;
        private String downloadError;
        private boolean downloading;

        private FileEntry(FileInfo file, TransferDirection direction, Instant createdAt) {
            this.file = file;
            this.direction = direction;
            this.createdAt = createdAt;
        }

        private boolean isActive() {
            if (failureReason != null || progress == null) {
                return failureReason == null;
            }
            return progress.state() == TransferState.WAITING
                    || progress.state() == TransferState.TRANSFERRING;
        }
    }

    /** Rounded card panel with anti-aliasing. */
    private static final class RoundedPanel extends JPanel {
        private final Color fill;
        private final int arc;

        private RoundedPanel(Color fill, int arc) {
            this.fill = fill;
            this.arc = arc;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(fill);
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            } finally {
                canvas.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static final class RoundedBorder extends AbstractBorder {
        private final Color color;
        private final int arc;

        private RoundedBorder(Color color, int arc) {
            this.color = color;
            this.arc = arc;
        }

        @Override
        public void paintBorder(
                Component component, Graphics graphics,
                int x, int y, int width, int height) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(color);
                canvas.drawRoundRect(x, y, width - 1, height - 1, arc, arc);
            } finally {
                canvas.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component component) {
            return new Insets(2, 4, 2, 4);
        }
    }

    private enum ComposerIcon {
        ATTACH,
        SMILE,
        GIF,
        MICROPHONE,
        VIDEO,
        SEND
    }

    private enum ChatMediaType {
        EMOJI,
        STICKER,
        GIF
    }

    /** Vector composer button that does not depend on emoji fonts. */
    private static final class ComposerIconButton extends JButton {
        private final ComposerIcon icon;
        private boolean active;

        private ComposerIconButton(ComposerIcon icon, String tooltip) {
            this.icon = icon;
            setToolTipText(tooltip);
            setPreferredSize(new Dimension(38, 38));
            setMinimumSize(new Dimension(38, 38));
            setMaximumSize(new Dimension(38, 38));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        private void setActive(boolean active) {
            this.active = active;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                if (icon != ComposerIcon.SEND && getModel().isRollover()) {
                    canvas.setColor(new Color(231, 243, 255));
                    canvas.fillOval(2, 2, getWidth() - 4, getHeight() - 4);
                }
                canvas.setColor(!isEnabled()
                        ? UiTheme.MUTED : active ? UiTheme.DANGER : UiTheme.PRIMARY);
                canvas.setStroke(new BasicStroke(
                        icon == ComposerIcon.SEND ? 2.8f : 2.1f,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                int centerX = getWidth() / 2;
                int centerY = getHeight() / 2;
                switch (icon) {
                    case ATTACH -> {
                        canvas.rotate(-0.55, centerX, centerY);
                        canvas.drawRoundRect(centerX - 7, centerY - 12, 14, 24, 10, 10);
                        canvas.drawRoundRect(centerX - 3, centerY - 8, 6, 16, 6, 6);
                    }
                    case SMILE -> {
                        // 3D Glossy Yellow Face
                        int r = 10;
                        GradientPaint faceGrad = new GradientPaint(
                                centerX, centerY - r, new Color(255, 225, 53),
                                centerX, centerY + r, new Color(245, 158, 11));
                        canvas.setPaint(faceGrad);
                        canvas.fillOval(centerX - r, centerY - r, r * 2, r * 2);
                        // Gloss highlight
                        canvas.setColor(new Color(255, 255, 255, 160));
                        canvas.fillOval(centerX - 6, centerY - r + 2, 12, 5);
                        // Border
                        canvas.setColor(new Color(180, 83, 9, 90));
                        canvas.setStroke(new BasicStroke(1.1f));
                        canvas.drawOval(centerX - r, centerY - r, r * 2, r * 2);
                        // Eyes
                        canvas.setColor(new Color(60, 30, 10));
                        canvas.fillOval(centerX - 5, centerY - 3, 3, 3);
                        canvas.fillOval(centerX + 2, centerY - 3, 3, 3);
                        // Smile
                        canvas.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        canvas.drawArc(centerX - 5, centerY - 2, 10, 7, 200, 140);
                        // Blush
                        canvas.setColor(new Color(244, 63, 94, 130));
                        canvas.fillOval(centerX - 8, centerY, 3, 2);
                        canvas.fillOval(centerX + 5, centerY, 3, 2);
                    }
                    case GIF -> {
                        int bw = 24;
                        int bh = 17;
                        int bx = centerX - bw / 2;
                        int by = centerY - bh / 2;
                        GradientPaint gifGrad = new GradientPaint(
                                bx, by, new Color(14, 165, 233),
                                bx + bw, by + bh, new Color(37, 99, 235));
                        canvas.setPaint(gifGrad);
                        canvas.fillRoundRect(bx, by, bw, bh, 6, 6);
                        canvas.setColor(Color.WHITE);
                        canvas.setFont(new Font("Segoe UI", Font.BOLD, 10));
                        FontMetrics fm = canvas.getFontMetrics();
                        int tx = bx + (bw - fm.stringWidth("GIF")) / 2;
                        int ty = by + (bh - fm.getHeight()) / 2 + fm.getAscent();
                        canvas.drawString("GIF", tx, ty);
                    }
                    case MICROPHONE -> {
                        canvas.drawRoundRect(centerX - 5, centerY - 11, 10, 17, 9, 9);
                        canvas.drawArc(centerX - 9, centerY - 5, 18, 15, 180, 180);
                        canvas.drawLine(centerX, centerY + 10, centerX, centerY + 14);
                        canvas.drawLine(centerX - 5, centerY + 14, centerX + 5, centerY + 14);
                    }
                    case VIDEO -> {
                        canvas.drawRoundRect(centerX - 11, centerY - 8, 16, 16, 5, 5);
                        Polygon lens = new Polygon();
                        lens.addPoint(centerX + 6, centerY - 5);
                        lens.addPoint(centerX + 12, centerY - 9);
                        lens.addPoint(centerX + 12, centerY + 9);
                        lens.addPoint(centerX + 6, centerY + 5);
                        canvas.drawPolygon(lens);
                    }
                    case SEND -> {
                        // Solid paper-plane icon matching modern chat applications.
                        Polygon plane = new Polygon();
                        plane.addPoint(centerX - 11, centerY - 11);
                        plane.addPoint(centerX + 12, centerY);
                        plane.addPoint(centerX - 11, centerY + 11);
                        plane.addPoint(centerX - 6, centerY + 2);
                        plane.addPoint(centerX + 3, centerY);
                        plane.addPoint(centerX - 6, centerY - 2);
                        canvas.fillPolygon(plane);
                    }
                }
            } finally {
                canvas.dispose();
            }
        }
    }

    /** Circular download button for incoming completed files. */
    private static final class DownloadIconButton extends JButton {
        private static final int BUTTON_SIZE = 34;

        private DownloadIconButton() {
            setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
            setMinimumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
            setMaximumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(isEnabled() ? UiTheme.PRIMARY : UiTheme.MUTED);
                canvas.fillOval(1, 1, getWidth() - 2, getHeight() - 2);
                int centerX = getWidth() / 2;
                int centerY = getHeight() / 2;
                canvas.setColor(UiTheme.WHITE);
                canvas.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                canvas.drawLine(centerX, centerY - 6, centerX, centerY + 4);
                canvas.drawLine(centerX - 4, centerY, centerX, centerY + 4);
                canvas.drawLine(centerX + 4, centerY, centerX, centerY + 4);
                canvas.drawLine(centerX - 6, centerY + 7, centerX + 6, centerY + 7);
            } finally {
                canvas.dispose();
            }
        }
    }

    private enum RailIcon {
        CHAT,
        GROUP,
        LOGOUT
    }

    /** Left navigation vector icon with an active pill indicator. */
    private static final class RailIconButton extends JButton {
        private final RailIcon icon;
        private final Color iconColor;

        private RailIconButton(
                RailIcon icon, Color iconColor, String tooltip, boolean selected) {
            this.icon = icon;
            this.iconColor = iconColor;
            setSelected(selected);
            setToolTipText(tooltip);
            setPreferredSize(new Dimension(48, 48));
            setMaximumSize(new Dimension(48, 48));
            setAlignmentX(Component.CENTER_ALIGNMENT);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public void setSelected(boolean selected) {
            super.setSelected(selected);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (isSelected()) {
                    g2.setColor(new Color(255, 255, 255, 46));
                    g2.fillRoundRect(4, 4, getWidth() - 8, getHeight() - 8, 16, 16);
                } else if (getModel().isRollover()) {
                    g2.setColor(new Color(255, 255, 255, 40));
                    g2.fillRoundRect(4, 4, getWidth() - 8, getHeight() - 8, 16, 16);
                }
                g2.setColor(isEnabled() ? iconColor : new Color(191, 219, 254));
                g2.setStroke(new BasicStroke(
                        2.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int centerX = getWidth() / 2;
                int centerY = getHeight() / 2;
                switch (icon) {
                    case CHAT -> {
                        g2.fillRoundRect(centerX - 12, centerY - 10, 24, 18, 10, 10);
                        Polygon tail = new Polygon();
                        tail.addPoint(centerX - 7, centerY + 6);
                        tail.addPoint(centerX - 9, centerY + 13);
                        tail.addPoint(centerX, centerY + 7);
                        g2.fillPolygon(tail);
                        g2.setColor(UiTheme.RAIL_BG);
                        g2.fillOval(centerX - 6, centerY - 2, 3, 3);
                        g2.fillOval(centerX - 1, centerY - 2, 3, 3);
                        g2.fillOval(centerX + 4, centerY - 2, 3, 3);
                    }
                    case GROUP -> {
                        g2.drawOval(centerX - 10, centerY - 10, 8, 8);
                        g2.drawOval(centerX + 2, centerY - 10, 8, 8);
                        g2.drawArc(centerX - 14, centerY - 1, 15, 16, 15, 150);
                        g2.drawArc(centerX - 1, centerY - 1, 15, 16, 15, 150);
                    }
                    case LOGOUT -> {
                        g2.drawRoundRect(centerX - 10, centerY - 12, 13, 24, 3, 3);
                        g2.drawLine(centerX - 1, centerY, centerX + 12, centerY);
                        g2.drawLine(centerX + 7, centerY - 5, centerX + 12, centerY);
                        g2.drawLine(centerX + 7, centerY + 5, centerX + 12, centerY);
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** Colored empty-state chat icon. */
    private static final class EmptyChatIcon extends JPanel {
        private EmptyChatIcon() {
            setOpaque(false);
            setPreferredSize(new Dimension(72, 72));
            setMaximumSize(new Dimension(72, 72));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(new Color(219, 234, 254));
                canvas.fillOval(2, 2, 68, 68);
                canvas.setColor(UiTheme.PRIMARY);
                canvas.fillRoundRect(16, 18, 40, 29, 16, 16);
                Polygon tail = new Polygon();
                tail.addPoint(24, 43);
                tail.addPoint(21, 56);
                tail.addPoint(35, 47);
                canvas.fillPolygon(tail);
                canvas.setColor(Color.WHITE);
                canvas.fillOval(26, 31, 4, 4);
                canvas.fillOval(34, 31, 4, 4);
                canvas.fillOval(42, 31, 4, 4);
            } finally {
                canvas.dispose();
            }
        }
    }

    /** Inline video preview with a large play control instead of a generic file card. */
    private static final class VideoPreviewButton extends JButton {
        private final String fileName;

        private VideoPreviewButton(String fileName) {
            this.fileName = fileName;
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Mở video " + fileName);
            getAccessibleContext().setAccessibleName("Phát video " + fileName);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(getModel().isRollover()
                        ? new Color(30, 64, 175) : new Color(15, 23, 42));
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);

                int centerX = getWidth() / 2;
                int centerY = getHeight() / 2 - 8;
                canvas.setColor(new Color(255, 255, 255, 220));
                canvas.fillOval(centerX - 25, centerY - 25, 50, 50);
                canvas.setColor(UiTheme.PRIMARY);
                Polygon play = new Polygon();
                play.addPoint(centerX - 7, centerY - 12);
                play.addPoint(centerX + 14, centerY);
                play.addPoint(centerX - 7, centerY + 12);
                canvas.fillPolygon(play);

                canvas.setFont(new Font("Segoe UI", Font.BOLD, 12));
                canvas.setColor(Color.WHITE);
                FontMetrics metrics = canvas.getFontMetrics();
                String label = fileName.length() > 42
                        ? fileName.substring(0, 39) + "..." : fileName;
                int textX = Math.max(12, (getWidth() - metrics.stringWidth(label)) / 2);
                canvas.drawString(label, textX, getHeight() - 18);
            } finally {
                canvas.dispose();
            }
        }
    }

    /** Textfield with placeholder when empty. */
    private static final class PlaceholderTextField extends JTextField {
        private final String placeholder;

        private PlaceholderTextField(String placeholder) {
            this.placeholder = placeholder;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty() && !isFocusOwner()) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(UiTheme.MUTED);
                    g2.setFont(getFont());
                    Insets insets = getInsets();
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(placeholder, insets.left, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                } finally {
                    g2.dispose();
                }
            }
        }
    }

    /** Modern group list item renderer with circular avatar. */
    private final class GroupRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            GroupInfo group = (GroupInfo) value;
            boolean archived = rawGroups.stream().noneMatch(live -> live.name().equals(group.name()));
            JPanel row = UiTheme.panel(new BorderLayout(12, 0),
                    isSelected ? UiTheme.SELECTION_BG : UiTheme.WHITE);
            row.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

            JPanel avatar = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        UiTheme.drawAvatar(g2, group.name(), 0, 0, 44, true, false);
                    } finally {
                        g2.dispose();
                    }
                }
            };
            avatar.setPreferredSize(new Dimension(44, 44));
            avatar.setOpaque(false);

            JPanel text = UiTheme.panel(null, row.getBackground());
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            JLabel nameLabel = UiTheme.label(group.name(), UiTheme.BODY_BOLD, UiTheme.NAVY);
            JLabel memberLabel = UiTheme.label(
                    archived ? "Lịch sử nhóm • Chưa tham gia"
                            : group.memberCount() + " thành viên"
                                    + (group.joined() ? " • Đã tham gia" : " • Chưa tham gia"),
                    new Font("Segoe UI", Font.PLAIN, 12),
                    group.joined() ? UiTheme.SUCCESS : UiTheme.MUTED);
            text.add(nameLabel);
            text.add(Box.createVerticalStrut(2));
            text.add(memberLabel);

            row.add(avatar, BorderLayout.WEST);
            row.add(text, BorderLayout.CENTER);
            return row;
        }
    }

    /** Modern user list item renderer with circular avatar and green online dot. */
    private final class UserRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            PeerInfo peer = (PeerInfo) value;
            boolean online = isPeerOnline(peer.username());
            JPanel row = UiTheme.panel(new BorderLayout(12, 0),
                    isSelected ? UiTheme.SELECTION_BG : UiTheme.WHITE);
            row.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

            JPanel avatar = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        UiTheme.drawAvatar(g2, peer.username(), 0, 0, 44, false, online);
                    } finally {
                        g2.dispose();
                    }
                }
            };
            avatar.setPreferredSize(new Dimension(44, 44));
            avatar.setOpaque(false);

            JPanel text = UiTheme.panel(null, row.getBackground());
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            JLabel nameLabel = UiTheme.label(peer.username(), UiTheme.BODY_BOLD, UiTheme.NAVY);
            JLabel infoLabel = UiTheme.label(
                    online ? "P2P sẵn sàng • " + peer.host() + ":" + peer.port()
                            : "Ngoại tuyến • Lịch sử chat",
                    new Font("Segoe UI", Font.PLAIN, 12), UiTheme.MUTED);
            text.add(nameLabel);
            text.add(Box.createVerticalStrut(2));
            text.add(infoLabel);

            row.add(avatar, BorderLayout.WEST);
            row.add(text, BorderLayout.CENTER);
            return row;
        }
    }
}
