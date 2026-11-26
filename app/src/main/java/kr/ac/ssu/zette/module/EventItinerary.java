package kr.ac.ssu.zette;

import kr.ac.ssu.zette.route.RouteResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 알림을 계산한 시점의 경로와 탑승 시각을 함께 보관한다. */
final class EventItinerary {
    static final class Ride {
        final RouteResult.Segment segment;
        final String line;
        final long boardAt;
        final Long vehicleAt;
        final boolean forecast;

        Ride(RouteResult.Segment segment, String line, long boardAt, Long vehicleAt, boolean forecast) {
            this.segment = segment;
            this.line = line;
            this.boardAt = boardAt;
            this.vehicleAt = vehicleAt;
            this.forecast = forecast;
        }
    }

    final RouteResult route;
    final long arrivalAt;
    final List<Ride> rides;

    EventItinerary(RouteResult route, long arrivalAt, List<Ride> rides) {
        this.route = route;
        this.arrivalAt = arrivalAt;
        this.rides = Collections.unmodifiableList(new ArrayList<>(rides));
    }

    Ride nextRide(long now) {
        if (now >= arrivalAt) return null;
        for (Ride ride : rides) {
            if (ride.boardAt >= now) return ride;
        }
        return null;
    }

    static EventItinerary estimate(RouteResult route, long departAt) {
        List<Ride> rides = new ArrayList<>();
        long at = departAt + route.walkToFirstStopSec * 1000L;
        for (RouteResult.Segment segment : route.getSegments()) {
            if ("BUS".equals(segment.type) || "SUBWAY".equals(segment.type)) {
                String line = segment.lineNames.isEmpty() ? null : String.join(", ", segment.lineNames);
                rides.add(new Ride(segment, line, at, null, true));
            }
            at += segment.durationSec * 1000L;
        }
        return new EventItinerary(route, departAt + route.totalTimeSec * 1000L, rides);
    }
}
