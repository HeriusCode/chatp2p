package chatp2p.client;

import chatp2p.model.ChatMessage;
import chatp2p.model.FileInfo;
import chatp2p.model.GroupInfo;
import chatp2p.model.GroupMessage;
import chatp2p.model.PeerInfo;
import chatp2p.model.TransferProgress;

import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.util.List;

/** Events raised by background control and peer network threads. */
public interface ClientEventListener {
    default void onUsersChanged(List<PeerInfo> users) {
    }

    default void onChatReceived(ChatMessage message) {
    }

    default void onGroupsChanged(List<GroupInfo> groups) {
    }

    default void onGroupChatReceived(GroupMessage message) {
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

    default void onCallChanged(CallSession call) {
    }

    default void onCallVideoFrame(CallSession call, BufferedImage frame, boolean local) {
    }
}
