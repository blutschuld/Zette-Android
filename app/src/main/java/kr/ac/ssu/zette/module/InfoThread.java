package kr.ac.ssu.zette;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Log;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Activity가 지정한 일정의 출발 카드 데이터를 조회하고 요청자에게 반환한다. */
public final class InfoThread implements Runnable {
    public static final int SUCCESS = 0;
    public static final int INVALID_EVENT_ID = 1;
    public static final int CALENDAR_PERMISSION_REQUIRED = 2;
    public static final int EVENT_UNAVAILABLE = 3;
    public static final int LOCATION_UNAVAILABLE = 4;
    public static final int ROUTE_UNAVAILABLE = 5;
    public static final int ROUTE_TIMEOUT = 6;
    public static final int CALENDAR_UNAVAILABLE = 7;

    public static final String EVENT_ID = MyService.EXTRA_EVENT_ID;
    public static final String EVENT_TITLE = "eventTitle";
    public static final String DESTINATION = "destination";
    public static final String APPOINTMENT_AT_MILLIS = "appointmentAtMillis";
    public static final String DEPART_AT_MILLIS = "departAtMillis";
    public static final String DEPART_IN_MILLIS = "departInMillis";
    public static final String ESTIMATED = "estimated";
    public static final String BOARDING_STOP = "boardingStop";
    public static final String WALK_TO_STOP_SECONDS = "walkToStopSeconds";
    public static final String FIRST_VEHICLE_LABEL = "firstVehicleLabel";
    public static final String FIRST_BOARD_AT_MILLIS = "firstBoardAtMillis";
    public static final String FIRST_VEHICLE_ARRIVES_AT_MILLIS = "firstVehicleArrivesAtMillis";
    public static final String FIRST_VEHICLE_ARRIVES_IN_MILLIS = "firstVehicleArrivesInMillis";
    public static final String FOLLOWING_VEHICLE_LABEL = "followingVehicleLabel";
    public static final String FOLLOWING_VEHICLE_ARRIVES_AT_MILLIS = "followingVehicleArrivesAtMillis";
    public static final String FOLLOWING_VEHICLE_ARRIVES_IN_MILLIS = "followingVehicleArrivesInMillis";
    public static final String INFO_SOURCE = "infoSource";
    public static final String EXPECTED_ARRIVAL_AT_MILLIS = "expectedArrivalAtMillis";
    public static final String NEXT_VEHICLE_LABEL = "nextVehicleLabel";
    public static final String NEXT_VEHICLE_TYPE = "nextVehicleType";
    public static final String NEXT_BOARDING_STOP = "nextBoardingStop";
    public static final String NEXT_VEHICLE_ARRIVES_AT_MILLIS = "nextVehicleArrivesAtMillis";
    public static final String NEXT_VEHICLE_ARRIVES_IN_MILLIS = "nextVehicleArrivesInMillis";
    public static final String NEXT_VEHICLE_ARRIVES_IN_MINUTES = "nextVehicleArrivesInMinutes";
    public static final String NEXT_VEHICLE_FORECAST = "nextVehicleForecast";

    private static final String TAG = "InfoThread";

    static final class CurrentEventInfo {
        private final CheckerThread.Plan plan;
        private final String source;
        private final long now;

        private CurrentEventInfo(CheckerThread.Plan plan, String source, long now) {
            this.plan = plan;
            this.source = source;
            this.now = now;
        }

        static CurrentEventInfo fromPlan(CheckerThread.Plan plan, String source, long now) {
            return new CurrentEventInfo(plan, plan == null ? null : source, now);
        }

