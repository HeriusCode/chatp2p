package chatp2p.server;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Creates the server logger without a third-party logging dependency. */
public final class ServerLogger {
    private static volatile Logger sharedLogger;

    private ServerLogger() {
    }

    public static synchronized Logger create() throws IOException {
        if (sharedLogger != null) {
            return sharedLogger;
        }
        Logger logger = Logger.getLogger("chatp2p.server");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);

        Formatter formatter = new Formatter() {
            private final DateTimeFormatter timeFormat =
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            @Override
            public String format(LogRecord record) {
                return "[" + LocalDateTime.now().format(timeFormat) + "] "
                        + record.getLevel() + " " + formatMessage(record) + System.lineSeparator();
            }
        };

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(formatter);
        logger.addHandler(consoleHandler);
        sharedLogger = logger;
        return logger;
    }
}
