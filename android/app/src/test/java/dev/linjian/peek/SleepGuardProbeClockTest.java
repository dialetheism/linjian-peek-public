package dev.linjian.peek;

/** Dependency-free JVM checks; run with javac/java, without Gradle or Android. */
public final class SleepGuardProbeClockTest {
    public static void main(String[] args) {
        firstEventIsAccepted();
        duplicateEventIsRejected();
        sameMillisecondDifferentPackagesAreAccepted();
        overlappingQueriesDoNotRecount();
        wallClockRollbackNeverProducesNegativeAge();
        elapsedRealtimeRollbackFallsBackSafely();
        clearResetsCursorState();
        disabledProbeDoesNotAdvanceCursor();
        androidEventConstantsMatchContract();
        selfPackageIsSeparated();
        directTypesAreClassified();
        ambientTypesAreClassified();
        untrackedTypeIsIgnored();
        unknownPackagesAreIgnored();
        eventStatsClearResetsEverything();
        disabledEventStatsDoNotUpdate();
        latestEventOnlyAcceptsNewerTime();
        highFrequencyAmbientEventsStayInMemory();
        persistenceGateRearmsAfterFlushOrClear();
        System.out.println("SleepGuardProbeClockTest PASS");
    }

    private static void firstEventIsAccepted() {
        SleepGuardProbeClock.Cursor cursor = new SleepGuardProbeClock.Cursor(0L, "");
        check(cursor.accept(true, 1_000L, "pkg.one"), "first event must be accepted");
        check(cursor.timestampMs() == 1_000L, "cursor must advance to first event");
    }

    private static void duplicateEventIsRejected() {
        SleepGuardProbeClock.Cursor cursor = new SleepGuardProbeClock.Cursor(1_000L, "pkg.one");
        check(!cursor.accept(true, 1_000L, "pkg.one"), "exact duplicate must be rejected");
    }

    private static void sameMillisecondDifferentPackagesAreAccepted() {
        SleepGuardProbeClock.Cursor cursor = new SleepGuardProbeClock.Cursor(1_000L, "pkg.one");
        check(cursor.accept(true, 1_000L, "pkg.two"), "same time with another package must be accepted");
        check(cursor.packagesForTest().size() == 2, "both packages must remain in cursor state");
    }

    private static void overlappingQueriesDoNotRecount() {
        SleepGuardProbeClock.Cursor cursor = new SleepGuardProbeClock.Cursor(2_000L, "pkg.new");
        check(cursor.queryStartMs(2_500L, 500L) == 1_500L, "query must include overlap");
        check(!cursor.accept(true, 1_900L, "pkg.old"), "older overlap event must not recount");
        check(!cursor.accept(true, 2_000L, "pkg.new"), "cursor event must not recount");
        check(cursor.accept(true, 2_100L, "pkg.next"), "newer event must be accepted");
    }

    private static void wallClockRollbackNeverProducesNegativeAge() {
        check(SleepGuardProbeClock.nonNegativeAgeSeconds(5_000L, 8_000L) == 0L,
                "wall-clock rollback must clamp age to zero");
        check(SleepGuardProbeClock.observationDelayMs(5_000L, 8_000L) == 0L,
                "wall-clock rollback must clamp delay to zero");
    }

    private static void elapsedRealtimeRollbackFallsBackSafely() {
        long age = SleepGuardProbeClock.safeElapsedAgeSeconds(20_000L, 500L, 15_000L, 9_000L);
        check(age == 5L, "elapsedRealtime reboot must fall back to wall clock");
    }

    private static void clearResetsCursorState() {
        SleepGuardProbeClock.Cursor cursor = new SleepGuardProbeClock.Cursor(1_000L, "pkg.one\npkg.two");
        cursor.clear(3_000L);
        check(cursor.timestampMs() == 3_000L, "clear must set requested baseline");
        check(cursor.packagesForTest().isEmpty(), "clear must remove dedupe packages");
    }

