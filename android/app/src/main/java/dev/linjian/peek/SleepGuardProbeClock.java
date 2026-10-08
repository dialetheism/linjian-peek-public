package dev.linjian.peek;

import java.util.LinkedHashSet;
import java.util.Set;

/** Pure-Java time and cursor rules for the local sleep-guard feasibility probe. */
public final class SleepGuardProbeClock {
    private SleepGuardProbeClock() { }

    public static long nonNegativeAgeSeconds(long nowMs, long eventMs) {
        if (nowMs <= 0 || eventMs <= 0 || nowMs < eventMs) return 0L;
        return (nowMs - eventMs) / 1000L;
    }

    public static long safeElapsedAgeSeconds(long nowWallMs, long nowElapsedMs,
                                             long eventWallMs, long eventElapsedMs) {
        if (eventElapsedMs > 0 && nowElapsedMs >= eventElapsedMs) {
            return (nowElapsedMs - eventElapsedMs) / 1000L;
        }
        return nonNegativeAgeSeconds(nowWallMs, eventWallMs);
    }

    public static long observationDelayMs(long observedAtMs, long eventAtMs) {
        if (observedAtMs <= 0 || eventAtMs <= 0 || observedAtMs < eventAtMs) return 0L;
        return observedAtMs - eventAtMs;
    }

    public static final class Cursor {
        private long timestampMs;
        private final LinkedHashSet<String> packagesAtTimestamp = new LinkedHashSet<>();

        public Cursor(long timestampMs, String encodedPackages) {
            this.timestampMs = Math.max(0L, timestampMs);
            decodePackages(encodedPackages);
        }

        public synchronized boolean accept(boolean enabled, long eventTimestampMs, String packageName) {
            if (!enabled || eventTimestampMs <= 0) return false;
            String pkg = normalizePackage(packageName);
            if (eventTimestampMs < timestampMs) return false;
            if (eventTimestampMs > timestampMs) {
                timestampMs = eventTimestampMs;
                packagesAtTimestamp.clear();
                packagesAtTimestamp.add(pkg);
                return true;
            }
            return packagesAtTimestamp.add(pkg);
        }

        public synchronized void rebaseAfterWallClockRollback(long nowMs, long toleranceMs) {
            long safeNow = Math.max(0L, nowMs);
            long tolerance = Math.max(0L, toleranceMs);
            if (timestampMs > safeNow + tolerance) clear(safeNow);
        }

        public synchronized long queryStartMs(long nowMs, long overlapMs) {
            long safeNow = Math.max(0L, nowMs);
            long anchor = timestampMs <= 0 ? safeNow : Math.min(timestampMs, safeNow);
            return Math.max(0L, anchor - Math.max(0L, overlapMs));
        }

        public synchronized void clear(long newTimestampMs) {
            timestampMs = Math.max(0L, newTimestampMs);
            packagesAtTimestamp.clear();
        }

        public synchronized long timestampMs() { return timestampMs; }

        public synchronized String encodedPackages() {
            StringBuilder out = new StringBuilder();
            for (String pkg : packagesAtTimestamp) {
                if (out.length() > 0) out.append('\n');
                out.append(pkg);
            }
            return out.toString();
        }

        private void decodePackages(String encoded) {
            if (encoded == null || encoded.length() == 0) return;
            for (String raw : encoded.split("\\n")) {
                String pkg = normalizePackage(raw);
                if (pkg.length() > 0) packagesAtTimestamp.add(pkg);
            }
        }

        private static String normalizePackage(String packageName) {
            if (packageName == null || packageName.trim().length() == 0) return "<unknown>";
            return packageName.trim();
        }

        synchronized Set<String> packagesForTest() {
            return new LinkedHashSet<>(packagesAtTimestamp);
        }
    }
}