        Bundle toBundle() {
            Bundle data = new Bundle();
            data.putString(INFO_SOURCE, source);
            data.putString(EVENT_ID, plan == null ? null : Long.toString(plan.eventId));
            EventItinerary itinerary = plan == null ? null : plan.itinerary;
            boolean notified = "notified".equals(source);
            EventItinerary.Ride next = notified && itinerary != null ? itinerary.nextRide(now) : null;
            Vehicle firstVehicle = null;
            String stop = null;
            if ("caller".equals(source)) {
                if (itinerary != null && !itinerary.rides.isEmpty()) {
                    EventItinerary.Ride first = itinerary.rides.get(0);
                    stop = first.segment.boardStopName;
                    if (first.boardAt >= now) next = first;
                } else if (plan.transit != null) {
                    stop = plan.transit.boardingStop;
                    Vehicle first = plan.transit.firstVehicle;
                    if (first != null && (first.boardAtMillis == 0 || first.boardAtMillis >= now)
                            && (first.arrivesAtMillis == 0 || first.arrivesAtMillis >= now)) {
                        firstVehicle = first;
                    }
                }
            } else if (next != null) {
                stop = next.segment.boardStopName;
            }
            putOptionalLong(data, DEPART_AT_MILLIS, plan == null ? null : plan.departAtMillis);
            putOptionalLong(data, DEPART_IN_MILLIS, plan == null ? null
                    : Math.max(0L, plan.departAtMillis - now));
            putOptionalLong(data, EXPECTED_ARRIVAL_AT_MILLIS, notified && itinerary != null
                    ? itinerary.arrivalAt : null);
            Long vehicleAt = next == null ? null : next.vehicleAt;
            if (firstVehicle != null && firstVehicle.arrivesAtMillis > 0L) {
                vehicleAt = firstVehicle.arrivesAtMillis;
            }
            Long remaining = vehicleAt == null ? null : Math.max(0L, vehicleAt - now);
            data.putString(BOARDING_STOP, stop);
            data.putString(NEXT_BOARDING_STOP, next != null || firstVehicle != null ? stop : null);
            String line = next != null ? next.line : firstVehicle == null ? null : firstVehicle.label;
            data.putString(NEXT_VEHICLE_LABEL, line);
            data.putString(NEXT_VEHICLE_TYPE, next == null ? null : next.segment.type);
            putOptionalLong(data, NEXT_VEHICLE_ARRIVES_AT_MILLIS, vehicleAt);
            putOptionalLong(data, NEXT_VEHICLE_ARRIVES_IN_MILLIS, remaining);
            putOptionalLong(data, NEXT_VEHICLE_ARRIVES_IN_MINUTES, remaining == null ? null
                    : (remaining + 59_999L) / 60_000L);
            if (next == null || next.vehicleAt == null) {
                data.putString(NEXT_VEHICLE_FORECAST, null);
            } else {
                data.putBoolean(NEXT_VEHICLE_FORECAST, next.forecast);
            }
            return data;
        }

        private static void putOptionalLong(Bundle data, String key, Long value) {
            if (value == null) data.putString(key, null);
            else data.putLong(key, value);
        }
    }

    static void currentEventInfo(CheckerThread checker, ResultReceiver receiver) {
        receiver.send(SUCCESS, checker.currentEventInfo(System.currentTimeMillis()).toBundle());
    }

    /** 시각이 없으면 0을, 노선 표시명이 없으면 null을 사용한다. */
    public static final class Vehicle {
        public final String label;
        public final long boardAtMillis;
        public final long arrivesAtMillis;

        public Vehicle(String label, long boardAtMillis, long arrivesAtMillis) {
            this.label = label;
            this.boardAtMillis = boardAtMillis;
            this.arrivesAtMillis = arrivesAtMillis;
        }
    }

    /** 출발지에서 처음 탑승하는 정류장과 교통편의 정보. 도보 시간이 없으면 -1을 사용한다. */
    public static final class TransitDetails {
        public final String boardingStop;
        public final int walkToStopSeconds;
        public final Vehicle firstVehicle;
        public final Vehicle followingVehicle;

        public TransitDetails(String boardingStop, int walkToStopSeconds,
                Vehicle firstVehicle, Vehicle followingVehicle) {
            this.boardingStop = boardingStop;
            this.walkToStopSeconds = walkToStopSeconds;
            this.firstVehicle = firstVehicle;
            this.followingVehicle = followingVehicle;
        }
    }

    private final Context context;
    private final GpsReader gpsReader;
    private final CheckerThread.RouteSource routes;
    private final String eventId;
    private final ResultReceiver receiver;

    public InfoThread(Context context, GpsReader gpsReader, CheckerThread.RouteSource routes,
            String eventId, ResultReceiver receiver) {
        this.context = context.getApplicationContext();
        this.gpsReader = gpsReader;
        this.routes = routes;
        this.eventId = eventId;
        this.receiver = receiver;
    }

