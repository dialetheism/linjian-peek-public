package dev.linjian.peek;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Local-only dual-signal feasibility probe. It never uploads or performs actions. */
public final class SleepGuardProbe {
    public static final long USAGE_POLL_INTERVAL_MS = 3000L;
    private static final long USAGE_QUERY_OVERLAP_MS = 5000L;
    private static final long PERSIST_INTERVAL_MS = 5000L;
    private static final long WALL_CLOCK_ROLLBACK_TOLERANCE_MS = 30000L;
    private static final Object LOCK = new Object();

    private static Context appContext;
    private static HandlerThread workerThread;
    private static Handler workerHandler;
    private static Runnable usagePollRunnable;
    private static long workerGeneration;
    private static boolean loaded;
    private static boolean enabled;
    private static boolean serviceConnected;
    private static boolean pendingPersist;
    private static boolean flushScheduled;
    private static long lastPersistElapsedMs;

    private static long a11yStartCount;
    private static long a11yEndCount;
    private static long lastA11yStartWallMs;
    private static long lastA11yStartElapsedMs;
    private static String lastA11yStartPackage = "";
    private static long lastA11yEndWallMs;
    private static long lastA11yEndElapsedMs;

    private static long usageCount;
    private static long lastUsageEventMs;
    private static String lastUsagePackage = "";
    private static long lastUsageDelayMs;
    private static SleepGuardProbeClock.Cursor usageCursor = new SleepGuardProbeClock.Cursor(0L, "");
    private static final SleepGuardEventStats eventStats = new SleepGuardEventStats();

    private SleepGuardProbe() { }

    private static final Runnable flushRunnable = new Runnable() {
        @Override public void run() {
            synchronized (LOCK) {
                flushScheduled = false;
                persistLocked();
            }
        }
    };

