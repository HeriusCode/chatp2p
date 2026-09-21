package chatp2p.client;

import java.util.UUID;

/** Allows the UI to cancel an active outgoing file transfer. */
public interface TransferHandle {
    UUID fileId();

    void cancel();

    boolean isCancelled();
}
