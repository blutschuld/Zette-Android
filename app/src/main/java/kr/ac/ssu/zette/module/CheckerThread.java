package kr.ac.ssu.zette;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Instances;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 다음 방문 일정의 출발 시각을 계산한다. */
public final class CheckerThread implements Runnable, AutoCloseable {
    private static final String TAG = "CheckerThread";
    private static final long CALENDAR_LOOKAHEAD = TimeUnit.DAYS.toMillis(7);
    static final long GPS_TIMEOUT = 10_000L;
    static final long ROUTE_TIMEOUT = 15_000L;
    private static final long CALLER_WINDOW = TimeUnit.HOURS.toMillis(1);
    private static final String[] COLUMNS = {
            Instances.EVENT_ID, Events.TITLE, Events.EVENT_LOCATION, Instances.BEGIN,
            Events.RRULE, Events.RDATE, Instances.VISIBLE, Events.ALL_DAY
    };

    /**
     * destination은 캘린더 장소 문자열이다. 구현체에서 장소를 좌표로 찾은 뒤
     * 대중교통 경로를 조회한다. 콜백은 CheckerThread의 작업 실행기에 넣지 않는다.
     */
    public interface RouteSource {
        CompletableFuture<RouteResult> find(double originLng, double originLat,
                String destination, long earliestDepartureMillis, long arriveByMillis);
    }

    public static final class Trip {
        // 문 앞에서 나가는 시각과 목적지에 도착하는 시각. 도보·환승을 포함한다.
        public final long departAtMillis;
        public final long arriveAtMillis;
        public final InfoThread.TransitDetails transit;

        public Trip(long departAtMillis, long arriveAtMillis) {
            this(departAtMillis, arriveAtMillis, null);
        }

        public Trip(long departAtMillis, long arriveAtMillis, InfoThread.TransitDetails transit) {
            this.departAtMillis = departAtMillis;
            this.arriveAtMillis = arriveAtMillis;
            this.transit = transit;
        }
    }

    public static final class RouteResult {
        final List<Trip> trips;
        final long totalTimeSec;
        final Long arrivalTimeMillis;
        final List<kr.ac.ssu.zette.route.RouteResult> alternatives;
        final kr.ac.ssu.zette.route.RouteResult primaryRoute;

        final InfoThread.TransitDetails transit;

        private RouteResult(List<Trip> trips, long totalTimeSec, InfoThread.TransitDetails transit,
                Long arrivalTimeMillis, List<kr.ac.ssu.zette.route.RouteResult> alternatives,
                kr.ac.ssu.zette.route.RouteResult primaryRoute) {
            this.trips = trips;
            this.totalTimeSec = totalTimeSec;
            this.transit = transit;
            this.arrivalTimeMillis = arrivalTimeMillis;
            this.alternatives = alternatives;
            this.primaryRoute = primaryRoute;
        }

        /** 빈 목록은 조건을 충족하는 편이 없다는 조회 결과다. */
        public static RouteResult scheduled(List<Trip> trips) {
            return new RouteResult(new ArrayList<>(trips), 0L, null, null, Collections.emptyList(), null);
        }

        public static RouteResult estimate(long totalTimeSec) {
            return estimate(totalTimeSec, null);
        }

        public static RouteResult estimate(long totalTimeSec, InfoThread.TransitDetails transit) {
            return estimate(totalTimeSec, transit, null);
        }

        public static RouteResult estimate(long totalTimeSec, InfoThread.TransitDetails transit,
                Long arrivalTimeMillis) {
            return estimate(totalTimeSec, transit, arrivalTimeMillis, null);
        }

        static RouteResult estimate(long totalTimeSec, InfoThread.TransitDetails transit,
                Long arrivalTimeMillis, kr.ac.ssu.zette.route.RouteResult primaryRoute) {
            if (totalTimeSec <= 0 || totalTimeSec > Long.MAX_VALUE / 1000L) {
                throw new IllegalArgumentException("유효한 이동 시간이 필요합니다.");
            }
            return new RouteResult(null, totalTimeSec, transit, arrivalTimeMillis,
                    primaryRoute == null ? Collections.emptyList() : primaryRoute.getAlternatives(), primaryRoute);
        }
    }

