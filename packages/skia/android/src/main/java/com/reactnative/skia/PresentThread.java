package com.reactnative.skia;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * The thread every SkiaView presents on, one for the process: it owns each
 * view's window surface (attach, resize, detach), runs its FrameScheduler on a
 * Choreographer of its own, and replays and presents its recordings. The main
 * thread only relays the backing views' callbacks to it, so that no present
 * takes the window's frames from the main thread. One thread for every view
 * keeps their submissions to the GPU in one order, which the Graphite context
 * serializes anyway.
 */
final class PresentThread {
    private static final Handler HANDLER = createHandler();

    private static final String DIAG_TAG = "RNSkDiag";
    private static final long DIAG_PERIOD_MS = 500;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Set<SkiaView> DIAG_VIEWS = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean sDiagStarted = false;
    private static volatile long sPresentBeats = 0;
    private static volatile long sMainBeats = 0;

    private static final Runnable DIAG_PRESENT_BEAT = new Runnable() {
        @Override
        public void run() {
            sPresentBeats++;
            SkiaView[] views;
            synchronized (DIAG_VIEWS) {
                views = DIAG_VIEWS.toArray(new SkiaView[0]);
            }
            Log.i(DIAG_TAG, "present beat " + sPresentBeats + " mainBeats=" + sMainBeats + " views=" + views.length);
            for (SkiaView view : views) {
                Log.i(DIAG_TAG, "  " + view.describeDiag());
            }
            HANDLER.postDelayed(this, DIAG_PERIOD_MS);
        }
    };

    private static final Runnable DIAG_MAIN_BEAT = new Runnable() {
        @Override
        public void run() {
            sMainBeats++;
            Log.i(DIAG_TAG, "main beat " + sMainBeats + " presentBeats=" + sPresentBeats);
            MAIN_HANDLER.postDelayed(this, DIAG_PERIOD_MS);
        }
    };

    /** Main thread: logs the view's frame state twice a second from the present thread. */
    static void registerDiag(SkiaView view) {
        synchronized (DIAG_VIEWS) {
            DIAG_VIEWS.add(view);
        }
        if (!sDiagStarted) {
            sDiagStarted = true;
            HANDLER.postDelayed(DIAG_PRESENT_BEAT, DIAG_PERIOD_MS);
            MAIN_HANDLER.postDelayed(DIAG_MAIN_BEAT, DIAG_PERIOD_MS);
        }
    }

    static void unregisterDiag(SkiaView view) {
        synchronized (DIAG_VIEWS) {
            DIAG_VIEWS.remove(view);
        }
    }

    private PresentThread() {}

    private static Handler createHandler() {
        HandlerThread thread = new HandlerThread("RNSkia Present", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        return new Handler(thread.getLooper());
    }

    /** Whether the calling thread is the present thread. */
    static boolean isCurrent() {
        return Looper.myLooper() == HANDLER.getLooper();
    }

    /** Runs the task on the present thread, after the ones posted before it. */
    static void post(Runnable task) {
        HANDLER.post(task);
    }

    /** Removes a task posted with post() that has not run yet. */
    static void remove(Runnable task) {
        HANDLER.removeCallbacks(task);
    }

    /**
     * Runs the task on the present thread and returns once it has run, after
     * the ones posted before it. A backing view's surface must not be touched
     * once its destroyed callback returns, so that callback waits here.
     */
    static void runAndWait(Runnable task) {
        if (isCurrent()) {
            task.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        HANDLER.post(() -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        });
        boolean interrupted = false;
        while (true) {
            try {
                done.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
