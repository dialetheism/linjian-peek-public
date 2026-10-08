package dev.linjian.peek;

final class SleepGuardEventStats {
    static final int TYPE_VIEW_CLICKED = 0x00000001;
    static final int TYPE_VIEW_LONG_CLICKED = 0x00000002;
    static final int TYPE_VIEW_SELECTED = 0x00000004;
    static final int TYPE_VIEW_FOCUSED = 0x00000008;
    static final int TYPE_VIEW_TEXT_CHANGED = 0x00000010;
    static final int TYPE_WINDOW_STATE_CHANGED = 0x00000020;
    static final int TYPE_WINDOW_CONTENT_CHANGED = 0x00000800;
    static final int TYPE_VIEW_SCROLLED = 0x00001000;
    static final int TYPE_WINDOWS_CHANGED = 0x00400000;

    private static final int INDEX_VIEW_CLICKED = 0;
    private static final int INDEX_VIEW_LONG_CLICKED = 1;
    private static final int INDEX_VIEW_SCROLLED = 2;
    private static final int INDEX_VIEW_TEXT_CHANGED = 3;
    private static final int INDEX_VIEW_SELECTED = 4;
    private static final int INDEX_VIEW_FOCUSED = 5;
    private static final int INDEX_WINDOW_CONTENT_CHANGED = 6;
    private static final int INDEX_WINDOW_STATE_CHANGED = 7;
    private static final int INDEX_WINDOWS_CHANGED = 8;
    private static final int TYPE_COUNT = 9;

    private final long[] counts = new long[TYPE_COUNT];
    private long selfPackageCount;
    private int lastNonSelfType;
    private String lastNonSelfPackage = "";
    private long lastNonSelfWallMs;
    private long lastNonSelfElapsedMs;

    boolean record(boolean enabled, String selfPackage, int eventType, String eventPackage,
                   long wallClockMs, long elapsedRealtimeMs) {
        int index = indexForType(eventType);
        if (!enabled || index < 0) return false;

        String normalizedEventPackage = normalizePackage(eventPackage);
        if (normalizedEventPackage.isEmpty()) return false;
        String normalizedSelfPackage = normalizePackage(selfPackage);
        if (!normalizedSelfPackage.isEmpty()
                && normalizedSelfPackage.equals(normalizedEventPackage)) {
            selfPackageCount++;
            return true;
        }

        counts[index]++;
        if (wallClockMs > lastNonSelfWallMs) {
            lastNonSelfType = eventType;
            lastNonSelfPackage = normalizedEventPackage;
            lastNonSelfWallMs = wallClockMs;
            lastNonSelfElapsedMs = Math.max(0L, elapsedRealtimeMs);
        }
        return true;
    }

    void clear() {
        for (int i = 0; i < counts.length; i++) counts[i] = 0L;
        selfPackageCount = 0L;
        lastNonSelfType = 0;
        lastNonSelfPackage = "";
        lastNonSelfWallMs = 0L;
        lastNonSelfElapsedMs = 0L;
    }

    void restoreCount(int eventType, long count) {
        int index = indexForType(eventType);
        if (index >= 0) counts[index] = Math.max(0L, count);
    }

    void restoreSelfPackageCount(long count) {
        selfPackageCount = Math.max(0L, count);
    }

    void restoreLastNonSelf(int eventType, String packageName, long wallClockMs,
                            long elapsedRealtimeMs) {
        if (indexForType(eventType) < 0 || wallClockMs <= 0L) return;
        lastNonSelfType = eventType;
        lastNonSelfPackage = normalizePackage(packageName);
        lastNonSelfWallMs = wallClockMs;
        lastNonSelfElapsedMs = Math.max(0L, elapsedRealtimeMs);
    }

    Snapshot snapshot() {
        return new Snapshot(counts.clone(), selfPackageCount, lastNonSelfType,
                lastNonSelfPackage, lastNonSelfWallMs, lastNonSelfElapsedMs);
    }

