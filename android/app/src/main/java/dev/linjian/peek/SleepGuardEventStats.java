package dev.linjian.peek;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    static final int MAX_EXTERNAL_PACKAGES = 8;
    static final int MAX_ENCODED_BREAKDOWN_LENGTH = 8192;
    static final String BREAKDOWN_FORMAT_VERSION = "SGPB1";

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
    private static final int MAX_PACKAGE_NAME_LENGTH = 255;
    private static final int ENCODED_ENTRY_FIELD_COUNT = 5 + TYPE_COUNT;

    private final long[] counts = new long[TYPE_COUNT];
    private final Map<String, MutablePackageStats> packageBreakdown = new HashMap<>();
    private long activitySequence;
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
            selfPackageCount = incrementSaturated(selfPackageCount);
            return true;
        }

        counts[index] = incrementSaturated(counts[index]);
        if (wallClockMs > lastNonSelfWallMs) {
            lastNonSelfType = eventType;
            lastNonSelfPackage = normalizedEventPackage;
            lastNonSelfWallMs = wallClockMs;
            lastNonSelfElapsedMs = Math.max(0L, elapsedRealtimeMs);
        }

        MutablePackageStats perPackage = packageBreakdown.get(normalizedEventPackage);
        if (perPackage == null) {
            if (packageBreakdown.size() >= MAX_EXTERNAL_PACKAGES) evictOldestPackage();
            perPackage = new MutablePackageStats(normalizedEventPackage);
            packageBreakdown.put(normalizedEventPackage, perPackage);
        }
        perPackage.counts[index] = incrementSaturated(perPackage.counts[index]);
        perPackage.lastEventType = eventType;
        perPackage.lastEventWallMs = Math.max(0L, wallClockMs);
        perPackage.lastEventElapsedMs = Math.max(0L, elapsedRealtimeMs);
        perPackage.activitySequence = nextActivitySequence();
        return true;
    }

    void clear() {
        for (int i = 0; i < counts.length; i++) counts[i] = 0L;
        selfPackageCount = 0L;
        lastNonSelfType = 0;
        lastNonSelfPackage = "";
        lastNonSelfWallMs = 0L;
        lastNonSelfElapsedMs = 0L;
        clearPackageBreakdown();
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
        String normalizedPackage = normalizePackage(packageName);
        if (indexForType(eventType) < 0 || normalizedPackage.isEmpty() || wallClockMs <= 0L) {
            return;
        }
        lastNonSelfType = eventType;
        lastNonSelfPackage = normalizedPackage;
        lastNonSelfWallMs = wallClockMs;
        lastNonSelfElapsedMs = Math.max(0L, elapsedRealtimeMs);
    }

    String encodePackageBreakdown() {
        List<PackageSnapshot> packages = packageSnapshotsMostRecentFirst();
        StringBuilder out = new StringBuilder();
        out.append(BREAKDOWN_FORMAT_VERSION).append('|').append(packages.size());
        for (PackageSnapshot entry : packages) {
            out.append('\n').append(entry.packageName)
                    .append('|').append(entry.lastEventType)
                    .append('|').append(entry.lastEventWallMs)
                    .append('|').append(entry.lastEventElapsedMs)
                    .append('|').append(entry.activitySequence);
            for (long count : entry.counts) out.append('|').append(count);
            if (out.length() > MAX_ENCODED_BREAKDOWN_LENGTH) return "";
        }
        return out.toString();
    }

    boolean restorePackageBreakdown(String encoded) {
        clearPackageBreakdown();
        if (encoded == null || encoded.isEmpty()) return true;
        if (encoded.length() > MAX_ENCODED_BREAKDOWN_LENGTH) return false;

        try {
            String[] lines = encoded.split("\\n", -1);
            String[] header = lines[0].split("\\|", -1);
            if (header.length != 2 || !BREAKDOWN_FORMAT_VERSION.equals(header[0])) return false;
            int entryCount = Integer.parseInt(header[1]);
            if (entryCount < 0 || entryCount > MAX_EXTERNAL_PACKAGES
                    || lines.length != entryCount + 1) return false;

            Map<String, MutablePackageStats> decoded = new HashMap<>();
            Set<String> seenPackages = new HashSet<>();
            long maxSequence = 0L;
            for (int i = 0; i < entryCount; i++) {
                String[] fields = lines[i + 1].split("\\|", -1);
                if (fields.length != ENCODED_ENTRY_FIELD_COUNT) return false;
                String packageName = normalizePackage(fields[0]);
                if (packageName.isEmpty() || !packageName.equals(fields[0])
                        || !seenPackages.add(packageName)) return false;

                int lastType = Integer.parseInt(fields[1]);
                long lastWall = Long.parseLong(fields[2]);
                long lastElapsed = Long.parseLong(fields[3]);
                long sequence = Long.parseLong(fields[4]);
                if (indexForType(lastType) < 0 || lastWall <= 0L
                        || lastElapsed < 0L || sequence <= 0L) return false;

                MutablePackageStats entry = new MutablePackageStats(packageName);
                entry.lastEventType = lastType;
                entry.lastEventWallMs = lastWall;
                entry.lastEventElapsedMs = lastElapsed;
                entry.activitySequence = sequence;
                long total = 0L;
                for (int countIndex = 0; countIndex < TYPE_COUNT; countIndex++) {
                    long count = Long.parseLong(fields[5 + countIndex]);
                    if (count < 0L) return false;
                    entry.counts[countIndex] = count;
                    total = addSaturated(total, count);
                }
                if (total <= 0L || entry.counts[indexForType(lastType)] <= 0L) return false;
                decoded.put(packageName, entry);
                maxSequence = Math.max(maxSequence, sequence);
            }

            packageBreakdown.putAll(decoded);
            activitySequence = maxSequence;
            return true;
        } catch (RuntimeException ignored) {
            clearPackageBreakdown();
            return false;
        }
    }

    Snapshot snapshot() {
        return new Snapshot(counts.clone(), selfPackageCount, lastNonSelfType,
                lastNonSelfPackage, lastNonSelfWallMs, lastNonSelfElapsedMs,
                packageSnapshotsMostRecentFirst());
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

    private void clearPackageBreakdown() {
        packageBreakdown.clear();
        activitySequence = 0L;
    }

    private long nextActivitySequence() {
        if (activitySequence == Long.MAX_VALUE) renumberActivitySequences();
        activitySequence++;
        return activitySequence;
    }

    private void renumberActivitySequences() {
        List<MutablePackageStats> entries = new ArrayList<>(packageBreakdown.values());
        Collections.sort(entries, new Comparator<MutablePackageStats>() {
            @Override public int compare(MutablePackageStats left, MutablePackageStats right) {
                int bySequence = Long.compare(left.activitySequence, right.activitySequence);
                if (bySequence != 0) return bySequence;
                return right.packageName.compareTo(left.packageName);
            }
        });
        long sequence = 0L;
        for (MutablePackageStats entry : entries) entry.activitySequence = ++sequence;
        activitySequence = sequence;
    }

    private void evictOldestPackage() {
        MutablePackageStats oldest = null;
        for (MutablePackageStats candidate : packageBreakdown.values()) {
            if (oldest == null
                    || candidate.activitySequence < oldest.activitySequence
                    || (candidate.activitySequence == oldest.activitySequence
                    && candidate.packageName.compareTo(oldest.packageName) > 0)) {
                oldest = candidate;
            }
        }
        if (oldest != null) packageBreakdown.remove(oldest.packageName);
    }

    private List<PackageSnapshot> packageSnapshotsMostRecentFirst() {
        List<PackageSnapshot> snapshots = new ArrayList<>(packageBreakdown.size());
        for (MutablePackageStats entry : packageBreakdown.values()) snapshots.add(entry.snapshot());
        Collections.sort(snapshots, new Comparator<PackageSnapshot>() {
            @Override public int compare(PackageSnapshot left, PackageSnapshot right) {
                int bySequence = Long.compare(right.activitySequence, left.activitySequence);
                if (bySequence != 0) return bySequence;
                return left.packageName.compareTo(right.packageName);
            }
        });
        return Collections.unmodifiableList(snapshots);
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
        if (packageName == null) return "";
        String value = packageName.trim();
        if (value.isEmpty() || value.length() > MAX_PACKAGE_NAME_LENGTH) return "";
        boolean segmentEmpty = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (segmentEmpty) return "";
                segmentEmpty = true;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_') {
                segmentEmpty = false;
            } else {
                return "";
            }
        }
        return !segmentEmpty ? value : "";
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static long addSaturated(long left, long right) {
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    private static final class MutablePackageStats {
        final String packageName;
        final long[] counts = new long[TYPE_COUNT];
        int lastEventType;
        long lastEventWallMs;
        long lastEventElapsedMs;
        long activitySequence;

        MutablePackageStats(String packageName) {
            this.packageName = packageName;
        }

        PackageSnapshot snapshot() {
            return new PackageSnapshot(packageName, lastEventType, lastEventWallMs,
                    lastEventElapsedMs, activitySequence, counts.clone());
        }
    }

    static final class PackageSnapshot {
        final String packageName;
        final int lastEventType;
        final long lastEventWallMs;
        final long lastEventElapsedMs;
        final long activitySequence;
        private final long[] counts;

        PackageSnapshot(String packageName, int lastEventType, long lastEventWallMs,
                        long lastEventElapsedMs, long activitySequence, long[] counts) {
            this.packageName = packageName;
            this.lastEventType = lastEventType;
            this.lastEventWallMs = lastEventWallMs;
            this.lastEventElapsedMs = lastEventElapsedMs;
            this.activitySequence = activitySequence;
            this.counts = counts;
        }

        long countForType(int eventType) {
            int index = indexForType(eventType);
            return index < 0 ? 0L : counts[index];
        }

        long directTotal() {
            long total = 0L;
            for (int i = INDEX_VIEW_CLICKED; i <= INDEX_VIEW_FOCUSED; i++) {
                total = addSaturated(total, counts[i]);
            }
            return total;
        }

        long ambientTotal() {
            long total = 0L;
            for (int i = INDEX_WINDOW_CONTENT_CHANGED; i <= INDEX_WINDOWS_CHANGED; i++) {
                total = addSaturated(total, counts[i]);
            }
            return total;
        }
    }

    static final class Snapshot {
        private final long[] counts;
        final long selfPackageCount;
        final int lastNonSelfType;
        final String lastNonSelfPackage;
        final long lastNonSelfWallMs;
        final long lastNonSelfElapsedMs;
        final List<PackageSnapshot> packages;

        Snapshot(long[] counts, long selfPackageCount, int lastNonSelfType,
                 String lastNonSelfPackage, long lastNonSelfWallMs,
                 long lastNonSelfElapsedMs, List<PackageSnapshot> packages) {
            this.counts = counts;
            this.selfPackageCount = selfPackageCount;
            this.lastNonSelfType = lastNonSelfType;
            this.lastNonSelfPackage = lastNonSelfPackage;
            this.lastNonSelfWallMs = lastNonSelfWallMs;
            this.lastNonSelfElapsedMs = lastNonSelfElapsedMs;
            this.packages = packages;
        }

        long countForType(int eventType) {
            int index = indexForType(eventType);
            return index < 0 ? 0L : counts[index];
        }

        long directTotal() {
            long total = 0L;
            for (int i = INDEX_VIEW_CLICKED; i <= INDEX_VIEW_FOCUSED; i++) {
                total = addSaturated(total, counts[i]);
            }
            return total;
        }

        long ambientTotal() {
            long total = 0L;
            for (int i = INDEX_WINDOW_CONTENT_CHANGED; i <= INDEX_WINDOWS_CHANGED; i++) {
                total = addSaturated(total, counts[i]);
            }
            return total;
        }
    }
}
