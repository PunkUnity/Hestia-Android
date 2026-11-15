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
 *
 * Success/failure accounting covers every release path: a helper release that
 * failed both attempts is counted as releaseFailed, not as a release.
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

    /**
     * Collects durations (in microseconds) from one writer thread and reports
     * percentiles per window. Bounded: at most CAPACITY samples per window.
     */
    static final class Stat {
        private static final int CAPACITY = 4096;
        private final long[] values = new long[CAPACITY];
        private int count;
        private long max;
        private long dropped;

        synchronized void add(long us) {
            if (us < 0) return;
            if (us > max) max = us;
            if (count < CAPACITY) values[count++] = us; else dropped++;
        }

        /** "p50/p90/p99/max ms (n)" for this window, then reset. */
        synchronized String takeSummary() {
            if (count == 0) return "-";
            long[] v = java.util.Arrays.copyOf(values, count);
            java.util.Arrays.sort(v);
            String s = ms(v[(int) (0.50 * (count - 1))]) + "/" + ms(v[(int) (0.90 * (count - 1))]) + "/"
                    + ms(v[(int) (0.99 * (count - 1))]) + "/" + ms(max) + "ms(n=" + (count + dropped) + ")";
            count = 0; max = 0; dropped = 0;
            return s;
        }

        private static String ms(long us) {
            return String.format(java.util.Locale.US, "%.1f", us / 1000.0);
        }
    }

    // Timing, per window. Each Stat has a single writer thread (noted).
    private final Stat submitGap = new Stat();    // between submitDecodeUnit calls (input thread)
    private final Stat outputGap = new Stat();    // between decoder outputs (render thread)
    private final Stat dequeueTime = new Stat();  // timed dequeue that returned a frame (render thread)
    private final Stat queueAge = new Stat();     // enqueue -> taken by Choreographer (Choreographer thread)
    private final Stat choreoLate = new Stat();   // callback entry minus vsync frame time, which the
                                                  // renderer has already moved earlier by the app vsync
                                                  // offset, so this includes that offset (Choreographer thread)
    private final AtomicLong[] depthAtEnqueue = {new AtomicLong(), new AtomicLong(), new AtomicLong()};
    private final AtomicLongArray enqueueNsByIndex = new AtomicLongArray(PTS_SLOTS);
    private long lastSubmitNs;   // input thread only
    private long lastOutputNs;   // render thread only

    // Individual long gaps, logged as they happen (rate-limited), to place hitches in a stage.
    private static final long LONG_GAP_US = 50_000;
    private static final int MAX_GAP_EVENTS_PER_WINDOW = 6;
    private final java.util.concurrent.atomic.AtomicInteger gapEvents = new java.util.concurrent.atomic.AtomicInteger();

    private void longGap(String stage, long us) {
        if (us >= LONG_GAP_US && gapEvents.incrementAndGet() <= MAX_GAP_EVENTS_PER_WINDOW) {
            LimeLog.info("PacingDiag gap: stage=" + stage + " gapMs=" + (us / 1000)
                    + " t=" + ((System.nanoTime() - sessionStartNs) / 1_000_000L) + "ms");
        }
    }

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
    private final AtomicLong releaseFailed = new AtomicLong();

    // PTS of each buffer waiting in the Balanced queue, by output buffer index.
    // -1 means unknown. A slot is cleared when its buffer is taken from the queue,
    // so a reused index can't report a stale timestamp.
    private final AtomicLongArray ptsByIndex = new AtomicLongArray(PTS_SLOTS);

    // Decoder output order. Written by the render thread, and by whichever thread
    // performs a codec reset while the others are quiesced.
    private volatile long lastOutputPtsUs = -1;
    private final AtomicLong outputReversal = new AtomicLong();

    PacingDiag() {
        for (int i = 0; i < PTS_SLOTS; i++) {
            ptsByIndex.set(i, -1);
        }
    }

    // Guarded by "this": release bookkeeping shared by the render and Choreographer threads.
    private final long[] released = new long[PATH_NAMES.length];
    private long outOfOrder;
    private long releasedWhileQueued;
    private long unknownPts;
    private long lastPtsUs = -1;
    private int lastPath = -1;
    private final StringBuilder examples = new StringBuilder();
    private int exampleCount;

    // Previous totals, for per-window deltas. Only touched by maybeReport().
    private final long[] prev = new long[20];
    private long windowStartNs = System.nanoTime();
    private long sessionStartNs = windowStartNs;

    /** A frame was submitted to the decoder. Input thread only. */
    void submitted() {
        submitted.incrementAndGet();
        long now = System.nanoTime();
        if (lastSubmitNs != 0) {
            long us = (now - lastSubmitNs) / 1000;
            submitGap.add(us);
            longGap("submit", us);
        }
        lastSubmitNs = now;
    }

    /** Duration of a timed dequeue that returned a frame. Render thread only. */
    void dequeueTook(long ns) { dequeueTime.add(ns / 1000); }
    /** The decoder returned an output buffer. Render thread only. */
    void output(long ptsUs) {
        output.incrementAndGet();
        long now = System.nanoTime();
        if (lastOutputNs != 0) {
            long us = (now - lastOutputNs) / 1000;
            outputGap.add(us);
            longGap("output", us);
        }
        lastOutputNs = now;
        if (ptsUs >= 0) {
            if (lastOutputPtsUs >= 0 && ptsUs < lastOutputPtsUs) {
                // The decoder itself returned frames out of timestamp order.
                outputReversal.incrementAndGet();
            }
            lastOutputPtsUs = ptsUs;
        }
    }
    void releaseFailed() { releaseFailed.incrementAndGet(); }
    void latestDrop() { latestDrop.incrementAndGet(); }
    void drainDrop() { drainDrop.incrementAndGet(); }
    void lateDrop() { lateDrop.incrementAndGet(); }
    void queueDrop() { queueDrop.incrementAndGet(); }
    /** A Choreographer callback ran for the vsync at frameTimeNanos. Choreographer thread only. */
    void choreoCallback(long frameTimeNanos) {
        choreoCallbacks.incrementAndGet();
        choreoLate.add((System.nanoTime() - frameTimeNanos) / 1000);
    }
    void choreoEmpty() { choreoEmpty.incrementAndGet(); }
    /**
     * The decoder was flushed, restarted or reset and the Balanced queue was cleared.
     * Called with all codec threads quiesced. Forget buffer indices and ordering
     * state from before the reset so they can't be compared with frames after it.
     */
    void resetEvent() {
        resets.incrementAndGet();
        for (int i = 0; i < PTS_SLOTS; i++) {
            ptsByIndex.set(i, -1);
            enqueueNsByIndex.set(i, 0);
        }
        lastOutputPtsUs = -1;
        synchronized (this) {
            lastPtsUs = -1;
            lastPath = -1;
        }
    }

    /** A frame was put in the Balanced queue for the Choreographer to release. */
    void queued(int bufferIndex, long ptsUs, int depthBefore) {
        queued.incrementAndGet();
        depthAtEnqueue[Math.max(0, Math.min(2, depthBefore))].incrementAndGet();
        if (bufferIndex >= 0 && bufferIndex < PTS_SLOTS) {
            ptsByIndex.set(bufferIndex, ptsUs);
            enqueueNsByIndex.set(bufferIndex, System.nanoTime());
        }
    }

    /** A queued buffer was discarded because the queue was full. Clears its slot. */
    void dropQueued(int bufferIndex) {
        if (bufferIndex >= 0 && bufferIndex < PTS_SLOTS) {
            ptsByIndex.set(bufferIndex, -1);
            enqueueNsByIndex.set(bufferIndex, 0);
        }
    }

    /**
     * Take the PTS recorded for a queued buffer and clear its slot, or -1 if unknown.
     * Call this when the buffer leaves the queue, BEFORE releasing it to the codec:
     * after release the codec may hand the same index out again.
     */
    long takePts(int bufferIndex) {
        if (bufferIndex >= 0 && bufferIndex < PTS_SLOTS) {
            long enqNs = enqueueNsByIndex.getAndSet(bufferIndex, 0);
            if (enqNs != 0) {
                queueAge.add((System.nanoTime() - enqNs) / 1000);
            }
            return ptsByIndex.getAndSet(bufferIndex, -1);
        }
        return -1;
    }

    /**
     * A frame was released for display. Call only after the release call succeeded.
     * Note: this bookkeeping runs after the release, under a lock, on whichever
     * thread released the frame. It records the order in which releases were
     * counted, which can differ slightly from the order the codec received them.
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
        } else {
            // No PTS for this frame (index out of range, or never recorded), so it
            // took no part in the order check.
            unknownPts++;
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
        long[] cur = new long[20];
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
            cur[15] = outputReversal.get();
            cur[16] = releaseFailed.get();
            cur[17] = unknownPts;
            ex = examples.toString();
            examples.setLength(0);
            exampleCount = 0;
        }

        String[] names = {"submitted", "output", "latestRel", "latestDrop", "queued", "queueDrop",
                "choreoRel", "choreoEmpty", "choreoCallbacks", "directRel", "drainDrop", "lateDrop",
                "relWhileQueued", "outOfOrder", "resets", "outputReversal", "releaseFailed", "unknownPts"};
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
        LimeLog.info("PacingDiag timing: submitGap=" + submitGap.takeSummary()
                + " outputGap=" + outputGap.takeSummary()
                + " dequeue=" + dequeueTime.takeSummary()
                + " queueAge=" + queueAge.takeSummary()
                + " choreoLate=" + choreoLate.takeSummary()
                + " depthAtEnqueue=" + depthAtEnqueue[0].getAndSet(0) + "/" + depthAtEnqueue[1].getAndSet(0)
                + "/" + depthAtEnqueue[2].getAndSet(0)
                + " gapEvents=" + gapEvents.getAndSet(0));
    }
}
