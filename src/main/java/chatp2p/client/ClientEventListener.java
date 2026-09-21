package chatp2p.client;

import chatp2p.model.ChatMessage;
import chatp2p.model.FileInfo;
import chatp2p.model.PeerInfo;
import chatp2p.model.TransferProgress;

import java.nio.file.Path;
import java.util.List;

/** Events raised by background control and peer network threads. */
public interface ClientEventListener {
    default void onUsersChanged(List<PeerInfo> users) {
    }

    default void onChatReceived(ChatMessage message) {
    }

    default void onConnectionError(String message) {
    }

    default boolean onFileOffered(FileInfo file) {
        return false;
    }

    default void onTransferProgress(TransferProgress progress) {
    }

    default void onTransferCompleted(FileInfo file, Path savedPath) {
    }

    default void onTransferFailed(FileInfo file, String reason) {
    }
}
