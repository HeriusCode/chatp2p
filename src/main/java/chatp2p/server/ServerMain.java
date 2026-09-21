package chatp2p.server;

import chatp2p.view.ConnectionFrame;
import chatp2p.view.ServerDashboardFrame;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import javax.swing.SwingUtilities;

/** Command-line entry point for the central server. */
public final class ServerMain {
    private ServerMain() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            launchGraphicalServer();
            return;
        }

        int port;
        try {
            port = parsePort(args);
        } catch (IllegalArgumentException invalidArgument) {
            System.err.println(invalidArgument.getMessage());
            System.err.println("Usage: ServerMain [port]");
            System.exit(2);
            return;
        }

        try {
            ChatServer server = new ChatServer(port);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "server-shutdown"));
            server.start();
        } catch (IOException failure) {
            System.err.println("Cannot start server: " + failure.getMessage());
            System.exit(1);
        }
    }

    private static void launchGraphicalServer() {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("A graphical environment is required. For console mode, provide a port.");
            System.exit(2);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            ConnectionFrame.installLookAndFeel();
            new ServerDashboardFrame().setVisible(true);
        });
    }

    private static int parsePort(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("Too many arguments");
        }
        int port = Integer.parseInt(args[0]);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return port;
    }
}
