package chatp2p.client;

import chatp2p.model.FileInfo;
import java.util.UUID;

/** Allows the UI to cancel an active outgoing file transfer. */
public interface TransferHandle {
    UUID fileId();

    FileInfo fileInfo();

    void cancel();

    boolean isCancelled();
}