    public static final class Plan {
        public final long eventId;
        public final String title;
        public final long appointmentMillis;
        public final long departAtMillis;
        public final boolean estimated;
        public final InfoThread.TransitDetails transit;
        public final List<kr.ac.ssu.zette.route.RouteResult> alternatives;
        final EventItinerary itinerary;

        Plan(long eventId, String title, long appointmentMillis,
                long departAtMillis, boolean estimated, InfoThread.TransitDetails transit) {
            this(eventId, title, appointmentMillis, departAtMillis, estimated, transit, Collections.emptyList());
        }

        Plan(long eventId, String title, long appointmentMillis, long departAtMillis,
                boolean estimated, InfoThread.TransitDetails transit,
                List<kr.ac.ssu.zette.route.RouteResult> alternatives) {
            this(eventId, title, appointmentMillis, departAtMillis, estimated, transit, alternatives, null);
        }

        Plan(long eventId, String title, long appointmentMillis, long departAtMillis,
                boolean estimated, InfoThread.TransitDetails transit,
                List<kr.ac.ssu.zette.route.RouteResult> alternatives, EventItinerary itinerary) {
            this.eventId = eventId;
            this.title = title;
            this.appointmentMillis = appointmentMillis;
            this.departAtMillis = departAtMillis;
            this.estimated = estimated;
            this.transit = transit;
            this.alternatives = alternatives;
            this.itinerary = itinerary;
        }

        String occurrenceId() {
            return eventId + ":" + appointmentMillis;
        }
    }

    private final Context context;
    private final GpsReader gpsReader;
    private final RouteSource routes;
    private final Map<Long, CallerThread> callers = new HashMap<>();
    private final Map<String, NotifiedPlan> notifiedPlans = new ConcurrentHashMap<>();
    private volatile Plan checkerPlan;
    private final RealtimeTransitApi realtime;
    private boolean closed;

    private static final class NotifiedPlan {
        final Plan plan;
        final long sentAt;

        NotifiedPlan(Plan plan, long sentAt) {
            this.plan = plan;
            this.sentAt = sentAt;
        }
    }

    // 기존 서비스에서 쓰는 위치·캘린더 조회용 생성자.
    public CheckerThread(Context context, GpsReader gpsReader) {
        this(context, gpsReader, null);
    }

    public CheckerThread(Context context, GpsReader gpsReader, RouteSource routes) {
        this(context, gpsReader, routes, null);
    }

    CheckerThread(Context context, GpsReader gpsReader, RouteSource routes, RealtimeTransitApi realtime) {
        this.context = context.getApplicationContext();
        this.gpsReader = gpsReader;
        this.routes = routes;
        this.realtime = realtime;
    }

    @Override
    public void run() {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            checkerPlan = null;
            Log.w(TAG, "캘린더 읽기 권한이 없습니다.");
            return;
        }

