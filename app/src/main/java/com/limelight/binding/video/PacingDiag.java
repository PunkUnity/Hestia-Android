package com.limelight.binding.video;

import com.limelight.LimeLog;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Diagnostic counters for frame pacing. This class only observes: it never
 * changes when or whether a frame is released.
 *
 * It counts frames at each stage (submitted to the decoder, returned by the
 * decoder, released or dropped by each path) and checks the order in which
 * frames are released. One summary line is logged every few seconds; nothing
 * is logged per frame.
 *
 * A release call is not proof that the frame reached the screen. Compare these
 * counts with SurfaceFlinger presentation timestamps for the same window.
 */
final class PacingDiag {
    /** Released by the non-blocking "latest frame only" block in the render thread. */
    static final int PATH_LATEST = 0;
    /** Released by the Choreographer callback from the Balanced queue. */
    static final int PATH_CHOREO = 1;
    /** Released directly by the render thread (non-Balanced pacing modes). */
    static final int PATH_DIRECT = 2;

    private static final String[] PATH_NAMES = {"LATEST", "CHOREO", "DIRECT"};
    private static final long REPORT_INTERVAL_NS = 5_000_000_000L;
    private static final int MAX_EXAMPLES = 4;
    private static final int PTS_SLOTS = 64;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong output = new AtomicLong();
    private final AtomicLong latestDrop = new AtomicLong();
    private final AtomicLong drainDrop = new AtomicLong();
    private final AtomicLong lateDrop = new AtomicLong();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong queueDrop = new AtomicLong();
    private final AtomicLong choreoCallbacks = new AtomicLong();
    private final AtomicLong choreoEmpty = new AtomicLong();
    private final AtomicLong resets = new AtomicLong();

    // PTS of each buffer waiting in the Balanced queue, by output buffer index.
    private final AtomicLongArray ptsByIndex = new AtomicLongArray(PTS_SLOTS);

    // Guarded by "this": release bookkeeping shared by the render and Choreographer threads.
    private final long[] released = new long[PATH_NAMES.length];
    private long outOfOrder;
    private long releasedWhileQueued;
    private long lastPtsUs = -1;
    private int lastPath = -1;
    private final StringBuilder examples = new StringBuilder();
    private int exampleCount;

    // Previous totals, for per-window deltas. Only touched by maybeReport().
    private final long[] prev = new long[16];
    private long windowStartNs = System.nanoTime();
    private long sessionStartNs = windowStartNs;

    void submitted() { submitted.incrementAndGet(); }
    void output() { output.incrementAndGet(); }
    void latestDrop() { latestDrop.incrementAndGet(); }
    void drainDrop() { drainDrop.incrementAndGet(); }
    void lateDrop() { lateDrop.incrementAndGet(); }
    void queueDrop() { queueDrop.incrementAndGet(); }
    void choreoCallback() { choreoCallbacks.incrementAndGet(); }
    void choreoEmpty() { choreoEmpty.incrementAndGet(); }
    void resetEvent() { resets.incrementAndGet(); }

    /** A frame was put in the Balanced queue for the Choreographer to release. */
    void queued(int bufferIndex, long ptsUs) {
        queued.incrementAndGet();
        if (bufferIndex >= 0 && bufferIndex < PTS_SLOTS) {
            ptsByIndex.set(bufferIndex, ptsUs);
        }
    }

    /** PTS recorded for a queued buffer, or -1 if unknown. */
    long ptsOf(int bufferIndex) {
        if (bufferIndex >= 0 && bufferIndex < PTS_SLOTS) {
            return ptsByIndex.get(bufferIndex);
        }
        return -1;
    }

    /**
     * A frame was released for display.
     *
     * @param path        one of the PATH_ constants
     * @param ptsUs       the frame's presentation timestamp, or -1 if unknown
     * @param queueDepth  frames still waiting in the Balanced queue at this moment
     */
    synchronized void released(int path, long ptsUs, int queueDepth) {
        released[path]++;
        if (path != PATH_CHOREO && queueDepth > 0) {
            // A frame was shown while older frames were still waiting in the queue.
            releasedWhileQueued++;
        }
        if (ptsUs >= 0) {
            if (lastPtsUs >= 0 && ptsUs < lastPtsUs) {
                outOfOrder++;
                if (exampleCount < MAX_EXAMPLES) {
                    examples.append(' ').append(lastPtsUs).append("us(").append(PATH_NAMES[lastPath])
                            .append(")->").append(ptsUs).append("us(").append(PATH_NAMES[path]).append(')');
                    exampleCount++;
                }
            }
            lastPtsUs = ptsUs;
            lastPath = path;
        }
    }

    /** Log the session's configuration once, when the render thread starts. */
    void session(String config) {
        sessionStartNs = System.nanoTime();
        windowStartNs = sessionStartNs;
        LimeLog.info("PacingDiag session: " + config);
    }

    /** Log one summary line if the reporting interval has passed. Render thread only. */
    void maybeReport() {
        long now = System.nanoTime();
        if (now - windowStartNs < REPORT_INTERVAL_NS) {
            return;
        }
        report(now);
    }

    /** Log a final summary. Safe to call from the render thread as it exits. */
    void finalReport() {
        report(System.nanoTime());
    }

    private void report(long now) {
        long[] cur = new long[16];
        String ex;
        synchronized (this) {
            cur[0] = submitted.get();
            cur[1] = output.get();
            cur[2] = released[PATH_LATEST];
            cur[3] = latestDrop.get();
            cur[4] = queued.get();
            cur[5] = queueDrop.get();
            cur[6] = released[PATH_CHOREO];
            cur[7] = choreoEmpty.get();
            cur[8] = choreoCallbacks.get();
            cur[9] = released[PATH_DIRECT];
            cur[10] = drainDrop.get();
            cur[11] = lateDrop.get();
            cur[12] = releasedWhileQueued;
            cur[13] = outOfOrder;
            cur[14] = resets.get();
            ex = examples.toString();
            examples.setLength(0);
            exampleCount = 0;
        }

        String[] names = {"submitted", "output", "latestRel", "latestDrop", "queued", "queueDrop",
                "choreoRel", "choreoEmpty", "choreoCallbacks", "directRel", "drainDrop", "lateDrop",
                "relWhileQueued", "outOfOrder", "resets"};
        StringBuilder sb = new StringBuilder(320);
        sb.append("PacingDiag t=").append((now - sessionStartNs) / 1_000_000L).append("ms window=")
                .append((now - windowStartNs) / 1_000_000L).append("ms");
        for (int i = 0; i < names.length; i++) {
            sb.append(' ').append(names[i]).append('=').append(cur[i] - prev[i])
                    .append('/').append(cur[i]);
            prev[i] = cur[i];
        }
        if (!ex.isEmpty()) {
            sb.append(" outOfOrderExamples:").append(ex);
        }
        windowStartNs = now;
        LimeLog.info(sb.toString());
    }
}