    private static void disabledProbeDoesNotAdvanceCursor() {
        SleepGuardProbeClock.Cursor cursor = new SleepGuardProbeClock.Cursor(4_000L, "pkg.one");
        check(!cursor.accept(false, 5_000L, "pkg.two"), "disabled probe must reject event");
        check(cursor.timestampMs() == 4_000L, "disabled probe must not change cursor");
    }

    private static void androidEventConstantsMatchContract() {
        check(SleepGuardEventStats.TYPE_VIEW_CLICKED == 0x00000001,
                "TYPE_VIEW_CLICKED must match Android constant 0x00000001");
        check(SleepGuardEventStats.TYPE_VIEW_LONG_CLICKED == 0x00000002,
                "TYPE_VIEW_LONG_CLICKED must match Android constant 0x00000002");
        check(SleepGuardEventStats.TYPE_VIEW_SELECTED == 0x00000004,
                "TYPE_VIEW_SELECTED must match Android constant 0x00000004");
        check(SleepGuardEventStats.TYPE_VIEW_FOCUSED == 0x00000008,
                "TYPE_VIEW_FOCUSED must match Android constant 0x00000008");
        check(SleepGuardEventStats.TYPE_VIEW_TEXT_CHANGED == 0x00000010,
                "TYPE_VIEW_TEXT_CHANGED must match Android constant 0x00000010");
        check(SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED == 0x00000020,
                "TYPE_WINDOW_STATE_CHANGED must match Android constant 0x00000020");
        check(SleepGuardEventStats.TYPE_WINDOW_CONTENT_CHANGED == 0x00000800,
                "TYPE_WINDOW_CONTENT_CHANGED must match Android constant 0x00000800");
        check(SleepGuardEventStats.TYPE_VIEW_SCROLLED == 0x00001000,
                "TYPE_VIEW_SCROLLED must match Android constant 0x00001000");
        check(SleepGuardEventStats.TYPE_WINDOWS_CHANGED == 0x00400000,
                "TYPE_WINDOWS_CHANGED must match Android constant 0x00400000");
    }

