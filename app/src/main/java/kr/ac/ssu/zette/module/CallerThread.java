package kr.ac.ssu.zette;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.util.Log;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/** 한 일정의 출발 계획을 다시 계산하고 알림을 한 번 보낸다. */
public final class CallerThread extends Thread {
    private static final String TAG = "CallerThread";
    private static final String CHANNEL = "departure_reminders";
    private static final String PREFS = "departure_reminder_state";
    private static final String LEGACY_SENT = "last_notified_occurrence";
    private static final String SENT_PREFIX = "notified:";
    private static final int NOTIFICATION_ID = 2301;
    private static final long MINUTE = 60_000L;
    private static final long NOTICE = 5 * MINUTE;
    private static final long RETRY_INTERVAL = MINUTE;
    private static final long FINAL_CHECK_INTERVAL = 30_000L;
    private static final long COORDINATE_ONLY_NOTICE = 10 * MINUTE;
    private static final long REALTIME_TIMEOUT = 12_000L;
    private static final long IDLE_INTERVAL = 10 * MINUTE;

    private final Context context;
    private final GpsReader gpsReader;
    private final CheckerThread.RouteSource routes;
    private final long notice;
    private final RealtimeDeparturePlanner realtimePlanner;
    private final BiConsumer<CheckerThread.Plan, Long> notifiedPlan;
    private final Object refreshLock = new Object();

    private boolean refreshRequested;
    private CheckerThread.Event event;
    private CheckerThread.Plan plan;
    private volatile CheckerThread.Plan publishedPlan;
    private boolean noOnTimeRoute;

    CallerThread(Context context, GpsReader gpsReader, CheckerThread.RouteSource routes,
            CheckerThread.Event event, CheckerThread.Plan plan, RealtimeTransitApi realtime,
            BiConsumer<CheckerThread.Plan, Long> notifiedPlan) {
        super("departure-" + event.id);
        this.context = context.getApplicationContext();
        this.gpsReader = gpsReader;
        this.routes = routes;
        this.realtimePlanner = realtime == null ? null : new RealtimeDeparturePlanner(realtime);
        this.notifiedPlan = notifiedPlan;
        if (routes instanceof KakaoTransitRouteSource) {
            this.notice = NOTICE;
        } else {
            this.notice = routes instanceof TransitRouteSource
                    && !((TransitRouteSource) routes).supportsDepartureTime()
                    ? COORDINATE_ONLY_NOTICE : NOTICE;
        }
        this.event = event;
        this.plan = plan;
        this.publishedPlan = plan;
    }

    CheckerThread.Plan currentPlan() {
        return publishedPlan;
    }

    void requestRefresh() {
        synchronized (refreshLock) {
            refreshRequested = true;
            refreshLock.notifyAll();
        }
    }

    @Override
    public void run() {
        boolean sent = wasNotified(context, event);
        while (!isInterrupted()) {
            try {
                long now = System.currentTimeMillis();
                CheckerThread.Event updated = CheckerThread.readTrackedEvent(context, now, event);
                if (updated == null) return;
                if (updated.start != event.start || !Objects.equals(updated.destination, event.destination)) {
                    plan = null;
                    publishedPlan = null;
                    noOnTimeRoute = false;
                }
                event = updated;
                if (sent || !event.eligibleForDeparture || event.destination.isEmpty()) {
                    plan = null;
                    publishedPlan = null;
                    waitForRefresh(Math.min(IDLE_INTERVAL, event.start - now));
                    continue;
                }

                now = System.currentTimeMillis();
                synchronized (refreshLock) {
                    if (isInterrupted()) return;
                    if (refreshRequested) {
                        refreshRequested = false;
                        continue;
                    }
                    sent = notifyIfDue(now);
                    publishedPlan = sent || noOnTimeRoute ? null : plan;
                }
                if (sent) continue;

                long checkStartedAt = now;
                long noticeAt = plan == null ? Long.MAX_VALUE : plan.departAtMillis - notice;
                long deadline = noticeAt > now ? Math.min(noticeAt, event.start) : event.start;
                CheckerThread.Plan prior = plan;
                boolean priorNoOnTimeRoute = noOnTimeRoute;
                recalculate(deadline);
                now = System.currentTimeMillis();
                synchronized (refreshLock) {
                    if (isInterrupted()) return;
                    if (refreshRequested) {
                        plan = prior;
                        noOnTimeRoute = priorNoOnTimeRoute;
                        refreshRequested = false;
                        continue;
                    }
                    if (prior != null && checkStartedAt < noticeAt && now >= noticeAt
                            && !noOnTimeRoute && plan != null && plan.departAtMillis > prior.departAtMillis) {
                        plan = prior;
                        noOnTimeRoute = priorNoOnTimeRoute;
                    }
                    sent = notifyIfDue(now);
                    publishedPlan = sent || noOnTimeRoute ? null : plan;
                }
                long nextCheck = checkStartedAt + checkDelay(plan, checkStartedAt, notice);
                if (plan != null && plan.departAtMillis - notice > now) {
                    nextCheck = Math.min(nextCheck, plan.departAtMillis - notice);
                }
                waitForRefresh(Math.min(nextCheck, event.start) - now);
            } catch (InterruptedException e) {
                interrupt();
                return;
            } catch (RuntimeException e) {
                // 캘린더 조회 실패 중에는 일정 상태를 확인할 수 없으므로 다음 확인까지 유지한다.
                Log.w(TAG, "출발 알림 확인 실패", e);
                if (System.currentTimeMillis() >= event.start) return;
                try {
                    waitForRefresh(RETRY_INTERVAL);
                } catch (InterruptedException interrupted) {
                    interrupt();
                    return;
                }
            }
        }
    }