    static boolean isDirectType(int eventType) {
        int index = indexForType(eventType);
        return index >= INDEX_VIEW_CLICKED && index <= INDEX_VIEW_FOCUSED;
    }

    static boolean isAmbientType(int eventType) {
        int index = indexForType(eventType);
        return index >= INDEX_WINDOW_CONTENT_CHANGED && index <= INDEX_WINDOWS_CHANGED;
    }

    static String typeName(int eventType) {
        switch (eventType) {
            case TYPE_VIEW_CLICKED: return "TYPE_VIEW_CLICKED";
            case TYPE_VIEW_LONG_CLICKED: return "TYPE_VIEW_LONG_CLICKED";
            case TYPE_VIEW_SCROLLED: return "TYPE_VIEW_SCROLLED";
            case TYPE_VIEW_TEXT_CHANGED: return "TYPE_VIEW_TEXT_CHANGED";
            case TYPE_VIEW_SELECTED: return "TYPE_VIEW_SELECTED";
            case TYPE_VIEW_FOCUSED: return "TYPE_VIEW_FOCUSED";
            case TYPE_WINDOW_CONTENT_CHANGED: return "TYPE_WINDOW_CONTENT_CHANGED";
            case TYPE_WINDOW_STATE_CHANGED: return "TYPE_WINDOW_STATE_CHANGED";
            case TYPE_WINDOWS_CHANGED: return "TYPE_WINDOWS_CHANGED";
            default: return "-";
        }
    }

    private static int indexForType(int eventType) {
        switch (eventType) {
            case TYPE_VIEW_CLICKED: return INDEX_VIEW_CLICKED;
            case TYPE_VIEW_LONG_CLICKED: return INDEX_VIEW_LONG_CLICKED;
            case TYPE_VIEW_SCROLLED: return INDEX_VIEW_SCROLLED;
            case TYPE_VIEW_TEXT_CHANGED: return INDEX_VIEW_TEXT_CHANGED;
            case TYPE_VIEW_SELECTED: return INDEX_VIEW_SELECTED;
            case TYPE_VIEW_FOCUSED: return INDEX_VIEW_FOCUSED;
            case TYPE_WINDOW_CONTENT_CHANGED: return INDEX_WINDOW_CONTENT_CHANGED;
            case TYPE_WINDOW_STATE_CHANGED: return INDEX_WINDOW_STATE_CHANGED;
            case TYPE_WINDOWS_CHANGED: return INDEX_WINDOWS_CHANGED;
            default: return -1;
        }
    }

    private static String normalizePackage(String packageName) {
        return packageName == null ? "" : packageName.trim();
    }

    static final class Snapshot {
        private final long[] counts;
        final long selfPackageCount;
        final int lastNonSelfType;
        final String lastNonSelfPackage;
        final long lastNonSelfWallMs;
        final long lastNonSelfElapsedMs;

        Snapshot(long[] counts, long selfPackageCount, int lastNonSelfType,
                 String lastNonSelfPackage, long lastNonSelfWallMs,
                 long lastNonSelfElapsedMs) {
            this.counts = counts;
            this.selfPackageCount = selfPackageCount;
            this.lastNonSelfType = lastNonSelfType;
            this.lastNonSelfPackage = lastNonSelfPackage;
            this.lastNonSelfWallMs = lastNonSelfWallMs;
            this.lastNonSelfElapsedMs = lastNonSelfElapsedMs;
        }

        long countForType(int eventType) {
            int index = indexForType(eventType);
            return index < 0 ? 0L : counts[index];
        }

        long directTotal() {
            long total = 0L;
            for (int i = INDEX_VIEW_CLICKED; i <= INDEX_VIEW_FOCUSED; i++) total += counts[i];
            return total;
        }

        long ambientTotal() {
            long total = 0L;
            for (int i = INDEX_WINDOW_CONTENT_CHANGED; i <= INDEX_WINDOWS_CHANGED; i++) {
                total += counts[i];
            }
            return total;
        }
    }
}
