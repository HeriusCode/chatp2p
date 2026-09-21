package chatp2p.client;

import chatp2p.view.ConnectionFrame;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Scanner;
import javax.swing.SwingUtilities;

/** Starts the Swing client by default, or the console client when arguments are supplied. */
public final class ClientMain {
    private ClientMain() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            launchGraphicalClient();
            return;
        }
        if (args.length != 3) {
            System.err.println("Usage: ClientMain [<server-host> <server-port> <client-name>]");
            System.exit(2);
            return;
        }

        runConsoleClient(args);
    }

    private static void launchGraphicalClient() {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("A graphical environment is required. "
                    + "For console mode, provide: <server-host> <server-port> <client-name>");
            System.exit(2);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            ConnectionFrame.installLookAndFeel();
            new ConnectionFrame().setVisible(true);
        });
    }

    private static void runConsoleClient(String[] args) {
        try (ChatClient client = new ChatClient(); Scanner scanner = new Scanner(System.in)) {
            ConnectionOptions options = readOptions(args);
            client.connect(options.host(), options.port(), options.clientName());
            System.out.println("Connected as " + client.clientName() + ". Commands: ping, status, quit");

            while (client.state() == ConnectionState.CONNECTED && scanner.hasNextLine()) {
                String command = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
                switch (command) {
                    case "ping" -> {
                        Duration roundTrip = client.ping();
                        System.out.println("PONG in " + roundTrip.toMillis() + " ms");
                    }
                    case "status" -> System.out.println("State: " + client.state());
                    case "quit", "exit" -> {
                        return;
                    }
                    case "" -> { }
                    default -> System.out.println("Unknown command. Use: ping, status, quit");
                }
            }
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("Client error: " + failure.getMessage());
            System.exit(1);
        }
    }

    private static ConnectionOptions readOptions(String[] args) {
        return new ConnectionOptions(args[0], parsePort(args[1]), args[2]);
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Server port must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException invalidPort) {
            throw new IllegalArgumentException("Server port must be a number", invalidPort);
        }
    }

    private record ConnectionOptions(String host, int port, String clientName) {
    }
}