    private void recalculate(long deadline) throws InterruptedException {
        try {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return;
            GpsReader.Result gps = gpsReader.read(Math.min(CheckerThread.GPS_TIMEOUT, remaining));
            if (isInterrupted()) throw new InterruptedException();
            if (!gps.isSuccess()) return;
            Location origin = gps.location;
            long now = System.currentTimeMillis();
            if (now >= deadline) return;

            CheckerThread.RouteResult route = routes.find(origin.getLongitude(),
                    origin.getLatitude(), event.destination, now, event.start)
                    .get(Math.min(CheckerThread.ROUTE_TIMEOUT, deadline - now), TimeUnit.MILLISECONDS);
            now = System.currentTimeMillis();
            CheckerThread.Plan estimate = CheckerThread.selectCallerPlan(event, route, now);
            if (realtimePlanner == null || route.primaryRoute == null || now >= deadline) {
                plan = estimate;
                noOnTimeRoute = false;
                return;
            }
            RealtimeDeparturePlanner.Selection live = realtimePlanner.select(event, route,
                    Math.min(deadline, now + REALTIME_TIMEOUT));
            noOnTimeRoute = live.allRoutesBlocked;
            if (live.plan != null) {
                plan = live.plan;
            } else if (noOnTimeRoute) {
                plan = estimate;
            } else if (plan == null || estimate.departAtMillis < plan.departAtMillis) {
                // 응답이 없을 때는 시각을 앞당길 수 있지만 기존 알림을 미루지 않는다.
                plan = estimate;
            }
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            Log.w(TAG, "경로 재계산 실패", e);
        }
    }

    static long checkDelay(CheckerThread.Plan plan, long now, long notice) {
        if (plan == null) return RETRY_INTERVAL;
        long untilDeparture = plan.departAtMillis - now;
        long untilNotice = untilDeparture - notice;
        if (untilNotice <= 0) return FINAL_CHECK_INTERVAL;

        long interval;
        if (untilDeparture > 30 * MINUTE) {
            interval = Math.min(5 * MINUTE, untilDeparture - 30 * MINUTE);
        } else if (untilDeparture > 15 * MINUTE) {
            interval = Math.min(2 * MINUTE, untilDeparture - 15 * MINUTE);
        } else {
            interval = FINAL_CHECK_INTERVAL;
        }
        return Math.min(interval, untilNotice);
    }

    private void waitForRefresh(long millis) throws InterruptedException {
        synchronized (refreshLock) {
            if (!refreshRequested && millis > 0) {
                refreshLock.wait(millis);
            }
            refreshRequested = false;
        }
    }

    static boolean wasNotified(Context context, CheckerThread.Event event) {
        String occurrence = event.id + ":" + event.start;
        SharedPreferences state = prefs(context);
        return state.contains(SENT_PREFIX + occurrence)
                || occurrence.equals(state.getString(LEGACY_SENT, ""));
    }

    private boolean notifyIfDue(long now) {
        synchronized (refreshLock) {
            return !isInterrupted() && !refreshRequested && plan != null
                    && (plan.estimated || now <= plan.departAtMillis)
                    && (noOnTimeRoute || now >= plan.departAtMillis - notice) && now < event.start
                    && sendNotification(now);
        }
    }

    private boolean sendNotification(long now) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return false;
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "출발 알림", NotificationManager.IMPORTANCE_HIGH));
            if (manager.getNotificationChannel(CHANNEL).getImportance()
                    == NotificationManager.IMPORTANCE_NONE) return false;
            builder = new Notification.Builder(context, CHANNEL);
        } else {
            builder = new Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL);
        }
        long untilDeparture = plan.departAtMillis - now;
        String message = untilDeparture <= 0L ? "지금 출발할 시간이에요."
                : "출발까지 약 " + ((untilDeparture + MINUTE - 1L) / MINUTE) + "분 남았어요.";
        if (noOnTimeRoute) {
            message = "확인한 경로로는 목표 시각까지 도착하기 어려워요.";
        } else if (plan.estimated) {
            message = "예상 경로 기준 · " + message;
        }
        String occurrence = plan.occurrenceId();
        manager.notify(occurrence, NOTIFICATION_ID, builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(event.title == null ? "출발 알림" : event.title)
                .setContentText(message)
                .setAutoCancel(true)
                .build());
        if (!noOnTimeRoute) notifiedPlan.accept(plan, now);

        SharedPreferences state = prefs(context);
        SharedPreferences.Editor edit = state.edit();
        state.getAll().forEach((key, value) -> {
            if (key.startsWith(SENT_PREFIX) && value instanceof Long && (Long) value < now) {
                edit.remove(key);
            }
        });
        edit.putLong(SENT_PREFIX + occurrence, event.start).apply();
        return true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
