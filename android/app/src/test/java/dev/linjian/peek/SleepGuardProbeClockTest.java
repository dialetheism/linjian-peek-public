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

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