    private static void selfPackageIsSeparated() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        check(stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "dev.linjian.peek", 1_000L, 900L), "tracked self event must be handled");
        SleepGuardEventStats.Snapshot snapshot = stats.snapshot();
        check(snapshot.selfPackageCount == 1L, "self event must use separate counter");
        check(snapshot.directTotal() == 0L, "self event must not pollute external direct count");
        check(snapshot.lastNonSelfWallMs == 0L, "self event must not become latest external event");
    }

    private static void directTypesAreClassified() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        int[] types = {
                SleepGuardEventStats.TYPE_VIEW_CLICKED,
                SleepGuardEventStats.TYPE_VIEW_LONG_CLICKED,
                SleepGuardEventStats.TYPE_VIEW_SCROLLED,
                SleepGuardEventStats.TYPE_VIEW_TEXT_CHANGED,
                SleepGuardEventStats.TYPE_VIEW_SELECTED,
                SleepGuardEventStats.TYPE_VIEW_FOCUSED
        };
        long wall = 1_000L;
        for (int type : types) {
            check(SleepGuardEventStats.isDirectType(type), "direct type must be classified");
            check(stats.record(true, "dev.linjian.peek", type, "example.game", wall++, wall),
                    "direct type must be recorded");
        }
        SleepGuardEventStats.Snapshot snapshot = stats.snapshot();
        check(snapshot.directTotal() == types.length, "all direct types must be counted");
        for (int type : types) {
            check(snapshot.countForType(type) == 1L, "each direct type must have its own count");
        }
    }

    private static void ambientTypesAreClassified() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        int[] types = {
                SleepGuardEventStats.TYPE_WINDOW_CONTENT_CHANGED,
                SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED,
                SleepGuardEventStats.TYPE_WINDOWS_CHANGED
        };
        long wall = 2_000L;
        for (int type : types) {
            check(SleepGuardEventStats.isAmbientType(type), "ambient type must be classified");
            check(stats.record(true, "dev.linjian.peek", type, "example.game", wall++, wall),
                    "ambient type must be recorded");
        }
        SleepGuardEventStats.Snapshot snapshot = stats.snapshot();
        check(snapshot.ambientTotal() == types.length, "all ambient types must be counted");
        for (int type : types) {
            check(snapshot.countForType(type) == 1L, "each ambient type must have its own count");
        }
    }

    private static void untrackedTypeIsIgnored() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        check(!stats.record(true, "dev.linjian.peek", 0x00000040,
                "example.game", 1_000L, 900L), "untracked event must be ignored");
        SleepGuardEventStats.Snapshot snapshot = stats.snapshot();
        check(snapshot.directTotal() == 0L, "untracked event must not change direct count");
        check(snapshot.ambientTotal() == 0L, "untracked event must not change ambient count");
        check(snapshot.selfPackageCount == 0L, "untracked event must not change self count");
    }

    private static void unknownPackagesAreIgnored() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        check(stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "com.example.game", 1_000L, 900L), "valid external event must be recorded");
        SleepGuardEventStats.Snapshot before = stats.snapshot();

        String[] unknownPackages = { null, "", "   ", "\t", " \r\n " };
        int[] eventTypes = {
                SleepGuardEventStats.TYPE_VIEW_LONG_CLICKED,
                SleepGuardEventStats.TYPE_WINDOW_CONTENT_CHANGED,
                SleepGuardEventStats.TYPE_VIEW_SCROLLED,
                SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED,
                SleepGuardEventStats.TYPE_WINDOWS_CHANGED
        };
        for (int i = 0; i < unknownPackages.length; i++) {
            check(!stats.record(true, "dev.linjian.peek", eventTypes[i], unknownPackages[i],
                    2_000L + i, 1_900L + i),
                    "unknown package event must report no state change at index " + i);
            checkEventSnapshotsEqual(before, stats.snapshot(),
                    "unknown package event must preserve all state at index " + i);
        }

        check(stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_SCROLLED,
                "  com.example.game  ", 3_000L, 2_900L),
                "trimmed valid package must be recorded");
        SleepGuardEventStats.Snapshot afterTrimmed = stats.snapshot();
        check("com.example.game".equals(afterTrimmed.lastNonSelfPackage),
                "valid package must be trimmed before storage");
        check(afterTrimmed.directTotal() == before.directTotal() + 1L,
                "trimmed valid package must increase the direct count");
    }

    private static void eventStatsClearResetsEverything() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "example.game", 1_000L, 900L);
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_WINDOWS_CHANGED,
                "dev.linjian.peek", 1_100L, 1_000L);
        stats.clear();
        SleepGuardEventStats.Snapshot snapshot = stats.snapshot();
        check(snapshot.directTotal() == 0L, "clear must reset direct counts");
        check(snapshot.ambientTotal() == 0L, "clear must reset ambient counts");
        check(snapshot.selfPackageCount == 0L, "clear must reset self count");
        check(snapshot.lastNonSelfType == 0, "clear must reset latest type");
        check(snapshot.lastNonSelfPackage.isEmpty(), "clear must reset latest package");
        check(snapshot.lastNonSelfWallMs == 0L, "clear must reset latest wall time");
        check(snapshot.lastNonSelfElapsedMs == 0L, "clear must reset latest elapsed time");
    }

    private static void disabledEventStatsDoNotUpdate() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        check(!stats.record(false, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "example.game", 1_000L, 900L), "disabled event probe must reject event");
        check(stats.snapshot().directTotal() == 0L, "disabled event probe must stay unchanged");
    }

    private static void latestEventOnlyAcceptsNewerTime() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "newer.package", 2_000L, 1_900L);
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_SCROLLED,
                "older.package", 1_000L, 900L);
        SleepGuardEventStats.Snapshot snapshot = stats.snapshot();
        check(snapshot.lastNonSelfType == SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "older event must not replace latest type");
        check("newer.package".equals(snapshot.lastNonSelfPackage),
                "older event must not replace latest package");
        check(snapshot.lastNonSelfWallMs == 2_000L, "older event must not replace latest time");
        check(snapshot.lastNonSelfElapsedMs == 1_900L,
                "older event must not replace latest elapsed time");
    }

    private static void highFrequencyAmbientEventsStayInMemory() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        boolean flushScheduled = false;
        int persistenceSchedules = 0;
        for (int i = 0; i < 1_000; i++) {
            stats.record(true, "dev.linjian.peek",
                    SleepGuardEventStats.TYPE_WINDOW_CONTENT_CHANGED,
                    "example.animated", 1_000L + i, 900L + i);
            if (SleepGuardProbeClock.shouldSchedulePersistence(true, flushScheduled)) {
                flushScheduled = true;
                persistenceSchedules++;
            }
        }
        check(stats.snapshot().countForType(
                SleepGuardEventStats.TYPE_WINDOW_CONTENT_CHANGED) == 1_000L,
                "high-frequency ambient events must accumulate without a persistence dependency");
        check(persistenceSchedules == 1,
                "high-frequency ambient events must not schedule one persistence per event");
    }

    private static void persistenceGateRearmsAfterFlushOrClear() {
        boolean flushScheduled = false;
        check(SleepGuardProbeClock.shouldSchedulePersistence(true, flushScheduled),
                "first dirty event must schedule persistence");

        flushScheduled = true;
        check(!SleepGuardProbeClock.shouldSchedulePersistence(true, flushScheduled),
                "events while a callback is pending must not schedule another callback");
        check(!SleepGuardProbeClock.shouldSchedulePersistence(true, flushScheduled),
                "repeated events must continue sharing the pending callback");

        // Simulate persist completion or clear/stop resetting the scheduling gate.
        flushScheduled = false;
        check(SleepGuardProbeClock.shouldSchedulePersistence(true, flushScheduled),
                "first event after flush or clear must rearm persistence");

        flushScheduled = true;
        check(!SleepGuardProbeClock.shouldSchedulePersistence(true, flushScheduled),
                "event after rearm must not create a duplicate callback");
    }

    private static void checkEventSnapshotsEqual(SleepGuardEventStats.Snapshot expected,
                                                 SleepGuardEventStats.Snapshot actual,
                                                 String message) {
        int[] allTypes = {
                SleepGuardEventStats.TYPE_VIEW_CLICKED,
                SleepGuardEventStats.TYPE_VIEW_LONG_CLICKED,
                SleepGuardEventStats.TYPE_VIEW_SCROLLED,
                SleepGuardEventStats.TYPE_VIEW_TEXT_CHANGED,
                SleepGuardEventStats.TYPE_VIEW_SELECTED,
                SleepGuardEventStats.TYPE_VIEW_FOCUSED,
                SleepGuardEventStats.TYPE_WINDOW_CONTENT_CHANGED,
                SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED,
                SleepGuardEventStats.TYPE_WINDOWS_CHANGED
        };
        check(expected.directTotal() == actual.directTotal(), message + ": direct total");
        check(expected.ambientTotal() == actual.ambientTotal(), message + ": ambient total");
        for (int type : allTypes) {
            check(expected.countForType(type) == actual.countForType(type),
                    message + ": per-type count " + SleepGuardEventStats.typeName(type));
        }
        check(expected.selfPackageCount == actual.selfPackageCount, message + ": self count");
        check(expected.lastNonSelfType == actual.lastNonSelfType, message + ": latest type");
        check(expected.lastNonSelfPackage.equals(actual.lastNonSelfPackage),
                message + ": latest package");
        check(expected.lastNonSelfWallMs == actual.lastNonSelfWallMs,
                message + ": latest wall time");
        check(expected.lastNonSelfElapsedMs == actual.lastNonSelfElapsedMs,
                message + ": latest elapsed time");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
