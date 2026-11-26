package kr.ac.ssu.zette;

import kr.ac.ssu.zette.route.KakaoRouteParser;
import kr.ac.ssu.zette.route.RouteResult;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 동일한 GPS·경로 조회에서 환승 구간을 목적지부터 역산한다. */
final class RealtimeDeparturePlanner {
    static final long ARRIVAL_MARGIN = TimeUnit.MINUTES.toMillis(5);
    private static final long MAX_FORECAST = TimeUnit.MINUTES.toMillis(30);
    private static final long MAX_HEADWAY = TimeUnit.MINUTES.toMillis(30);
    private static final TimeZone SEOUL = TimeZone.getTimeZone("Asia/Seoul");
    private final RealtimeTransitApi api;

    static final class Selection {
        final CheckerThread.Plan plan;
        final boolean allRoutesBlocked;

        Selection(CheckerThread.Plan plan, boolean allRoutesBlocked) {
            this.plan = plan;
            this.allRoutesBlocked = allRoutesBlocked;
        }
    }

    RealtimeDeparturePlanner(RealtimeTransitApi api) {
        this.api = api;
    }

    Selection select(CheckerThread.Event event, CheckerThread.RouteResult result,
            long deadline) throws InterruptedException {
        List<RouteResult> routes = new ArrayList<>();
        routes.add(result.primaryRoute);
        routes.addAll(result.alternatives);
        Map<String, CompletableFuture<List<RealtimeTransitApi.BusStop>>> stops = new HashMap<>();
        Map<String, CompletableFuture<List<RealtimeTransitApi.Arrival>>> stations = new HashMap<>();
        Map<RouteResult.Segment, CompletableFuture<List<RealtimeTransitApi.Arrival>>> queries = new HashMap<>();

        for (RouteResult route : routes) {
            for (RouteResult.Segment segment : route.getSegments()) {
                queries.put(segment, arrivals(segment, stops, stations));
            }
        }

        CompletableFuture<?> all = CompletableFuture.allOf(queries.values().toArray(new CompletableFuture<?>[0]));
        try {
            all.get(Math.max(0L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException ignored) {
            // 대기 시간이 끝나면 이미 성공한 구간의 응답으로 계산한다.
        }

        long now = System.currentTimeMillis();
        long arriveBy = event.start - ARRIVAL_MARGIN;
        CheckerThread.Plan best = null;
        boolean allBlocked = true;
        for (RouteResult route : routes) {
            List<RouteResult.Segment> segments = route.getSegments();
            List<List<RealtimeTransitApi.Arrival>> rows = new ArrayList<>(segments.size());
            for (RouteResult.Segment segment : segments) {
                rows.add(queries.get(segment).getNow(Collections.emptyList()));
            }
            List<EventItinerary.Ride> rides = new ArrayList<>();
            Long departure = latestDeparture(route, rows, now, arriveBy, rides);
            if (departure != null && departure == Long.MIN_VALUE) continue;
            allBlocked = false;
            if (departure == null) continue;
            long departAt = departure;
            EventItinerary itinerary = new EventItinerary(route, arrivalAt(route, rides), rides);
            CheckerThread.Plan candidate = new CheckerThread.Plan(event.id, event.title, event.start,
                    departAt, true, null, result.alternatives, itinerary);
            if (best == null || departAt > best.departAtMillis
                    || departAt == best.departAtMillis && itinerary.arrivalAt < best.itinerary.arrivalAt) {
                best = candidate;
            }
        }
        return new Selection(best, allBlocked);
    }

    // null이면 실시간 정보 부족, MIN_VALUE이면 확인한 차량 조합으로는 제시간 도착 불가.
    static Long latestDeparture(RouteResult route, List<List<RealtimeTransitApi.Arrival>> rows,
            long now, long arriveBy) {
        return latestDeparture(route, rows, now, arriveBy, new ArrayList<>());
    }

    private static Long latestDeparture(RouteResult route, List<List<RealtimeTransitApi.Arrival>> rows,
            long now, long arriveBy, List<EventItinerary.Ride> rides) {
        List<RouteResult.Segment> segments = route.getSegments();
        long outerWalkSeconds = route.totalTimeSec;
        for (RouteResult.Segment segment : segments) outerWalkSeconds -= segment.durationSec;
        long initialWalk = Math.min(Math.max(0L, outerWalkSeconds), Math.max(0L, route.walkToFirstStopSec));
        long latest = arriveBy - Math.max(0L, outerWalkSeconds - initialWalk) * 1000L;
        boolean unknown = false;
        boolean hasRide = false;
        for (int i = segments.size() - 1; i >= 0; i--) {
            RouteResult.Segment segment = segments.get(i);
            latest -= segment.durationSec * 1000L;
            if ("WALKING".equals(segment.type)) continue;
            hasRide = true;
            Map<String, List<RealtimeTransitApi.Arrival>> groups = new HashMap<>();
            for (RealtimeTransitApi.Arrival row : rows.get(i)) {
                if (matchesSegment(segment, row)) {
                    String key = row.lineId + "|" + row.direction + "|"
                            + (row.mode == RealtimeTransitApi.Mode.SUBWAY ? row.destination : "");
                    groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
                }
            }
            if (groups.isEmpty()) {
                unknown = true;
                continue;
            }
            long chosen = Long.MIN_VALUE;
            List<RealtimeTransitApi.Arrival> chosenGroup = null;
            boolean everyGroupEnds = true;
            for (List<RealtimeTransitApi.Arrival> group : groups.values()) {
                Long boarding = latestBoarding(group, now, latest);
                if (boarding == null) everyGroupEnds = false;
                else if (boarding > chosen) {
                    chosen = boarding;
                    chosenGroup = group;
                }
            }
            if (chosen == Long.MIN_VALUE) {
                if (everyGroupEnds) return Long.MIN_VALUE;
                unknown = true;
            } else {
                latest = chosen;
                rides.add(toRide(segment, chosen, chosenGroup));
            }
        }
        long departure = latest - initialWalk * 1000L;
        if (departure < now) return Long.MIN_VALUE;
        if (unknown || !hasRide) return null;
        Collections.reverse(rides);
        return departure;
    }

    private static EventItinerary.Ride toRide(RouteResult.Segment segment, long at,
            List<RealtimeTransitApi.Arrival> group) {
        RealtimeTransitApi.Arrival row = group.get(0);
        boolean forecast = true;
        for (RealtimeTransitApi.Arrival arrival : group) {
            if (arrival.etaSeconds != null && arrival.fetchedAtMillis + arrival.etaSeconds * 1000L == at) {
                row = arrival;
                forecast = false;
                break;
            }
        }
        String line = row.mode == RealtimeTransitApi.Mode.BUS ? row.busRouteName
                : row.lineId.substring(3) + "호선";
        return new EventItinerary.Ride(segment, line, at, at, forecast);
    }

    private static long arrivalAt(RouteResult route, List<EventItinerary.Ride> rides) {
        EventItinerary.Ride last = rides.get(rides.size() - 1);
        long outerWalk = route.totalTimeSec;
        long fromLast = 0;
        boolean reachedLast = false;
        for (RouteResult.Segment segment : route.getSegments()) {
            outerWalk -= segment.durationSec;
            reachedLast |= segment == last.segment;
            if (reachedLast) fromLast += segment.durationSec;
        }
        return last.boardAt + (fromLast + Math.max(0, outerWalk - Math.max(0, route.walkToFirstStopSec))) * 1000L;
    }

    // null이면 마지막 편을 알 수 없음, MIN_VALUE이면 이 그룹에는 타고 갈 수 있는 편이 없음.
    static Long latestBoarding(List<RealtimeTransitApi.Arrival> rows, long now, long arriveBy) {
        TreeSet<Long> observed = new TreeSet<>();
        long end = Long.MAX_VALUE;
        long scheduledEnd = Long.MAX_VALUE;
        boolean lastWithoutEta = false;
        long latestKnownNonLast = Long.MIN_VALUE;
        for (RealtimeTransitApi.Arrival row : rows) {
            scheduledEnd = Math.min(scheduledEnd, lastServiceMillis(row.lastServiceTime, row.fetchedAtMillis));
            if (row.etaSeconds == null) {
                lastWithoutEta |= Boolean.TRUE.equals(row.lastVehicle);
                continue;
            }
            long at = row.fetchedAtMillis + row.etaSeconds * 1000L;
            observed.add(at);
            if (Boolean.TRUE.equals(row.lastVehicle)) end = Math.min(end, at);
            if (Boolean.FALSE.equals(row.lastVehicle)) {
                latestKnownNonLast = Math.max(latestKnownNonLast, at);
            }
        }
        long limit = Math.min(end, arriveBy);
        Long chosen = observed.floor(limit);
        if (!lastWithoutEta && observed.size() >= 2 && end == Long.MAX_VALUE
                && (scheduledEnd != Long.MAX_VALUE || latestKnownNonLast == observed.last())) {
            long latest = observed.last();
            long forecastLimit = Math.min(Math.min(limit, scheduledEnd), latest + MAX_FORECAST);
            long headway = latest - observed.lower(latest);
            if (headway > 0 && headway <= MAX_HEADWAY && forecastLimit > latest) {
                chosen = latest + (forecastLimit - latest) / headway * headway;
            }
        }
        if (chosen != null && chosen >= now) return chosen;
        return end != Long.MAX_VALUE || scheduledEnd < now || !observed.isEmpty() && observed.first() > arriveBy
                ? Long.MIN_VALUE : null;
    }

    private CompletableFuture<List<RealtimeTransitApi.Arrival>> arrivals(RouteResult.Segment segment,
            Map<String, CompletableFuture<List<RealtimeTransitApi.BusStop>>> stops,
            Map<String, CompletableFuture<List<RealtimeTransitApi.Arrival>>> stations) {
        if (segment.boardStopName == null || segment.boardStopName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        String name = segment.boardStopName.trim();
        if ("BUS".equals(segment.type) && api.hasBusKey()) {
            boolean hasPosition = segment.startLng != null && segment.startLat != null;
            String key = hasPosition ? name + "|" + segment.startLng + "|" + segment.startLat : name;
            return stops.computeIfAbsent(key, ignored -> (hasPosition
                            ? api.findNearbyBusStops(segment.startLng, segment.startLat)
                            : api.findBusStops(name)).exceptionally(error -> Collections.emptyList()))
                    .thenCompose(matches -> {
                        String arsId = matchingBusStop(segment, matches);
                        if (arsId == null) return CompletableFuture.completedFuture(Collections.emptyList());
                        synchronized (stations) {
                            return stations.computeIfAbsent("BUS|" + arsId,
                                    ignored -> api.getBusArrivals(arsId, null));
                        }
                    }).exceptionally(error -> Collections.emptyList());
        }
        if ("SUBWAY".equals(segment.type) && api.hasSubwayKey()) {
            synchronized (stations) {
                return stations.computeIfAbsent("SUBWAY|" + name, key ->
                        api.getSubwayArrivals(name, null, null).exceptionally(error -> Collections.emptyList()));
            }
        }
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    private static String matchingBusStop(RouteResult.Segment segment, List<RealtimeTransitApi.BusStop> stops) {
        List<RealtimeTransitApi.BusStop> matching = new ArrayList<>();
        for (RealtimeTransitApi.BusStop stop : stops) {
            if (normalized(stop.name).equals(normalized(segment.boardStopName))) matching.add(stop);
        }
        if (matching.size() == 1) return matching.get(0).arsId;
        if (segment.startLng == null || segment.startLat == null) return null;
        double closest = Double.POSITIVE_INFINITY;
        double second = Double.POSITIVE_INFINITY;
        String selected = null;
        for (RealtimeTransitApi.BusStop stop : matching) {
            if (!Double.isFinite(stop.longitude) || !Double.isFinite(stop.latitude)
                    || Math.abs(stop.longitude) > 180 || Math.abs(stop.latitude) > 90) return null;
            double distance = KakaoRouteParser.distanceM(segment.startLng, segment.startLat,
                    stop.longitude, stop.latitude);
            if (distance < closest) {
                second = closest;
                closest = distance;
                selected = stop.arsId;
            } else {
                second = Math.min(second, distance);
            }
        }
        return closest <= 100 && second - closest >= 10 ? selected : null;
    }

    private static boolean matchesSegment(RouteResult.Segment segment, RealtimeTransitApi.Arrival row) {
        for (int i = 0; i < segment.stopNames.size() - 1; i++) {
            if (!row.terminalName.isEmpty()
                    && stationName(row.terminalName).equals(stationName(segment.stopNames.get(i)))) return false;
        }
        if (row.mode == RealtimeTransitApi.Mode.BUS) {
            String actual = normalized(row.busRouteName);
            return "BUS".equals(segment.type) && !actual.isEmpty()
                    && segment.lineNames.stream().anyMatch(name -> normalized(name)
                            .replace("(심야)", "").equals(actual.replace("(심야)", "")));
        }
        if (!"SUBWAY".equals(segment.type) || segment.stopNames.size() < 2) return false;
        String expectedNext = stationName(segment.stopNames.get(1));
        String destination = row.destination;
        int separator = destination.lastIndexOf('-');
        if (separator < 0 || !destination.endsWith("방면")
                || expectedNext.isEmpty() || !expectedNext.equals(stationName(
                        destination.substring(separator + 1, destination.length() - 2)))) return false;
        if (!row.trainType.isEmpty() && !"일반".equals(row.trainType)) return false;
        return segment.lineNames.stream().anyMatch(name ->
                normalized(name).matches("[1-9]호선")
                        && ("100" + normalized(name).charAt(0)).equals(row.lineId));
    }

    private static String stationName(String name) {
        return normalized(name).replaceFirst("역$", "");
    }

    private static String normalized(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    static long lastServiceMillis(String value, long now) {
        if (value == null || !value.matches("(?:[0-9]{4}|[0-9]{6}|[0-9]{14})")) return Long.MAX_VALUE;
        String time = value.length() == 14 ? value.substring(8) : value;
        int hour = Integer.parseInt(time.substring(0, 2));
        int minute = Integer.parseInt(time.substring(2, 4));
        int second = time.length() == 6 ? Integer.parseInt(time.substring(4)) : 0;
        if (hour >= 30 || minute >= 60 || second >= 60 || value.matches("0+")) return Long.MAX_VALUE;
        Calendar local = Calendar.getInstance(SEOUL);
        local.setTimeInMillis(now);
        if (value.length() == 14) {
            local.set(Integer.parseInt(value.substring(0, 4)), Integer.parseInt(value.substring(4, 6)) - 1,
                    Integer.parseInt(value.substring(6, 8)));
        }
        local.set(Calendar.HOUR_OF_DAY, hour % 24);
        local.set(Calendar.MINUTE, minute);
        local.set(Calendar.SECOND, second);
        local.set(Calendar.MILLISECOND, 0);
        if (hour >= 24) local.add(Calendar.DATE, 1);
        long at = local.getTimeInMillis();
        if (value.length() == 14) return at;
        long halfDay = TimeUnit.HOURS.toMillis(12);
        long day = TimeUnit.DAYS.toMillis(1);
        if (at - now > halfDay) return at - day;
        if (now - at > halfDay) return at + day;
        return at;
    }
}
