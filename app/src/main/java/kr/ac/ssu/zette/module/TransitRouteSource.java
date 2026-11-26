package kr.ac.ssu.zette;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 팀의 장소 검색과 대중교통 API를 연결하는 Java 클래스. */
public class TransitRouteSource implements CheckerThread.RouteSource {
    private static final int DEPARTURE_SAMPLES = 5;

    public static final class Place {
        public final double longitude;
        public final double latitude;

        public Place(double longitude, double latitude) {
            this.longitude = longitude;
            this.latitude = latitude;
        }
    }

    @Override
    public final CompletableFuture<CheckerThread.RouteResult> find(
            double originLng, double originLat, String destination,
            long earliestDepartureMillis, long arriveByMillis) {
        return searchPlace(destination, originLng, originLat).thenCompose(documents -> {
            if (documents == null || documents.isEmpty()) {
                throw new IllegalStateException("일정 장소의 검색 결과가 없습니다.");
            }
            Place first = documents.get(0);
            if (supportsDepartureTime()) {
                return sampleRoutes(originLng, originLat, first, earliestDepartureMillis, arriveByMillis);
            } else {
                try {
                    return getTransitRoute(originLng, originLat, first.longitude, first.latitude);
                } catch (RuntimeException error) {
                    return failedRoute(error);
                }
            }
        });
    }

    private CompletableFuture<CheckerThread.RouteResult> sampleRoutes(double originLng,
            double originLat, Place destination, long earliest, long arriveBy) {
        if (earliest >= arriveBy) {
            return CompletableFuture.completedFuture(CheckerThread.RouteResult.scheduled(new ArrayList<>()));
        }

        List<CompletableFuture<CheckerThread.RouteResult>> requests = new ArrayList<>();
        long span = arriveBy - earliest - 1L;
        for (int sample = 0; sample < DEPARTURE_SAMPLES; sample++) {
            long offset = span * sample / DEPARTURE_SAMPLES;
            if (sample > 0 && offset == span * (sample - 1) / DEPARTURE_SAMPLES) continue;
            requests.add(requestRoute(originLng, originLat, destination, earliest + offset, arriveBy));
        }
        return CompletableFuture.allOf(requests.toArray(new CompletableFuture<?>[0]))
                .thenApply(ignored -> {
                    List<CheckerThread.Trip> trips = new ArrayList<>();
                    CheckerThread.RouteResult estimate = null;
                    boolean hasSchedule = false;
                    for (CompletableFuture<CheckerThread.RouteResult> request : requests) {
                        CheckerThread.RouteResult result = request.join();
                        if (result == null) continue;
                        if (result.trips != null) {
                            hasSchedule = true;
                            trips.addAll(result.trips);
                        } else if (estimate == null) {
                            estimate = result;
                        }
                    }
                    if (hasSchedule) return CheckerThread.RouteResult.scheduled(trips);
                    if (estimate != null) return estimate;
                    throw new IllegalStateException("대중교통 경로 조회 실패");
                });
    }

    private CompletableFuture<CheckerThread.RouteResult> requestRoute(double originLng,
            double originLat, Place destination, long departure, long arriveBy) {
        try {
            return getTransitRoute(originLng, originLat, destination.longitude, destination.latitude,
                    departure, arriveBy).exceptionally(error -> null);
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Override to return false when the team API accepts only the four coordinates. */
    protected boolean supportsDepartureTime() {
        return true;
    }

    /** Override for an API that accepts origin and destination coordinates only. */
    protected CompletableFuture<CheckerThread.RouteResult> getTransitRoute(
            double originLng, double originLat, double destinationLng, double destinationLat) {
        return unavailable("getTransitRoute");
    }

    /**
     * 오버라이드에서 searchPlace 콜백을 Future로 전달한다.
     * 성공 시 검색 결과의 순서를 유지하고, 오류 시 completeExceptionally를 호출한다.
     */
    protected CompletableFuture<List<Place>> searchPlace(
            String keyword, double originLng, double originLat) {
        return unavailable("searchPlace");
    }

    /**
     * 오버라이드에서 getRoute("TRANSIT", ...) 콜백을 Future로 전달한다.
     * 이동 시간만 있으면 RouteResult.estimate(totalTimeSec)를 반환한다.
     * 시간표 조회가 가능하면 요청한 출발 기준 시각 이후에 출발하는 API 응답을
     * RouteResult.scheduled(trips)로 반환한다. 이 메서드는 다른 출발 기준 시각으로 동시에 호출될 수 있다.
     * UI에 필요한 정류장·도보·실시간 도착 정보는 Trip 또는 estimate의 TransitDetails에 담는다.
     */
    protected CompletableFuture<CheckerThread.RouteResult> getTransitRoute(
            double originLng, double originLat, double destinationLng, double destinationLat,
            long earliestDepartureMillis, long arriveByMillis) {
        return unavailable("getTransitRoute");
    }

    private static <T> CompletableFuture<T> unavailable(String method) {
        return failedRoute(new UnsupportedOperationException(method + " 연결 필요"));
    }

    private static <T> CompletableFuture<T> failedRoute(RuntimeException error) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(error);
        return result;
    }
}