    @Override
    public void run() {
        long id;
        try {
            id = Long.parseLong(eventId == null ? "" : eventId.trim());
            if (id <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sendError(receiver, INVALID_EVENT_ID, eventId);
            return;
        }

        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            sendError(receiver, CALENDAR_PERMISSION_REQUIRED, eventId);
            return;
        }

        CheckerThread.Event event;
        try {
            event = CheckerThread.readEvent(context, System.currentTimeMillis(), id);
        } catch (SecurityException e) {
            sendError(receiver, CALENDAR_PERMISSION_REQUIRED, eventId);
            return;
        } catch (RuntimeException e) {
            Log.w(TAG, "일정 조회 실패", e);
            sendError(receiver, CALENDAR_UNAVAILABLE, eventId);
            return;
        }
        if (event == null) {
            sendError(receiver, EVENT_UNAVAILABLE, eventId);
            return;
        }

        GpsReader.Result gps;
        try {
            gps = gpsReader.read(CheckerThread.GPS_TIMEOUT);
        } catch (RuntimeException e) {
            Log.w(TAG, "위치 조회 실패", e);
            sendError(receiver, LOCATION_UNAVAILABLE, eventId);
            return;
        }
        if (Thread.currentThread().isInterrupted()) return;
        if (!gps.isSuccess()) {
            sendError(receiver, LOCATION_UNAVAILABLE, eventId);
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= event.start) {
            sendError(receiver, EVENT_UNAVAILABLE, eventId);
            return;
        }

        CheckerThread.Plan plan;
        long resolvedAt;
        try {
            Location origin = gps.location;
            CheckerThread.RouteResult route = routes.find(origin.getLongitude(), origin.getLatitude(),
                    event.destination, now, event.start)
                    .get(CheckerThread.ROUTE_TIMEOUT, TimeUnit.MILLISECONDS);
            resolvedAt = System.currentTimeMillis();
            plan = CheckerThread.selectPlan(event, route, resolvedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (TimeoutException e) {
            sendError(receiver, ROUTE_TIMEOUT, eventId);
            return;
        } catch (ExecutionException | RuntimeException e) {
            Log.w(TAG, "대중교통 경로 조회 실패", e);
            sendError(receiver, ROUTE_UNAVAILABLE, eventId);
            return;
        }
        if (resolvedAt >= event.start) {
            sendError(receiver, EVENT_UNAVAILABLE, eventId);
        } else if (plan == null) {
            sendError(receiver, ROUTE_UNAVAILABLE, eventId);
        } else {
            receiver.send(SUCCESS, toBundle(event, plan, resolvedAt));
        }
    }

    static void sendError(ResultReceiver receiver, int code, String eventId) {
        Bundle result = new Bundle();
        result.putString(EVENT_ID, eventId);
        receiver.send(code, result);
    }

    static Bundle toBundle(CheckerThread.Event event, CheckerThread.Plan plan, long now) {
        Bundle result = new Bundle();
        result.putString(EVENT_ID, Long.toString(event.id));
        result.putString(EVENT_TITLE, event.title);
        result.putString(DESTINATION, event.destination);
        result.putLong(APPOINTMENT_AT_MILLIS, event.start);
        result.putLong(DEPART_AT_MILLIS, plan.departAtMillis);
        result.putLong(DEPART_IN_MILLIS, Math.max(0L, plan.departAtMillis - now));
        result.putBoolean(ESTIMATED, plan.estimated);
        TransitDetails transit = plan.transit;
        if (transit == null) return result;

        if (transit.boardingStop != null) result.putString(BOARDING_STOP, transit.boardingStop);
        if (transit.walkToStopSeconds >= 0) {
            result.putInt(WALK_TO_STOP_SECONDS, transit.walkToStopSeconds);
        }
        Vehicle first = transit.firstVehicle;
        if (first != null) {
            if (first.label != null) result.putString(FIRST_VEHICLE_LABEL, first.label);
            if (first.boardAtMillis > 0) result.putLong(FIRST_BOARD_AT_MILLIS, first.boardAtMillis);
            putArrival(result, FIRST_VEHICLE_ARRIVES_AT_MILLIS, FIRST_VEHICLE_ARRIVES_IN_MILLIS,
                    first.arrivesAtMillis, now);
        }
        Vehicle following = transit.followingVehicle;
        if (following != null) {
            if (following.label != null) result.putString(FOLLOWING_VEHICLE_LABEL, following.label);
            putArrival(result, FOLLOWING_VEHICLE_ARRIVES_AT_MILLIS, FOLLOWING_VEHICLE_ARRIVES_IN_MILLIS,
                    following.arrivesAtMillis, now);
        }
        return result;
    }

    private static void putArrival(Bundle result, String atKey, String inKey, long at, long now) {
        if (at > 0) {
            result.putLong(atKey, at);
            result.putLong(inKey, Math.max(0L, at - now));
        }
    }
}
