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
        packageBreakdownSeparatesGamesAndSystemUi();
        stoppedProbeDoesNotRecordScreenshot();
        perPackageEventTypeCountsAreCorrect();
        packageNamesAreNormalizedAndSelfIsExcluded();
        recentPackageOrderIsDeterministic();
        ninthPackageEvictsOnlyOldest();
        reactivatedPackageSurvivesEviction();
        packageBreakdownCodecRoundTrip();
        packageBreakdownCodecRejectsCorruption();
        restoredMaxSequenceAndCountsStaySafe();
        packageSnapshotCollectionIsImmutableAndStable();
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
        check(snapshot.packages.isEmpty(), "self event must not create an external package row");
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
        check(snapshot.packages.isEmpty(), "clear must reset package breakdown");
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

    private static void packageBreakdownSeparatesGamesAndSystemUi() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "com.tencent.tmgp.lv", 1_000L, 900L);
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_SCROLLED,
                "com.tencent.tmgp.lv", 1_100L, 1_000L);
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_FOCUSED,
                "com.netease.skzdhlr", 1_200L, 1_100L);
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED,
                "com.android.systemui", 1_300L, 1_200L);

        SleepGuardEventStats.Snapshot snapshot = stats.snapshot();
        SleepGuardEventStats.PackageSnapshot light = packageSnapshot(snapshot, "com.tencent.tmgp.lv");
        SleepGuardEventStats.PackageSnapshot space = packageSnapshot(snapshot, "com.netease.skzdhlr");
        SleepGuardEventStats.PackageSnapshot systemUi = packageSnapshot(snapshot, "com.android.systemui");
        check(snapshot.packages.size() == 3, "games and SystemUI must have separate rows");
        check(light.directTotal() == 2L, "first game must retain its own direct count");
        check(space.directTotal() == 1L, "second game must retain its own direct count");
        check(systemUi.ambientTotal() == 1L, "SystemUI must retain its own ambient count");

        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "com.android.systemui", 1_400L, 1_300L);
        SleepGuardEventStats.Snapshot afterSystemUi = stats.snapshot();
        check(packageSnapshot(afterSystemUi, "com.tencent.tmgp.lv").directTotal() == 2L,
                "later SystemUI event must not overwrite first game row");
        check(packageSnapshot(afterSystemUi, "com.netease.skzdhlr").directTotal() == 1L,
                "later SystemUI event must not overwrite second game row");
    }

    private static void stoppedProbeDoesNotRecordScreenshot() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "com.tencent.tmgp.lv", 2_000L, 1_900L);
        SleepGuardEventStats.Snapshot frozen = stats.snapshot();
        check(!stats.record(false, "dev.linjian.peek",
                        SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED,
                        "com.oplus.screenshot", 2_100L, 2_000L),
                "stopped probe must reject screenshot package event");
        checkEventSnapshotsEqual(frozen, stats.snapshot(),
                "stopped probe must preserve frozen package breakdown");
        check(findPackage(stats.snapshot(), "com.oplus.screenshot") == null,
                "screenshot package must not appear after probe is stopped");
    }

    private static void perPackageEventTypeCountsAreCorrect() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        int[] types = allEventTypes();
        long wall = 3_000L;
        for (int type : types) {
            stats.record(true, "dev.linjian.peek", type, "com.example.game", wall, wall - 100L);
            wall++;
        }
        SleepGuardEventStats.PackageSnapshot entry =
                packageSnapshot(stats.snapshot(), "com.example.game");
        check(entry.directTotal() == 6L, "package must count all six direct candidates");
        check(entry.ambientTotal() == 3L, "package must count all three ambient candidates");
        for (int type : types) {
            check(entry.countForType(type) == 1L,
                    "package must keep independent count for " + SleepGuardEventStats.typeName(type));
        }
    }

    private static void packageNamesAreNormalizedAndSelfIsExcluded() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "  com.example.game  ", 4_000L, 3_900L);
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_SCROLLED,
                "com.example.game", 4_100L, 4_000L);
        check(stats.snapshot().packages.size() == 1,
                "trimmed package names must merge into one row");
        check(packageSnapshot(stats.snapshot(), "com.example.game").directTotal() == 2L,
                "trimmed package row must contain both events");
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED,
                "android", 4_150L, 4_050L);
        check(findPackage(stats.snapshot(), "android") != null,
                "valid single-segment system package must be retained");

        stats.record(true, "  dev.linjian.peek  ", SleepGuardEventStats.TYPE_VIEW_FOCUSED,
                "  dev.linjian.peek  ", 4_200L, 4_100L);
        SleepGuardEventStats.Snapshot beforeUnknown = stats.snapshot();
        check(beforeUnknown.selfPackageCount == 1L, "trimmed self package must use self counter");
        check(beforeUnknown.packages.size() == 2,
                "trimmed self package must not enter external rows");

        String[] unknown = { null, "", "   ", "\t", " \r\n " };
        for (String packageName : unknown) {
            check(!stats.record(true, "dev.linjian.peek",
                            SleepGuardEventStats.TYPE_WINDOW_CONTENT_CHANGED,
                            packageName, 4_300L, 4_200L),
                    "unknown package must be ignored by package breakdown");
        }
        checkEventSnapshotsEqual(beforeUnknown, stats.snapshot(),
                "unknown packages must not create or change package rows");
    }

    private static void recentPackageOrderIsDeterministic() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "com.example.one", 5_000L, 4_900L);
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "com.example.two", 5_100L, 5_000L);
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_SCROLLED,
                "com.example.one", 5_200L, 5_100L);
        check("com.example.one".equals(stats.snapshot().packages.get(0).packageName),
                "reactivated package must move to the first row");
        check("com.example.two".equals(stats.snapshot().packages.get(1).packageName),
                "less recent package must move behind reactivated package");

        String tied = SleepGuardEventStats.BREAKDOWN_FORMAT_VERSION + "|2\n"
                + codecEntry("com.example.z", 6_000L, 5_900L, 7L) + "\n"
                + codecEntry("com.example.a", 6_000L, 5_900L, 7L);
        SleepGuardEventStats restored = new SleepGuardEventStats();
        check(restored.restorePackageBreakdown(tied), "valid tied codec must restore");
        check("com.example.a".equals(restored.snapshot().packages.get(0).packageName),
                "equal activity sequence must sort deterministically by package name");
        check("com.example.z".equals(restored.snapshot().packages.get(1).packageName),
                "deterministic tie order must be stable");
    }

    private static void ninthPackageEvictsOnlyOldest() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        for (int i = 0; i < SleepGuardEventStats.MAX_EXTERNAL_PACKAGES; i++) {
            stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                    "com.example.pkg" + i, 7_000L + i, 6_900L + i);
        }
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "com.example.pkg8", 7_100L, 7_000L);
        SleepGuardEventStats.Snapshot snapshot = stats.snapshot();
        check(snapshot.packages.size() == SleepGuardEventStats.MAX_EXTERNAL_PACKAGES,
                "package breakdown must stay capped at eight rows");
        check(findPackage(snapshot, "com.example.pkg0") == null,
                "ninth package must evict only the oldest row");
        for (int i = 1; i <= 8; i++) {
            check(findPackage(snapshot, "com.example.pkg" + i) != null,
                    "all non-oldest rows must remain after eviction");
        }
    }

    private static void reactivatedPackageSurvivesEviction() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        for (int i = 0; i < SleepGuardEventStats.MAX_EXTERNAL_PACKAGES; i++) {
            stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                    "com.example.pkg" + i, 8_000L + i, 7_900L + i);
        }
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_SCROLLED,
                "com.example.pkg0", 8_100L, 8_000L);
        stats.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "com.example.pkg8", 8_200L, 8_100L);
        SleepGuardEventStats.Snapshot snapshot = stats.snapshot();
        check(findPackage(snapshot, "com.example.pkg0") != null,
                "reactivated oldest package must survive next eviction");
        check(findPackage(snapshot, "com.example.pkg1") == null,
                "next least-recent package must be evicted after reactivation");
        check(packageSnapshot(snapshot, "com.example.pkg0").directTotal() == 2L,
                "reactivated package must retain prior counts");
    }

    private static void packageBreakdownCodecRoundTrip() {
        SleepGuardEventStats original = new SleepGuardEventStats();
        long wall = 9_000L;
        for (int type : allEventTypes()) {
            original.record(true, "dev.linjian.peek", type, "com.tencent.tmgp.lv",
                    wall, wall - 100L);
            wall++;
        }
        original.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_VIEW_CLICKED,
                "com.android.systemui", wall++, wall - 100L);
        original.record(true, "dev.linjian.peek", SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED,
                "com.oplus.screenshot", wall, wall - 100L);

        String encoded = original.encodePackageBreakdown();
        check(encoded.startsWith(SleepGuardEventStats.BREAKDOWN_FORMAT_VERSION + "|"),
                "codec must include format version");
        check(encoded.length() <= SleepGuardEventStats.MAX_ENCODED_BREAKDOWN_LENGTH,
                "codec output must obey length cap");
        SleepGuardEventStats restored = new SleepGuardEventStats();
        check(restored.restorePackageBreakdown(encoded), "valid codec must restore");
        checkPackageBreakdownsEqual(original.snapshot(), restored.snapshot(),
                "codec round-trip must preserve every package field and order");
        check(encoded.equals(restored.encodePackageBreakdown()),
                "codec round-trip must remain deterministic");
    }

    private static void packageBreakdownCodecRejectsCorruption() {
        String valid = rawCodecEntry("com.example.one", "1", "10000", "9900", "1",
                "1|0|0|0|0|0|0|0|0");
        String secondValid = rawCodecEntry("com.example.two", "1", "10100", "10000", "2",
                "1|0|0|0|0|0|0|0|0");

        checkCorruptCodecRejected("unknown version", "SGPB2|0");
        checkCorruptCodecRejected("header only with missing data", "SGPB1|1");
        checkCorruptCodecRejected("truncated entry", "SGPB1|1\ncom.example.one|bad");
        checkCorruptCodecRejected("extra field", "SGPB1|1\n" + valid + "|extra");
        checkCorruptCodecRejected("extra non-empty line",
                "SGPB1|1\n" + valid + "\n" + secondValid);
        checkCorruptCodecRejected("declared row count exceeds actual rows", "SGPB1|2\n" + valid);
        checkCorruptCodecRejected("trailing blank line", "SGPB1|1\n" + valid + "\n");
        checkCorruptCodecRejected("trailing delimiter", "SGPB1|1\n" + valid + "|");

        checkCorruptCodecRejected("negative wall time", "SGPB1|1\n" + rawCodecEntry(
                "com.example.one", "1", "-1", "9900", "1", "1|0|0|0|0|0|0|0|0"));
        checkCorruptCodecRejected("negative elapsed time", "SGPB1|1\n" + rawCodecEntry(
                "com.example.one", "1", "10000", "-1", "1", "1|0|0|0|0|0|0|0|0"));
        checkCorruptCodecRejected("negative activity sequence", "SGPB1|1\n" + rawCodecEntry(
                "com.example.one", "1", "10000", "9900", "-1", "1|0|0|0|0|0|0|0|0"));
        for (int countIndex = 0; countIndex < 9; countIndex++) {
            checkCorruptCodecRejected("negative count field " + countIndex,
                    "SGPB1|1\n" + rawCodecEntry("com.example.one", "1", "10000", "9900",
                            "1", rawCountsWithReplacement(countIndex, "-1")));
        }

        checkCorruptCodecRejected("untracked last event type", "SGPB1|1\n" + rawCodecEntry(
                "com.example.one", "64", "10000", "9900", "1", "1|0|0|0|0|0|0|0|0"));
        checkCorruptCodecRejected("last event type positive int overflow",
                "SGPB1|1\n" + rawCodecEntry("com.example.one", "2147483648", "10000", "9900",
                        "1", "1|0|0|0|0|0|0|0|0"));
        checkCorruptCodecRejected("last event type negative int overflow",
                "SGPB1|1\n" + rawCodecEntry("com.example.one", "-2147483649", "10000", "9900",
                        "1", "1|0|0|0|0|0|0|0|0"));
        checkCorruptCodecRejected("wall time positive long overflow", "SGPB1|1\n" + rawCodecEntry(
                "com.example.one", "1", "9223372036854775808", "9900", "1",
                "1|0|0|0|0|0|0|0|0"));
        checkCorruptCodecRejected("elapsed time positive long overflow", "SGPB1|1\n" + rawCodecEntry(
                "com.example.one", "1", "10000", "9223372036854775808", "1",
                "1|0|0|0|0|0|0|0|0"));
        checkCorruptCodecRejected("activity sequence positive long overflow",
                "SGPB1|1\n" + rawCodecEntry("com.example.one", "1", "10000", "9900",
                        "9223372036854775808", "1|0|0|0|0|0|0|0|0"));
        checkCorruptCodecRejected("count positive long overflow", "SGPB1|1\n" + rawCodecEntry(
                "com.example.one", "1", "10000", "9900", "1",
                "9223372036854775808|0|0|0|0|0|0|0|0"));

        checkCorruptCodecRejected("decimal numeric field", "SGPB1|1\n" + rawCodecEntry(
                "com.example.one", "1", "10000.5", "9900", "1", "1|0|0|0|0|0|0|0|0"));
        checkCorruptCodecRejected("alphabetic numeric field", "SGPB1|1\n" + rawCodecEntry(
                "com.example.one", "1", "10000", "99x00", "1", "1|0|0|0|0|0|0|0|0"));
        checkCorruptCodecRejected("whitespace-polluted numeric field",
                "SGPB1|1\n" + rawCodecEntry("com.example.one", "1", "10000", "9900", " 1",
                        "1|0|0|0|0|0|0|0|0"));
        checkCorruptCodecRejected("empty numeric field", "SGPB1|1\n" + rawCodecEntry(
                "com.example.one", "1", "10000", "", "1", "1|0|0|0|0|0|0|0|0"));

        String partialThenInvalid = "SGPB1|2\n" + valid + "\n" + rawCodecEntry(
                "com.example.two", "64", "10100", "10000", "2", "1|0|0|0|0|0|0|0|0");
        checkCorruptCodecRejected("valid first row followed by invalid second row", partialThenInvalid);
        checkCorruptCodecRejected("duplicate package", "SGPB1|2\n" + valid + "\n" + rawCodecEntry(
                "com.example.one", "1", "10100", "10000", "2", "1|0|0|0|0|0|0|0|0"));

        StringBuilder tooMany = new StringBuilder("SGPB1|9");
        for (int i = 0; i < 9; i++) {
            tooMany.append('\n').append(rawCodecEntry("com.example.pkg" + i, "1",
                    Long.toString(11_000L + i), Long.toString(10_900L + i),
                    Long.toString(i + 1L), "1|0|0|0|0|0|0|0|0"));
        }
        checkCorruptCodecRejected("too many rows", tooMany.toString());

        StringBuilder tooLong = new StringBuilder();
        while (tooLong.length() <= 8192) {
            tooLong.append('x');
        }
        checkCorruptCodecRejected("encoded input exceeds hard limit", tooLong.toString());
    }

    private static void restoredMaxSequenceAndCountsStaySafe() {
        long max = 9_223_372_036_854_775_807L;
        StringBuilder encoded = new StringBuilder("SGPB1|8");
        for (int i = 0; i < 8; i++) {
            String clickedCount = i == 0 ? "9223372036854775807" : "1";
            encoded.append('\n').append(rawCodecEntry("com.example.pkg" + i, "1",
                    Long.toString(20_000L + i), Long.toString(19_000L + i),
                    Long.toString(max - i), clickedCount + "|0|0|0|0|0|0|0|0"));
        }

        SleepGuardEventStats stats = new SleepGuardEventStats();
        check(stats.restorePackageBreakdown(encoded.toString()),
                "valid MAX sequence and count codec must restore");
        SleepGuardEventStats.Snapshot restored = stats.snapshot();
        check(restored.packages.size() == 8, "MAX boundary restore must retain all eight rows");
        check("com.example.pkg0".equals(restored.packages.get(0).packageName),
                "MAX sequence package must initially be most recent");
        check(packageSnapshot(restored, "com.example.pkg0").countForType(0x00000001) == max,
                "MAX clicked count must restore exactly");

        check(stats.record(true, "dev.linjian.peek", 0x00001000,
                        "com.example.pkg7", 21_000L, 20_900L),
                "existing oldest package activity must be recorded after MAX restore");
        SleepGuardEventStats.Snapshot reactivated = stats.snapshot();
        check(reactivated.packages.size() == 8,
                "reactivating an existing package must not change capacity");
        check("com.example.pkg7".equals(reactivated.packages.get(0).packageName),
                "reactivated package must become most recent after sequence renumber");
        for (int i = 0; i < reactivated.packages.size(); i++) {
            check(reactivated.packages.get(i).activitySequence > 0L,
                    "renumbered activity sequence must stay positive at row " + i);
        }
        for (int i = 1; i < 8; i++) {
            String expectedPackage = "com.example.pkg" + (i - 1);
            check(expectedPackage.equals(reactivated.packages.get(i).packageName),
                    "renumbered existing rows must retain deterministic order at row " + i);
        }

        stats.restoreCount(SleepGuardEventStats.TYPE_VIEW_CLICKED, max);
        SleepGuardEventStats.Snapshot beforeSaturatedGlobalClick = stats.snapshot();
        int[] globalTypes = allEventTypes();
        long[] otherGlobalCountsBefore = new long[globalTypes.length];
        for (int i = 0; i < globalTypes.length; i++) {
            otherGlobalCountsBefore[i] = beforeSaturatedGlobalClick.countForType(globalTypes[i]);
        }
        long globalAmbientBefore = beforeSaturatedGlobalClick.ambientTotal();

        check(stats.record(true, "dev.linjian.peek", 0x00000001,
                        "com.example.pkg0", 21_100L, 21_000L),
                "MAX-count package must accept another event");
        SleepGuardEventStats.Snapshot saturatedGlobalSnapshot = stats.snapshot();
        check(saturatedGlobalSnapshot.countForType(SleepGuardEventStats.TYPE_VIEW_CLICKED) == max,
                "global clicked count must remain saturated at Long.MAX_VALUE");
        check(saturatedGlobalSnapshot.countForType(SleepGuardEventStats.TYPE_VIEW_CLICKED) >= 0L,
                "global clicked count must never become negative");
        check(saturatedGlobalSnapshot.directTotal() == max,
                "global direct total must remain saturated at Long.MAX_VALUE");
        check(saturatedGlobalSnapshot.directTotal() >= 0L,
                "global direct total must never become negative");
        for (int i = 0; i < globalTypes.length; i++) {
            if (globalTypes[i] == SleepGuardEventStats.TYPE_VIEW_CLICKED) continue;
            check(saturatedGlobalSnapshot.countForType(globalTypes[i]) == otherGlobalCountsBefore[i],
                    "global non-click count must stay unchanged for "
                            + SleepGuardEventStats.typeName(globalTypes[i]));
        }
        check(saturatedGlobalSnapshot.ambientTotal() == globalAmbientBefore,
                "global ambient total must remain unchanged by a clicked event");
        check(saturatedGlobalSnapshot.ambientTotal() >= 0L,
                "global ambient total must never become negative");
        SleepGuardEventStats.PackageSnapshot saturated =
                packageSnapshot(saturatedGlobalSnapshot, "com.example.pkg0");
        check(saturated.countForType(0x00000001) == max,
                "MAX clicked count must remain saturated after another click");
        check(saturated.countForType(0x00000001) >= 0L,
                "MAX package clicked count must never become negative");
        check(saturated.countForType(0x00001000) == 0L,
                "saturating clicked count must not affect another event type");
        check(saturated.directTotal() == max,
                "direct total must remain saturated and non-negative");
        check(saturated.directTotal() >= 0L,
                "package direct total must never become negative");
        check(saturated.ambientTotal() == 0L,
                "direct saturation must not affect ambient total");

        check(stats.record(true, "dev.linjian.peek", 0x00000001,
                        "com.example.pkg8", 21_200L, 21_100L),
                "ninth package must be recorded after restored activity");
        SleepGuardEventStats.Snapshot afterNinth = stats.snapshot();
        check(afterNinth.packages.size() == 8,
                "ninth package after restore must keep capacity at eight");
        check("com.example.pkg8".equals(afterNinth.packages.get(0).packageName),
                "new ninth package must become most recent");
        check(findPackage(afterNinth, "com.example.pkg6") == null,
                "ninth package must evict the true oldest row after reactivation");
        check(findPackage(afterNinth, "com.example.pkg7") != null,
                "reactivated formerly-oldest package must survive eviction");
        check(findPackage(afterNinth, "com.example.pkg0") != null,
                "MAX-count package must survive after its later activity");

        String reencoded = stats.encodePackageBreakdown();
        SleepGuardEventStats roundTripped = new SleepGuardEventStats();
        check(roundTripped.restorePackageBreakdown(reencoded),
                "post-MAX activity state must encode and restore again");
        checkPackageBreakdownsEqual(afterNinth, roundTripped.snapshot(),
                "post-MAX round-trip must preserve capacity, order, sequences and saturated counts");
    }

    private static void packageSnapshotCollectionIsImmutableAndStable() {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        stats.record(true, "dev.linjian.peek", 0x00000001,
                "com.example.one", 30_000L, 29_900L);
        stats.record(true, "dev.linjian.peek", 0x00000020,
                "com.example.two", 30_100L, 30_000L);
        SleepGuardEventStats.Snapshot oldSnapshot = stats.snapshot();

        int oldSize = oldSnapshot.packages.size();
        String[] oldPackageNames = new String[oldSize];
        int[] oldLastTypes = new int[oldSize];
        long[] oldWallTimes = new long[oldSize];
        long[] oldElapsedTimes = new long[oldSize];
        long[] oldSequences = new long[oldSize];
        int[] types = {
                SleepGuardEventStats.TYPE_VIEW_CLICKED,
                SleepGuardEventStats.TYPE_VIEW_LONG_CLICKED,
                SleepGuardEventStats.TYPE_VIEW_SELECTED,
                SleepGuardEventStats.TYPE_VIEW_FOCUSED,
                SleepGuardEventStats.TYPE_VIEW_TEXT_CHANGED,
                SleepGuardEventStats.TYPE_VIEW_SCROLLED,
                SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED,
                SleepGuardEventStats.TYPE_WINDOW_CONTENT_CHANGED,
                SleepGuardEventStats.TYPE_WINDOWS_CHANGED
        };
        long[] oldGlobalCounts = new long[types.length];
        for (int typeIndex = 0; typeIndex < types.length; typeIndex++) {
            oldGlobalCounts[typeIndex] = oldSnapshot.countForType(types[typeIndex]);
        }
        long oldGlobalDirectTotal = oldSnapshot.directTotal();
        long oldGlobalAmbientTotal = oldSnapshot.ambientTotal();
        check(oldGlobalCounts[0] == 1L, "old snapshot clicked baseline must be exactly one");
        check(oldGlobalCounts[5] == 0L, "old snapshot scrolled baseline must be exactly zero");
        check(oldGlobalDirectTotal == 1L, "old snapshot direct baseline must be exactly one");
        check(oldGlobalAmbientTotal == 1L, "old snapshot ambient baseline must be exactly one");
        long[][] oldCounts = new long[oldSize][types.length];
        for (int row = 0; row < oldSize; row++) {
            SleepGuardEventStats.PackageSnapshot entry = oldSnapshot.packages.get(row);
            oldPackageNames[row] = entry.packageName;
            oldLastTypes[row] = entry.lastEventType;
            oldWallTimes[row] = entry.lastEventWallMs;
            oldElapsedTimes[row] = entry.lastEventElapsedMs;
            oldSequences[row] = entry.activitySequence;
            for (int typeIndex = 0; typeIndex < types.length; typeIndex++) {
                oldCounts[row][typeIndex] = entry.countForType(types[typeIndex]);
            }
        }
        int oldGlobalType = oldSnapshot.lastNonSelfType;
        String oldGlobalPackage = oldSnapshot.lastNonSelfPackage;
        long oldGlobalWall = oldSnapshot.lastNonSelfWallMs;
        long oldGlobalElapsed = oldSnapshot.lastNonSelfElapsedMs;

        boolean collectionRejectedMutation = false;
        try {
            oldSnapshot.packages.clear();
        } catch (UnsupportedOperationException expected) {
            collectionRejectedMutation = true;
        }
        check(collectionRejectedMutation,
                "snapshot package collection must reject structural mutation");

        stats.record(true, "dev.linjian.peek", 0x00001000,
                "com.example.one", 30_200L, 30_100L);
        SleepGuardEventStats.Snapshot newSnapshot = stats.snapshot();
        check(newSnapshot.packages.size() == 2,
                "new snapshot must still contain the original two packages");
        check("com.example.one".equals(newSnapshot.packages.get(0).packageName),
                "new activity must update order in the new snapshot");
        check(packageSnapshot(newSnapshot, "com.example.one").countForType(0x00001000) == 1L,
                "new snapshot must include the newly recorded event");
        check(newSnapshot.countForType(SleepGuardEventStats.TYPE_VIEW_SCROLLED)
                        == oldGlobalCounts[5] + 1L,
                "new snapshot global scrolled count must increase by exactly one");
        check(newSnapshot.directTotal() == oldGlobalDirectTotal + 1L,
                "new snapshot global direct total must increase by exactly one");
        check(newSnapshot.ambientTotal() == oldGlobalAmbientTotal,
                "new snapshot global ambient total must remain unchanged");

        check(oldSnapshot.packages.size() == oldSize,
                "old snapshot package count must remain unchanged");
        for (int typeIndex = 0; typeIndex < types.length; typeIndex++) {
            check(oldSnapshot.countForType(types[typeIndex]) == oldGlobalCounts[typeIndex],
                    "old snapshot global count must remain unchanged for "
                            + SleepGuardEventStats.typeName(types[typeIndex]));
        }
        check(oldSnapshot.directTotal() == oldGlobalDirectTotal,
                "old snapshot global direct total must remain unchanged");
        check(oldSnapshot.ambientTotal() == oldGlobalAmbientTotal,
                "old snapshot global ambient total must remain unchanged");
        check(oldSnapshot.lastNonSelfType == oldGlobalType,
                "old snapshot latest global type must remain unchanged");
        check(oldSnapshot.lastNonSelfPackage.equals(oldGlobalPackage),
                "old snapshot latest global package must remain unchanged");
        check(oldSnapshot.lastNonSelfWallMs == oldGlobalWall,
                "old snapshot latest global wall time must remain unchanged");
        check(oldSnapshot.lastNonSelfElapsedMs == oldGlobalElapsed,
                "old snapshot latest global elapsed time must remain unchanged");
        for (int row = 0; row < oldSize; row++) {
            SleepGuardEventStats.PackageSnapshot entry = oldSnapshot.packages.get(row);
            check(entry.packageName.equals(oldPackageNames[row]),
                    "old snapshot package order/name must remain unchanged at row " + row);
            check(entry.lastEventType == oldLastTypes[row],
                    "old snapshot last event type must remain unchanged at row " + row);
            check(entry.lastEventWallMs == oldWallTimes[row],
                    "old snapshot wall time must remain unchanged at row " + row);
            check(entry.lastEventElapsedMs == oldElapsedTimes[row],
                    "old snapshot elapsed time must remain unchanged at row " + row);
            check(entry.activitySequence == oldSequences[row],
                    "old snapshot activity sequence must remain unchanged at row " + row);
            for (int typeIndex = 0; typeIndex < types.length; typeIndex++) {
                check(entry.countForType(types[typeIndex]) == oldCounts[row][typeIndex],
                        "old snapshot event count must remain unchanged at row " + row
                                + ", type index " + typeIndex);
            }
        }
        // PackageSnapshot exposes no array or mutable collection, so no caller-owned copy exists to mutate.
    }

    private static void checkCorruptCodecRejected(String caseName, String encoded) {
        SleepGuardEventStats stats = new SleepGuardEventStats();
        check(stats.record(true, "dev.linjian.peek", 0x00000001,
                        "com.example.before", 12_000L, 11_900L),
                "corrupt case precondition must record first old event: " + caseName);
        check(stats.record(true, "dev.linjian.peek", 0x00001000,
                        "com.example.before", 12_100L, 12_000L),
                "corrupt case precondition must advance old activity sequence: " + caseName);
        SleepGuardEventStats.PackageSnapshot oldEntry =
                packageSnapshot(stats.snapshot(), "com.example.before");
        check(oldEntry.activitySequence > 1L,
                "corrupt case precondition must use an old sequence above one: " + caseName);
        boolean restored;
        try {
            restored = stats.restorePackageBreakdown(encoded);
        } catch (RuntimeException unexpected) {
            throw new AssertionError("corrupt codec threw for case: " + caseName, unexpected);
        }
        check(!restored, "corrupt codec must return false for case: " + caseName);
        check(stats.snapshot().packages.isEmpty(),
                "corrupt codec must discard prior/partial rows for case: " + caseName);
        check(findPackage(stats.snapshot(), "com.example.before") == null,
                "corrupt codec must remove the old package for case: " + caseName);

        check(stats.record(true, "dev.linjian.peek", 0x00000001,
                        "com.example.after", 13_000L, 12_900L),
                "first valid event after corrupt restore must be accepted for case: " + caseName);
        SleepGuardEventStats.Snapshot restarted = stats.snapshot();
        check(restarted.packages.size() == 1,
                "first post-failure event must create exactly one package for case: " + caseName);
        SleepGuardEventStats.PackageSnapshot restartedEntry = restarted.packages.get(0);
        check("com.example.after".equals(restartedEntry.packageName),
                "first post-failure package must be the new package for case: " + caseName);
        check(restartedEntry.activitySequence == 1L,
                "first post-failure activity sequence must restart at one for case: " + caseName);
        check(restartedEntry.activitySequence > 0L,
                "first post-failure activity sequence must stay positive for case: " + caseName);
        check(restartedEntry.countForType(0x00000001) == 1L,
                "first post-failure clicked count must equal one for case: " + caseName);
    }

    private static String rawCodecEntry(String packageName, String lastType, String wall,
                                        String elapsed, String sequence, String counts) {
        return packageName + "|" + lastType + "|" + wall + "|" + elapsed + "|" + sequence
                + "|" + counts;
    }

    private static String rawCountsWithReplacement(int replacementIndex, String replacement) {
        StringBuilder counts = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            if (i > 0) counts.append('|');
            if (i == replacementIndex) counts.append(replacement);
            else counts.append(i == 0 ? '1' : '0');
        }
        return counts.toString();
    }

    private static String codecEntry(String packageName, long wall, long elapsed, long sequence) {
        return packageName + "|" + SleepGuardEventStats.TYPE_VIEW_CLICKED
                + "|" + wall + "|" + elapsed + "|" + sequence
                + "|1|0|0|0|0|0|0|0|0";
    }

    private static SleepGuardEventStats.PackageSnapshot packageSnapshot(
            SleepGuardEventStats.Snapshot snapshot, String packageName) {
        SleepGuardEventStats.PackageSnapshot entry = findPackage(snapshot, packageName);
        check(entry != null, "missing package row: " + packageName);
        return entry;
    }

    private static SleepGuardEventStats.PackageSnapshot findPackage(
            SleepGuardEventStats.Snapshot snapshot, String packageName) {
        for (SleepGuardEventStats.PackageSnapshot entry : snapshot.packages) {
            if (packageName.equals(entry.packageName)) return entry;
        }
        return null;
    }

    private static int[] allEventTypes() {
        return new int[] {
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
    }

    private static void checkPackageBreakdownsEqual(SleepGuardEventStats.Snapshot expected,
                                                     SleepGuardEventStats.Snapshot actual,
                                                     String message) {
        check(expected.packages.size() == actual.packages.size(), message + ": row count");
        int[] types = allEventTypes();
        for (int i = 0; i < expected.packages.size(); i++) {
            SleepGuardEventStats.PackageSnapshot left = expected.packages.get(i);
            SleepGuardEventStats.PackageSnapshot right = actual.packages.get(i);
            check(left.packageName.equals(right.packageName), message + ": package at " + i);
            check(left.lastEventType == right.lastEventType, message + ": last type at " + i);
            check(left.lastEventWallMs == right.lastEventWallMs,
                    message + ": wall time at " + i);
            check(left.lastEventElapsedMs == right.lastEventElapsedMs,
                    message + ": elapsed time at " + i);
            check(left.activitySequence == right.activitySequence,
                    message + ": activity sequence at " + i);
            for (int type : types) {
                check(left.countForType(type) == right.countForType(type),
                        message + ": count " + SleepGuardEventStats.typeName(type) + " at " + i);
            }
        }
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
        checkPackageBreakdownsEqual(expected, actual, message + ": package breakdown");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
