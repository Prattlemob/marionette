package com.prattlemob.marionette.observation;

import java.util.function.Consumer;

import com.google.gson.JsonPrimitive;
import com.prattlemob.marionette.bridge.AgentConnection;
import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.ErrorCode;
import com.prattlemob.marionette.bridge.protocol.Messages;

/**
 * Runs at most one block scan at a time on the client thread: applies the caps
 * when a request arrives, reads a budgeted portion each tick, and sends exactly
 * one {@code scan_result} or error per request. Minecraft-free: the caller
 * passes the level as an identity token. See protocol/v1.md, scan.
 */
public final class BlockScanRunner {
    private record Active(BlockScan scan, Consumer<String> reply, JsonPrimitive id, String raw,
                          Object level, String dimension) {}

    private Active active;

    /**
     * Apply a scan request. {@code feet} is the player's feet block, or null
     * without a live player; {@code level} identifies the client level.
     */
    public void start(AgentCommand.Scan request, Consumer<String> reply, int[] feet, int radius,
                      Object level, String dimension) {
        if (feet == null || level == null) {
            reply.accept(error(ErrorCode.SCAN_REFUSED, "no_world", "no world is loaded", request, null));
            return;
        }
        if (active != null) {
            reply.accept(error(ErrorCode.SCAN_REFUSED, "busy", "another scan is in progress", request, null));
            return;
        }
        int[] size = {request.size().x(), request.size().y(), request.size().z()};
        int[] min = request.min() == null ? BlockScan.centred(size, feet)
                : new int[] {request.min().x(), request.min().y(), request.min().z()};
        String refusal = BlockScan.refusal(min, size, feet, radius);
        if (refusal != null) {
            String message = refusal.equals("over_volume")
                    ? "scan box exceeds " + BlockScan.MAX_VOLUME + " blocks"
                    : "scan box exceeds the configured radius " + radius + " around the player";
            reply.accept(error(ErrorCode.SCAN_REFUSED, refusal, message, request,
                    new int[] {radius, BlockScan.MAX_VOLUME}));
            return;
        }
        active = new Active(new BlockScan(min, size), reply, request.id(), request.raw(), level, dimension);
    }

    /**
     * Read the next portion of the active scan, at most {@code budget} positions,
     * and send its result when complete. A changed {@code level} cancels it.
     * @return positions read this call (0 when idle or cancelled)
     */
    public int tick(long tick, int budget, Object level, BlockScan.BlockSource source) {
        Active scan = active;
        if (scan == null) return 0;
        if (level != scan.level()) {
            cancel("level_changed", "the client level changed");
            return 0;
        }
        int read = scan.scan().advance(source, budget, tick);
        if (scan.scan().done()) {
            active = null;
            String result = scan.scan().result(scan.id(), scan.dimension());
            scan.reply().accept(AgentConnection.fitsFrame(result) ? result
                    : Messages.scanError(ErrorCode.SCAN_CANCELLED, "too_large",
                            "scan result exceeds the reply size limit", scan.id(), scan.raw(), null));
        }
        return read;
    }

    /** Stop the active scan, if any, with a {@code scan_cancelled} reason. */
    public void cancel(String reason, String message) {
        Active scan = active;
        if (scan == null) return;
        active = null;
        scan.reply().accept(Messages.scanError(ErrorCode.SCAN_CANCELLED, reason, message,
                scan.id(), scan.raw(), null));
    }

    public boolean active() {
        return active != null;
    }

    private static String error(ErrorCode code, String reason, String message, AgentCommand.Scan request,
                                int[] limits) {
        return Messages.scanError(code, reason, message, request.id(), request.raw(), limits);
    }
}
