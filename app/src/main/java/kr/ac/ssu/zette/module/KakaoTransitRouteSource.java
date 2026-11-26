package kr.ac.ssu.zette;

import kr.ac.ssu.zette.route.KakaoRouteRepository;
import kr.ac.ssu.zette.route.RouteCallback;
import kr.ac.ssu.zette.route.RouteResult;
import kr.ac.ssu.zette.route.model.PlaceSearchResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class KakaoTransitRouteSource extends TransitRouteSource {
    private final KakaoRouteRepository repository;

    KakaoTransitRouteSource() {
        this(new KakaoRouteRepository());
    }

    KakaoTransitRouteSource(KakaoRouteRepository repository) {
        this.repository = repository;
    }

    @Override
    protected boolean supportsDepartureTime() {
        return false;
    }

    @Override
    protected CompletableFuture<List<Place>> searchPlace(
            String keyword, double originLng, double originLat) {
        CompletableFuture<List<Place>> places = new CompletableFuture<>();
        repository.searchPlace(keyword, originLng, originLat, new KakaoRouteRepository.PlaceCallback() {
            @Override
            public void onSuccess(PlaceSearchResponse response) {
                List<Place> candidates = new ArrayList<>();
                try {
                    if (response.documents != null) {
                        for (PlaceSearchResponse.Document document : response.documents) {
                            candidates.add(new Place(document.lng(), document.lat()));
                        }
                    }
                    places.complete(candidates);
                } catch (RuntimeException error) {
                    places.completeExceptionally(error);
                }
            }

            @Override
            public void onError(String message) {
                places.completeExceptionally(new IllegalStateException(message));
            }
        });
        return places;
    }

    @Override
    protected CompletableFuture<CheckerThread.RouteResult> getTransitRoute(
            double originLng, double originLat, double destinationLng, double destinationLat) {
        CompletableFuture<CheckerThread.RouteResult> route = new CompletableFuture<>();
        // 조회 시각을 기준으로 예상 도착 시각까지 계산한다.
        repository.getRoute("TRANSIT", originLng, originLat, destinationLng, destinationLat,
                System.currentTimeMillis(), null,
                new RouteCallback() {
                    @Override
                    public void onSuccess(RouteResult result) {
                        try {
                            String label = result.firstLineNames == null || result.firstLineNames.isEmpty()
                                    ? result.firstVehicleType : String.join(", ", result.firstLineNames);
                            InfoThread.Vehicle firstVehicle = label == null
                                    ? null : new InfoThread.Vehicle(label, 0L, 0L);
                            int walkSeconds = result.firstBoardStopName == null
                                    ? -1 : result.walkToFirstStopSec;
                            InfoThread.TransitDetails transit = new InfoThread.TransitDetails(
                                    result.firstBoardStopName, walkSeconds, firstVehicle, null);
                            route.complete(CheckerThread.RouteResult.estimate(
                                    result.totalTimeSec, transit, result.arrivalTimeMillis,
                                    result));
                        } catch (RuntimeException error) {
                            route.completeExceptionally(error);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        route.completeExceptionally(new IllegalStateException(message));
                    }
                });
        return route;
    }
}
