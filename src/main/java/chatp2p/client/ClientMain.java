package chatp2p.client;

import chatp2p.view.LoginFrame;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Scanner;
import javax.swing.SwingUtilities;

/** Starts the Swing client by default, or the console client when arguments are supplied. */
public final class ClientMain {
    private static URLClassLoader cameraClassLoader;

    private ClientMain() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            if (!cameraLibrariesAvailable() && launchWithBundledLibraries(args)) {
                return;
            }
            launchGraphicalClient();
            return;
        }
        if (args.length != 4) {
            System.err.println("Usage: ClientMain [<server-host> <server-port> <username> <password>]");
            System.exit(2);
            return;
        }

        runConsoleClient(args);
    }

    private static boolean cameraLibrariesAvailable() {
        try {
            Class.forName("com.github.sarxos.webcam.Webcam");
            Class.forName("org.jcodec.api.awt.AWTSequenceEncoder");
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    /** Keeps the IDE's plain target/classes launch working after scripts/build.ps1. */
    private static boolean launchWithBundledLibraries(String[] args) {
        try {
            Path classes = Path.of(ClientMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dependencies = classes.getParent().resolve("dependency");
            String[] jars = {
                "sqlite-jdbc-3.54.1.0.jar", "webcam-capture-0.3.12.jar",
                "bridj-0.7.0.jar", "slf4j-api-1.7.36.jar", "slf4j-nop-1.7.36.jar",
                "jcodec-0.2.5.jar", "jcodec-javase-0.2.5.jar"
            };
            URL[] urls = new URL[jars.length + 1];
            urls[0] = classes.toUri().toURL();
            for (int index = 0; index < jars.length; index++) {
                Path jar = dependencies.resolve(jars[index]);
                if (!Files.isRegularFile(jar)) {
                    return false;
                }
                urls[index + 1] = jar.toUri().toURL();
            }
            cameraClassLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
            Thread.currentThread().setContextClassLoader(cameraClassLoader);
            Class<?> entry = Class.forName(ClientMain.class.getName(), true, cameraClassLoader);
            entry.getMethod("main", String[].class).invoke(null, (Object) args);
            return true;
        } catch (Exception failure) {
            System.err.println("Cannot load camera libraries: " + failure.getMessage());
            return false;
        }
    }

    private static void launchGraphicalClient() {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("A graphical environment is required. "
                    + "For console mode, provide: <server-host> <server-port> <username> <password>");
            System.exit(2);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            LoginFrame.installLookAndFeel();
            new LoginFrame().setVisible(true);
        });
    }

    private static void runConsoleClient(String[] args) {
        try (ChatClient client = new ChatClient(); Scanner scanner = new Scanner(System.in)) {
            ConnectionOptions options = readOptions(args);
            client.connect(options.host(), options.port(), options.clientName(), options.password());
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
        return new ConnectionOptions(args[0], parsePort(args[1]), args[2], args[3]);
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

    private record ConnectionOptions(String host, int port, String clientName, String password) {
    }
}