    public static void attach(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
            ensureLoadedLocked(appContext);
            serviceConnected = true;
            if (enabled) startWorkerLocked();
        }
    }

    public static void detach(Context context) {
        synchronized (LOCK) {
            Context ctx = context == null ? appContext : context.getApplicationContext();
            if (ctx != null) {
                ensureLoadedLocked(ctx);
                persistLocked();
            }
            serviceConnected = false;
            stopWorkerLocked();
            appContext = null;
        }
    }

    public static void onServiceInterrupted(Context context) {
        synchronized (LOCK) {
            Context ctx = context == null ? appContext : context.getApplicationContext();
            if (ctx != null) {
                ensureLoadedLocked(ctx);
                persistLocked();
            }
            serviceConnected = false;
            stopWorkerLocked();
        }
    }

    public static void onAccessibilityEventObserved(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
            ensureLoadedLocked(appContext);
            serviceConnected = true;
            if (enabled) startWorkerLocked();
        }
    }

    public static boolean isEnabled(Context context) {
        synchronized (LOCK) {
            ensureLoadedLocked(context.getApplicationContext());
            return enabled;
        }
    }

    public static void setEnabled(Context context, boolean value) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            ensureLoadedLocked(app);
            enabled = value;
            if (enabled) usageCursor.clear(System.currentTimeMillis());
            pendingPersist = true;
            persistLocked();
            if (enabled && serviceConnected) startWorkerLocked();
            else if (!enabled) stopWorkerLocked();
        }
    }

    public static void clearData(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            ensureLoadedLocked(context.getApplicationContext());
            boolean restartWorker = enabled && serviceConnected;
            stopWorkerLocked();
            pendingPersist = false;
            a11yStartCount = 0L;
            a11yEndCount = 0L;
            lastA11yStartWallMs = 0L;
            lastA11yStartElapsedMs = 0L;
            lastA11yStartPackage = "";
            lastA11yEndWallMs = 0L;
            lastA11yEndElapsedMs = 0L;
            usageCount = 0L;
            lastUsageEventMs = 0L;
            lastUsagePackage = "";
            lastUsageDelayMs = 0L;
            eventStats.clear();
            usageCursor.clear(enabled ? System.currentTimeMillis() : 0L);
            pendingPersist = true;
            persistLocked();
            if (restartWorker) startWorkerLocked();
        }
    }

    public static void onAccessibilityTouch(Context context, int eventType, String inferredCurrentPackage) {
        if (context == null) return;
        long wallNow = System.currentTimeMillis();
        long elapsedNow = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            ensureLoadedLocked(context.getApplicationContext());
            if (!enabled) return;
            if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
                a11yStartCount++;
                lastA11yStartWallMs = wallNow;
                lastA11yStartElapsedMs = elapsedNow;
                lastA11yStartPackage = inferredCurrentPackage == null ? "" : inferredCurrentPackage.trim();
            } else if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
                a11yEndCount++;
                lastA11yEndWallMs = wallNow;
                lastA11yEndElapsedMs = elapsedNow;
            } else {
                return;
            }
            pendingPersist = true;
            scheduleFlushLocked(elapsedNow);
        }
    }

    public static void onAccessibilityEventType(Context context, int eventType, String eventPackage) {
        if (context == null) return;
        long wallNow = System.currentTimeMillis();
        long elapsedNow = SystemClock.elapsedRealtime();
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            ensureLoadedLocked(app);
            if (!eventStats.record(enabled, app.getPackageName(), eventType, eventPackage,
                    wallNow, elapsedNow)) return;
            pendingPersist = true;
            scheduleFlushLocked(elapsedNow);
        }
    }

    public static boolean hasUsagePermission(Context context) {
        if (context == null) return false;
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isTouchExplorationEnabled(Context context) {
        if (context == null) return false;
        try {
            AccessibilityManager manager = (AccessibilityManager)
                    context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            return manager != null && manager.isTouchExplorationEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String statusText(Context context) {
        Snapshot snapshot = snapshot(context);
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        StringBuilder out = new StringBuilder();
        out.append("睡眠守门可行性探针\n");
        out.append("仅本机测试；不会弹窗、不会锁屏、不会上传。\n");
        out.append("探针：").append(snapshot.enabled ? "已开启" : "已关闭").append("\n");
        out.append("无障碍服务：").append(snapshot.serviceConnected ? "已连接" : "未连接或中断").append("\n");
        boolean usagePermission = hasUsagePermission(context);
        out.append("使用情况访问权限：").append(usagePermission ? "有" : "无（缺少使用情况访问权限）").append("\n");
        out.append("系统触摸探索：").append(isTouchExplorationEnabled(context) ? "开启" : "关闭").append("\n");
        out.append("掌心窗自身没有请求触摸探索。\n");
        String current = ScreenshotService.currentPackage();
        out.append("当前已知前台包：").append(empty(current)).append("\n\n");

        out.append("A11Y TOUCH START 数量：").append(snapshot.a11yStartCount).append("\n");
        out.append("A11Y TOUCH END 数量：").append(snapshot.a11yEndCount).append("\n");
        out.append("最后 A11Y START：").append(formatLocal(snapshot.lastA11yStartWallMs)).append("\n");
        out.append("距今：").append(snapshot.lastA11yStartWallMs <= 0 ? "-" :
                SleepGuardProbeClock.safeElapsedAgeSeconds(nowWall, nowElapsed,
                        snapshot.lastA11yStartWallMs, snapshot.lastA11yStartElapsedMs) + " 秒").append("\n");
        out.append("当时 currentPackage 快照（推测）：")
                .append(empty(snapshot.lastA11yStartPackage)).append("\n\n");

        out.append("USER_INTERACTION 数量：").append(snapshot.usageCount).append("\n");
        out.append("最后 USER_INTERACTION 包名：").append(empty(snapshot.lastUsagePackage)).append("\n");
        out.append("最后 USER_INTERACTION：").append(formatLocal(snapshot.lastUsageEventMs)).append("\n");
        out.append("距今：").append(snapshot.lastUsageEventMs <= 0 ? "-" :
                SleepGuardProbeClock.nonNegativeAgeSeconds(nowWall, snapshot.lastUsageEventMs) + " 秒").append("\n");
        out.append("观察延迟：").append(snapshot.lastUsageEventMs <= 0 ? "-" : snapshot.lastUsageDelayMs + " 毫秒")
                .append("\n\n");

        SleepGuardEventStats.Snapshot proxy = snapshot.eventStats;
        out.append("A11Y 事件类型代理（非掌心窗）\n");
        out.append("最近事件类型：").append(SleepGuardEventStats.typeName(proxy.lastNonSelfType)).append("\n");
        out.append("最近事件包名：").append(empty(proxy.lastNonSelfPackage)).append("\n");
        out.append("最近事件时间：").append(formatLocal(proxy.lastNonSelfWallMs)).append("\n");
        out.append("距今：").append(proxy.lastNonSelfWallMs <= 0 ? "-" :
                SleepGuardProbeClock.safeElapsedAgeSeconds(nowWall, nowElapsed,
                        proxy.lastNonSelfWallMs, proxy.lastNonSelfElapsedMs) + " 秒").append("\n");
        out.append("主动候选事件总数：").append(proxy.directTotal()).append("\n");
        out.append("  TYPE_VIEW_CLICKED：").append(proxy.countForType(SleepGuardEventStats.TYPE_VIEW_CLICKED)).append("\n");
        out.append("  TYPE_VIEW_LONG_CLICKED：").append(proxy.countForType(SleepGuardEventStats.TYPE_VIEW_LONG_CLICKED)).append("\n");
        out.append("  TYPE_VIEW_SCROLLED：").append(proxy.countForType(SleepGuardEventStats.TYPE_VIEW_SCROLLED)).append("\n");
        out.append("  TYPE_VIEW_TEXT_CHANGED：").append(proxy.countForType(SleepGuardEventStats.TYPE_VIEW_TEXT_CHANGED)).append("\n");
        out.append("  TYPE_VIEW_SELECTED：").append(proxy.countForType(SleepGuardEventStats.TYPE_VIEW_SELECTED)).append("\n");
        out.append("  TYPE_VIEW_FOCUSED：").append(proxy.countForType(SleepGuardEventStats.TYPE_VIEW_FOCUSED)).append("\n");
        out.append("环境事件总数：").append(proxy.ambientTotal()).append("\n");
        out.append("  TYPE_WINDOW_CONTENT_CHANGED：").append(proxy.countForType(SleepGuardEventStats.TYPE_WINDOW_CONTENT_CHANGED)).append("\n");
        out.append("  TYPE_WINDOW_STATE_CHANGED：").append(proxy.countForType(SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED)).append("\n");
        out.append("  TYPE_WINDOWS_CHANGED：").append(proxy.countForType(SleepGuardEventStats.TYPE_WINDOWS_CHANGED)).append("\n");
        out.append("掌心窗自身事件数量：").append(proxy.selfPackageCount).append("\n");
        out.append("提示：环境事件可能由动画自动产生，不能直接视为触摸。\n\n");
        out.append("按包名事件明细（最近活跃，最多 ")
                .append(SleepGuardEventStats.MAX_EXTERNAL_PACKAGES).append(" 个）\n");
        if (proxy.packages.isEmpty()) {
            out.append("尚未收到有效外部包事件");
        } else {
            for (int i = 0; i < proxy.packages.size(); i++) {
                SleepGuardEventStats.PackageSnapshot entry = proxy.packages.get(i);
                if (i > 0) out.append("\n");
                out.append("[").append(i + 1).append("] ").append(entry.packageName).append("\n");
                out.append("最近：").append(SleepGuardEventStats.typeName(entry.lastEventType))
                        .append("｜").append(formatLocalTime(entry.lastEventWallMs))
                        .append("｜距今 ").append(entry.lastEventWallMs <= 0L ? "-" :
                                SleepGuardProbeClock.safeElapsedAgeSeconds(
                                        nowWall, nowElapsed, entry.lastEventWallMs,
                                        entry.lastEventElapsedMs) + " 秒")
                        .append("\n");
                out.append("主动：").append(entry.directTotal())
                        .append("（点击 ").append(entry.countForType(SleepGuardEventStats.TYPE_VIEW_CLICKED))
                        .append("，长按 ").append(entry.countForType(SleepGuardEventStats.TYPE_VIEW_LONG_CLICKED))
                        .append("，滚动 ").append(entry.countForType(SleepGuardEventStats.TYPE_VIEW_SCROLLED))
                        .append("，文本 ").append(entry.countForType(SleepGuardEventStats.TYPE_VIEW_TEXT_CHANGED))
                        .append("，选中 ").append(entry.countForType(SleepGuardEventStats.TYPE_VIEW_SELECTED))
                        .append("，聚焦 ").append(entry.countForType(SleepGuardEventStats.TYPE_VIEW_FOCUSED))
                        .append("）\n");
                out.append("环境：").append(entry.ambientTotal())
                        .append("（内容 ").append(entry.countForType(SleepGuardEventStats.TYPE_WINDOW_CONTENT_CHANGED))
                        .append("，状态 ").append(entry.countForType(SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED))
                        .append("，窗口 ").append(entry.countForType(SleepGuardEventStats.TYPE_WINDOWS_CHANGED))
                        .append("）\n");
            }
        }
        return out.toString();
    }

    private static Snapshot snapshot(Context context) {
        synchronized (LOCK) {
            ensureLoadedLocked(context.getApplicationContext());
            return new Snapshot(enabled, serviceConnected, a11yStartCount, a11yEndCount,
                    lastA11yStartWallMs, lastA11yStartElapsedMs, lastA11yStartPackage,
                    usageCount, lastUsageEventMs, lastUsagePackage, lastUsageDelayMs,
                    eventStats.snapshot());
        }
    }

    private static void queryUsageEvents(Context context, long generation) {
        if (!hasUsagePermission(context)) return;
        try {
            UsageStatsManager manager = (UsageStatsManager)
                    context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (manager == null) return;
            long now = System.currentTimeMillis();
            long start;
            boolean changed;
            synchronized (LOCK) {
                if (!enabled || generation != workerGeneration) return;
                long before = usageCursor.timestampMs();
                usageCursor.rebaseAfterWallClockRollback(now, WALL_CLOCK_ROLLBACK_TOLERANCE_MS);
                changed = before != usageCursor.timestampMs();
                if (changed) pendingPersist = true;
                start = usageCursor.queryStartMs(now, USAGE_QUERY_OVERLAP_MS);
            }
            UsageEvents events = manager.queryEvents(start, now + 1L);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() != UsageEvents.Event.USER_INTERACTION) continue;
                long eventAt = event.getTimeStamp();
                String pkg = event.getPackageName();
                synchronized (LOCK) {
                    if (!enabled || !serviceConnected || generation != workerGeneration) return;
                    if (lastUsageEventMs <= 0L && eventAt <= usageCursor.timestampMs()) continue;
                    if (!usageCursor.accept(enabled, eventAt, pkg)) continue;
                    usageCount++;
                    lastUsageEventMs = eventAt;
                    lastUsagePackage = pkg == null ? "" : pkg.trim();
                    lastUsageDelayMs = SleepGuardProbeClock.observationDelayMs(
                            System.currentTimeMillis(), eventAt);
                    pendingPersist = true;
                    changed = true;
                }
            }
            if (changed) {
                synchronized (LOCK) {
                    if (!enabled || !serviceConnected || generation != workerGeneration) return;
                    scheduleFlushLocked(SystemClock.elapsedRealtime());
                }
            }
        } catch (SecurityException ignored) {
            // Permission can be revoked while a query is in flight; the status view reflects it.
        } catch (Exception ignored) {
            // A probe failure must never affect normal phone interaction or the accessibility service.
        }
    }

    private static void ensureLoadedLocked(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        if (loaded) return;
        SharedPreferences prefs = AppPrefs.get(appContext);
        enabled = prefs.getBoolean(AppPrefs.KEY_SLEEP_GUARD_PROBE_ENABLED, false);
        a11yStartCount = prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_A11Y_START_COUNT, 0L);
        a11yEndCount = prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_A11Y_END_COUNT, 0L);
        lastA11yStartWallMs = prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_A11Y_START_WALL_MS, 0L);
        lastA11yStartElapsedMs = prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_A11Y_START_ELAPSED_MS, 0L);
        lastA11yStartPackage = prefs.getString(AppPrefs.KEY_SLEEP_GUARD_A11Y_START_PACKAGE, "");
        lastA11yEndWallMs = prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_A11Y_END_WALL_MS, 0L);
        lastA11yEndElapsedMs = prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_A11Y_END_ELAPSED_MS, 0L);
        usageCount = prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_USAGE_COUNT, 0L);
        lastUsageEventMs = prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_USAGE_EVENT_MS, 0L);
        lastUsagePackage = prefs.getString(AppPrefs.KEY_SLEEP_GUARD_USAGE_PACKAGE, "");
        lastUsageDelayMs = prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_USAGE_DELAY_MS, 0L);
        usageCursor = new SleepGuardProbeClock.Cursor(
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_USAGE_CURSOR_MS, 0L),
                prefs.getString(AppPrefs.KEY_SLEEP_GUARD_USAGE_CURSOR_PACKAGES, ""));
        eventStats.restoreCount(SleepGuardEventStats.TYPE_VIEW_CLICKED,
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_VIEW_CLICKED_COUNT, 0L));
        eventStats.restoreCount(SleepGuardEventStats.TYPE_VIEW_LONG_CLICKED,
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_VIEW_LONG_CLICKED_COUNT, 0L));
        eventStats.restoreCount(SleepGuardEventStats.TYPE_VIEW_SCROLLED,
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_VIEW_SCROLLED_COUNT, 0L));
        eventStats.restoreCount(SleepGuardEventStats.TYPE_VIEW_TEXT_CHANGED,
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_VIEW_TEXT_CHANGED_COUNT, 0L));
        eventStats.restoreCount(SleepGuardEventStats.TYPE_VIEW_SELECTED,
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_VIEW_SELECTED_COUNT, 0L));
        eventStats.restoreCount(SleepGuardEventStats.TYPE_VIEW_FOCUSED,
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_VIEW_FOCUSED_COUNT, 0L));
        eventStats.restoreCount(SleepGuardEventStats.TYPE_WINDOW_CONTENT_CHANGED,
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_WINDOW_CONTENT_CHANGED_COUNT, 0L));
        eventStats.restoreCount(SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED,
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_WINDOW_STATE_CHANGED_COUNT, 0L));
        eventStats.restoreCount(SleepGuardEventStats.TYPE_WINDOWS_CHANGED,
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_WINDOWS_CHANGED_COUNT, 0L));
        eventStats.restoreSelfPackageCount(
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_SELF_PACKAGE_COUNT, 0L));
        eventStats.restoreLastNonSelf(
                prefs.getInt(AppPrefs.KEY_SLEEP_GUARD_PROXY_LAST_NON_SELF_TYPE, 0),
                prefs.getString(AppPrefs.KEY_SLEEP_GUARD_PROXY_LAST_NON_SELF_PACKAGE, ""),
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_LAST_NON_SELF_WALL_MS, 0L),
                prefs.getLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_LAST_NON_SELF_ELAPSED_MS, 0L));
        String encodedBreakdown = prefs.getString(
                AppPrefs.KEY_SLEEP_GUARD_PROXY_PACKAGE_BREAKDOWN, "");
        if (!eventStats.restorePackageBreakdown(encodedBreakdown)
                && encodedBreakdown != null && !encodedBreakdown.isEmpty()) {
            pendingPersist = true;
        }
        if (enabled && usageCursor.timestampMs() <= 0L) {
            usageCursor.clear(System.currentTimeMillis());
            pendingPersist = true;
        }
        lastPersistElapsedMs = SystemClock.elapsedRealtime();
        loaded = true;
    }

    private static void startWorkerLocked() {
        if (workerHandler != null || appContext == null || !enabled || !serviceConnected) return;
        workerThread = new HandlerThread("SleepGuardLocalProbe");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        final long generation = ++workerGeneration;
        usagePollRunnable = new Runnable() {
            @Override public void run() {
                Context ctx;
                synchronized (LOCK) {
                    if (!enabled || !serviceConnected || generation != workerGeneration
                            || workerHandler == null || appContext == null) return;
                    ctx = appContext;
                }
                queryUsageEvents(ctx, generation);
                synchronized (LOCK) {
                    if (enabled && serviceConnected
                            && generation == workerGeneration && workerHandler != null) {
                        workerHandler.postDelayed(this, USAGE_POLL_INTERVAL_MS);
                    }
                }
            }
        };
        workerHandler.post(usagePollRunnable);
        if (pendingPersist) scheduleFlushLocked(SystemClock.elapsedRealtime());
    }

    private static void stopWorkerLocked() {
        workerGeneration++;
        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
        workerHandler = null;
        usagePollRunnable = null;
        flushScheduled = false;
        if (workerThread != null) workerThread.quitSafely();
        workerThread = null;
    }

    private static void scheduleFlushLocked(long elapsedNow) {
        if (!SleepGuardProbeClock.shouldSchedulePersistence(pendingPersist, flushScheduled)) return;
        if (workerHandler == null) {
            persistLocked();
            return;
        }
        long sinceLast = Math.max(0L, elapsedNow - lastPersistElapsedMs);
        long delay = Math.max(0L, PERSIST_INTERVAL_MS - sinceLast);
        flushScheduled = true;
        workerHandler.postDelayed(flushRunnable, delay);
    }

    private static void persistLocked() {
        if (!pendingPersist || appContext == null) return;
        SleepGuardEventStats.Snapshot proxy = eventStats.snapshot();
        AppPrefs.get(appContext).edit()
                .putBoolean(AppPrefs.KEY_SLEEP_GUARD_PROBE_ENABLED, enabled)
                .putLong(AppPrefs.KEY_SLEEP_GUARD_A11Y_START_COUNT, a11yStartCount)
                .putLong(AppPrefs.KEY_SLEEP_GUARD_A11Y_END_COUNT, a11yEndCount)
                .putLong(AppPrefs.KEY_SLEEP_GUARD_A11Y_START_WALL_MS, lastA11yStartWallMs)
                .putLong(AppPrefs.KEY_SLEEP_GUARD_A11Y_START_ELAPSED_MS, lastA11yStartElapsedMs)
                .putString(AppPrefs.KEY_SLEEP_GUARD_A11Y_START_PACKAGE, lastA11yStartPackage)
                .putLong(AppPrefs.KEY_SLEEP_GUARD_A11Y_END_WALL_MS, lastA11yEndWallMs)
                .putLong(AppPrefs.KEY_SLEEP_GUARD_A11Y_END_ELAPSED_MS, lastA11yEndElapsedMs)
                .putLong(AppPrefs.KEY_SLEEP_GUARD_USAGE_COUNT, usageCount)
                .putLong(AppPrefs.KEY_SLEEP_GUARD_USAGE_EVENT_MS, lastUsageEventMs)
                .putString(AppPrefs.KEY_SLEEP_GUARD_USAGE_PACKAGE, lastUsagePackage)
                .putLong(AppPrefs.KEY_SLEEP_GUARD_USAGE_DELAY_MS, lastUsageDelayMs)
                .putLong(AppPrefs.KEY_SLEEP_GUARD_USAGE_CURSOR_MS, usageCursor.timestampMs())
                .putString(AppPrefs.KEY_SLEEP_GUARD_USAGE_CURSOR_PACKAGES, usageCursor.encodedPackages())
                .putLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_VIEW_CLICKED_COUNT,
                        proxy.countForType(SleepGuardEventStats.TYPE_VIEW_CLICKED))
                .putLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_VIEW_LONG_CLICKED_COUNT,
                        proxy.countForType(SleepGuardEventStats.TYPE_VIEW_LONG_CLICKED))
                .putLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_VIEW_SCROLLED_COUNT,
                        proxy.countForType(SleepGuardEventStats.TYPE_VIEW_SCROLLED))
                .putLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_VIEW_TEXT_CHANGED_COUNT,
                        proxy.countForType(SleepGuardEventStats.TYPE_VIEW_TEXT_CHANGED))
                .putLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_VIEW_SELECTED_COUNT,
                        proxy.countForType(SleepGuardEventStats.TYPE_VIEW_SELECTED))
                .putLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_VIEW_FOCUSED_COUNT,
                        proxy.countForType(SleepGuardEventStats.TYPE_VIEW_FOCUSED))
                .putLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_WINDOW_CONTENT_CHANGED_COUNT,
                        proxy.countForType(SleepGuardEventStats.TYPE_WINDOW_CONTENT_CHANGED))
                .putLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_WINDOW_STATE_CHANGED_COUNT,
                        proxy.countForType(SleepGuardEventStats.TYPE_WINDOW_STATE_CHANGED))
                .putLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_WINDOWS_CHANGED_COUNT,
                        proxy.countForType(SleepGuardEventStats.TYPE_WINDOWS_CHANGED))
                .putLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_SELF_PACKAGE_COUNT,
                        proxy.selfPackageCount)
                .putInt(AppPrefs.KEY_SLEEP_GUARD_PROXY_LAST_NON_SELF_TYPE,
                        proxy.lastNonSelfType)
                .putString(AppPrefs.KEY_SLEEP_GUARD_PROXY_LAST_NON_SELF_PACKAGE,
                        proxy.lastNonSelfPackage)
                .putLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_LAST_NON_SELF_WALL_MS,
                        proxy.lastNonSelfWallMs)
                .putLong(AppPrefs.KEY_SLEEP_GUARD_PROXY_LAST_NON_SELF_ELAPSED_MS,
                        proxy.lastNonSelfElapsedMs)
                .putString(AppPrefs.KEY_SLEEP_GUARD_PROXY_PACKAGE_BREAKDOWN,
                        eventStats.encodePackageBreakdown())
                .commit();
        pendingPersist = false;
        lastPersistElapsedMs = SystemClock.elapsedRealtime();
    }

    private static String formatLocal(long timestampMs) {
        if (timestampMs <= 0L) return "尚未收到";
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(timestampMs));
    }

    private static String formatLocalTime(long timestampMs) {
        if (timestampMs <= 0L) return "-";
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(timestampMs));
    }

    private static String empty(String value) {
        return value == null || value.trim().length() == 0 ? "-" : value.trim();
    }

    private static final class Snapshot {
        final boolean enabled;
        final boolean serviceConnected;
        final long a11yStartCount;
        final long a11yEndCount;
        final long lastA11yStartWallMs;
        final long lastA11yStartElapsedMs;
        final String lastA11yStartPackage;
        final long usageCount;
        final long lastUsageEventMs;
        final String lastUsagePackage;
        final long lastUsageDelayMs;
        final SleepGuardEventStats.Snapshot eventStats;

        Snapshot(boolean enabled, boolean serviceConnected,
                 long a11yStartCount, long a11yEndCount,
                 long lastA11yStartWallMs, long lastA11yStartElapsedMs,
                 String lastA11yStartPackage, long usageCount, long lastUsageEventMs,
                  String lastUsagePackage, long lastUsageDelayMs,
                  SleepGuardEventStats.Snapshot eventStats) {
            this.enabled = enabled;
            this.serviceConnected = serviceConnected;
            this.a11yStartCount = a11yStartCount;
            this.a11yEndCount = a11yEndCount;
            this.lastA11yStartWallMs = lastA11yStartWallMs;
            this.lastA11yStartElapsedMs = lastA11yStartElapsedMs;
            this.lastA11yStartPackage = lastA11yStartPackage;
            this.usageCount = usageCount;
            this.lastUsageEventMs = lastUsageEventMs;
            this.lastUsagePackage = lastUsagePackage;
            this.lastUsageDelayMs = lastUsageDelayMs;
            this.eventStats = eventStats;
        }
    }
}