        try {
            Event event = readEvent(context, System.currentTimeMillis(), null);
            Plan previousEstimate = checkerPlan;
            if (event == null || previousEstimate != null && (previousEstimate.eventId != event.id
                    || previousEstimate.appointmentMillis != event.start)) checkerPlan = null;
            if (event == null || hasCaller(event.id)
                    || (routes != null && CallerThread.wasNotified(context, event))) {
                return;
            }

            GpsReader.Result gps = gpsReader.read(GPS_TIMEOUT);
            if (Thread.currentThread().isInterrupted()) return;
            if (!gps.isSuccess()) {
                Log.w(TAG, "위치 조회 실패. 오류 코드 " + gps.code);
                return;
            }
            if (routes == null) {
                Log.d(TAG, "위치와 다음 방문 일정 조회 완료");
                return;
            }

            Location origin = gps.location;
            long now = System.currentTimeMillis();
            if (now >= event.start) return;
            RouteResult result = routes.find(origin.getLongitude(), origin.getLatitude(),
                    event.destination, now, event.start).get(ROUTE_TIMEOUT, TimeUnit.MILLISECONDS);
            long resolvedAt = System.currentTimeMillis();
            if (!Thread.currentThread().isInterrupted() && resolvedAt < event.start) {
                Plan plan = selectCallerPlan(event, result, resolvedAt);
                checkerPlan = plan;
                if (shouldStartCaller(plan, result, resolvedAt)) {
                    startCaller(event, plan);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            // 일시적인 실패는 이미 계산한 출발 계획을 취소하지 않는다.
            Log.w(TAG, "일정 또는 경로 조회 실패", e);
        }
    }

    static boolean shouldStartCaller(Plan plan, RouteResult result, long now) {
        long target = plan == null ? 0 : plan.appointmentMillis;
        if (result.primaryRoute != null) target -= RealtimeDeparturePlanner.ARRIVAL_MARGIN;
        return plan != null && (plan.departAtMillis - now <= CALLER_WINDOW
                || (result.arrivalTimeMillis != null && result.arrivalTimeMillis >= target - CALLER_WINDOW));
    }

    private synchronized boolean hasCaller(long eventId) {
        callers.values().removeIf(caller -> !caller.isAlive());
        return closed || callers.containsKey(eventId);
    }

    private synchronized void startCaller(Event event, Plan plan) {
        if (closed || callers.containsKey(event.id)) return;
        CallerThread caller = new CallerThread(context, gpsReader, routes, event, plan, realtime,
                this::saveNotifiedPlan);
        callers.put(event.id, caller);
        caller.start();
    }

    synchronized void refreshCallers() {
        checkerPlan = null;
        for (CallerThread caller : callers.values()) {
            caller.requestRefresh();
        }
    }

    private void saveNotifiedPlan(Plan plan, long sentAt) {
        if (plan.itinerary == null || plan.itinerary.arrivalAt <= sentAt) return;
        notifiedPlans.put(plan.occurrenceId(), new NotifiedPlan(plan, sentAt));
        notifiedPlans.entrySet().removeIf(entry -> entry.getValue().plan.itinerary.arrivalAt <= sentAt);
    }

    synchronized InfoThread.CurrentEventInfo currentEventInfo(long now) {
        notifiedPlans.entrySet().removeIf(entry -> entry.getValue().plan.itinerary.arrivalAt <= now);
        NotifiedPlan latest = null;
        for (NotifiedPlan entry : notifiedPlans.values()) {
            if (entry.plan.itinerary.arrivalAt > now && (latest == null || entry.sentAt > latest.sentAt)) {
                latest = entry;
            }
        }
        if (latest != null) return InfoThread.CurrentEventInfo.fromPlan(latest.plan, "notified", now);

        Plan active = null;
        boolean hasActiveCaller = false;
        for (CallerThread caller : callers.values()) {
            if (!caller.isAlive()) continue;
            hasActiveCaller = true;
            Plan candidate = caller.currentPlan();
            if (candidate != null && candidate.appointmentMillis > now
                    && (active == null || candidate.appointmentMillis < active.appointmentMillis)) {
                active = candidate;
            }
        }
        if (hasActiveCaller) return InfoThread.CurrentEventInfo.fromPlan(active, "caller", now);
        Plan estimate = checkerPlan;
        if (estimate != null && estimate.appointmentMillis > now
                && (estimate.itinerary == null || estimate.itinerary.arrivalAt > now)) {
            return InfoThread.CurrentEventInfo.fromPlan(estimate, "checker", now);
        }
        return InfoThread.CurrentEventInfo.fromPlan(null, null, now);
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (CallerThread caller : callers.values()) {
            caller.interrupt();
        }
        callers.clear();
    }

    static Plan selectCallerPlan(Event event, RouteResult result, long earliest) {
        if (result.primaryRoute == null) return selectPlan(event, result, earliest);
        long departure = event.start - RealtimeDeparturePlanner.ARRIVAL_MARGIN - result.totalTimeSec * 1000L;
        return new Plan(event.id, event.title, event.start, departure, true, result.transit,
                result.alternatives, EventItinerary.estimate(result.primaryRoute, departure));
    }

    static Plan selectPlan(Event event, RouteResult result, long earliest) {
        if (result.trips == null) {
            long departAt = event.start - result.totalTimeSec * 1000L;
            return new Plan(event.id, event.title, event.start, departAt, true, result.transit,
                    result.alternatives);
        }

        Trip latest = null;
        for (Trip trip : result.trips) {
            if (trip.departAtMillis >= earliest && trip.arriveAtMillis >= trip.departAtMillis
                    && trip.arriveAtMillis <= event.start
                    && (latest == null || trip.departAtMillis > latest.departAtMillis
                    || (trip.departAtMillis == latest.departAtMillis
                    && trip.arriveAtMillis < latest.arriveAtMillis))) {
                latest = trip;
            }
        }
        return latest == null ? null
                : new Plan(event.id, event.title, event.start, latest.departAtMillis, false, latest.transit);
    }

    static Event readEvent(Context context, long now, Long eventId) {
        return readEvent(context, now, eventId, null, true);
    }

    static Event readTrackedEvent(Context context, long now, Event event) {
        return readEvent(context, now, event.id, event.recurring ? event.start : null, false);
    }

    private static Event readEvent(Context context, long now, Long eventId, Long occurrenceStart,
            boolean requirePlace) {
        long end = now + CALENDAR_LOOKAHEAD;
        Uri.Builder uri = Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, now);
        ContentUris.appendId(uri, end);
        String selection = Instances.BEGIN + " >= ? AND " + Instances.BEGIN + " < ?";
        if (requirePlace) {
            selection += " AND " + Instances.VISIBLE + " = 1 AND " + Events.ALL_DAY + " = 0"
                    + " AND " + Events.EVENT_LOCATION + " IS NOT NULL";
        }
        String[] bounds = {Long.toString(now), Long.toString(end)};
        if (eventId != null) {
            selection += " AND " + Instances.EVENT_ID + " = ?";
            bounds = new String[]{bounds[0], bounds[1], Long.toString(eventId)};
        }
        if (occurrenceStart != null) {
            selection += " AND " + Instances.BEGIN + " = ?";
            bounds = new String[]{bounds[0], bounds[1], bounds[2], Long.toString(occurrenceStart)};
        }
        try (Cursor rows = context.getContentResolver().query(uri.build(), COLUMNS, selection,
                bounds,
                Instances.BEGIN + " ASC, " + Instances.EVENT_ID + " ASC")) {
            if (rows == null) throw new IllegalStateException("캘린더 조회 결과가 없습니다.");

            while (rows.moveToNext()) {
                String place = rows.getString(2);
                String destination = place == null ? "" : place.trim();
                if (!requirePlace || !destination.isEmpty()) {
                    boolean recurring = !rows.isNull(4) || !rows.isNull(5);
                    return new Event(rows.getLong(0), rows.getString(1), destination,
                            rows.getLong(3), recurring, rows.getInt(6) != 0 && rows.getInt(7) == 0);
                }
            }
            return null;
        }
    }

    static final class Event {
        final long id;
        final String title;
        final String destination;
        final long start;
        final boolean recurring;
        final boolean eligibleForDeparture;

        Event(long id, String title, String destination, long start, boolean recurring,
                boolean eligibleForDeparture) {
            this.id = id;
            this.title = title;
            this.destination = destination;
            this.start = start;
            this.recurring = recurring;
            this.eligibleForDeparture = eligibleForDeparture;
        }
    }
}
