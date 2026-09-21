package chatp2p.model;

/** Snapshot used by Swing to render progress without touching network threads. */
public record TransferProgress(
        FileInfo file,
        TransferDirection direction,
        TransferState state,
        long transferredBytes,
        double bytesPerSecond,
        long estimatedSeconds,
        String detail) {

    public int percent() {
        if (file.size() == 0) {
            return state == TransferState.SUCCESS ? 100 : 0;
        }
        return (int) Math.min(100, transferredBytes * 100 / file.size());
    }
}
