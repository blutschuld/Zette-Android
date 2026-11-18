package kr.ac.ssu.zette;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

//경도 위도를 반환하거나, 오류값 반환
//api 24버전까지 만들것!
public final class GpsReader {

    //성공 or 에러코드
    public static final int SUCCESS = 0;
    public static final int ERROR_PERMISSION = 1;
    // 기존 오류 코드 이름을 유지한다. 지원되는 위치 공급자가 모두 꺼진 경우에도 반환한다.
    public static final int ERROR_GPS_DISABLED = 2;
    public static final int ERROR_TIMEOUT = 3;
    public static final int ERROR_NO_LOCATION = 4;
    public static final int ERROR_INTERRUPTED = 5;
    public static final int ERROR_UNAVAILABLE = 6;

    //퍼미션 확인을 위한 컨텍스트, 로케이션 라이브러리의 매니저 받아오기
    private final Context context;
    private final LocationManager locationManager;

    public GpsReader(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager)
                this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public static final class Result {

        public final int code;

        // 성공 시 위치 정보, 실패 시 null
        public final Location location;

        private Result(int code, Location location) {
            this.code = code;
            this.location = location;
        }

        public boolean isSuccess() {
            return code == SUCCESS;
        }
    }

    // @param timeoutMillis 최대 대기 시간(ms)

    public Result read(long timeoutMillis) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w("GpsReader", "GpsReader.read()는 보조 스레드에서 호출해야 합니다.");
            return new Result(ERROR_UNAVAILABLE, null);
        }

        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis는 0보다 커야 합니다.");
        }

        if (locationManager == null) {
            return new Result(ERROR_UNAVAILABLE, null);
        }

        boolean hasFineLocation = context.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarseLocation = context.checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasFineLocation && !hasCoarseLocation) {
            return new Result(ERROR_PERMISSION, null);
        }

        try {
            List<String> providers = locationManager.getAllProviders();
            String provider = null;
            // 정확한 위치가 허용된 경우에만 GPS를 우선한다.
            if (hasFineLocation && isEnabled(providers, LocationManager.GPS_PROVIDER)) {
                provider = LocationManager.GPS_PROVIDER;
            } else if (isEnabled(providers, LocationManager.NETWORK_PROVIDER)) {
                provider = LocationManager.NETWORK_PROVIDER;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && isEnabled(providers, LocationManager.FUSED_PROVIDER)) {
                provider = LocationManager.FUSED_PROVIDER;
            }

            if (provider == null) {
                boolean hasSupportedProvider =
                        (hasFineLocation && providers.contains(LocationManager.GPS_PROVIDER))
                                || providers.contains(LocationManager.NETWORK_PROVIDER)
                                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                    && providers.contains(LocationManager.FUSED_PROVIDER));
                return new Result(
                        hasSupportedProvider ? ERROR_GPS_DISABLED : ERROR_UNAVAILABLE, null);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return readCurrent(provider, timeoutMillis);
            }
            return readLegacy(provider, timeoutMillis);
        } catch (SecurityException e) {
            return new Result(ERROR_PERMISSION, null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new Result(ERROR_UNAVAILABLE, null);
        }
    }

    private boolean isEnabled(List<String> providers, String provider) {
        return providers.contains(provider) && locationManager.isProviderEnabled(provider);
    }

    private Result readCurrent(String provider, long timeoutMillis) {
        CancellationSignal cancellation = new CancellationSignal();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Location> received = new AtomicReference<>();

        try {
            locationManager.getCurrentLocation(
                    provider,
                    cancellation,
                    Runnable::run,
                    location -> {
                        received.set(location);
                        completed.countDown();
                    }
            );

            if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return new Result(ERROR_TIMEOUT, null);
            }

            Location location = received.get();

            if (location == null) {
                return new Result(ERROR_NO_LOCATION, null);
            }

            return new Result(SUCCESS, new Location(location));

        } catch (SecurityException e) {
            return new Result(ERROR_PERMISSION, null);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(ERROR_INTERRUPTED, null);

        } catch (IllegalArgumentException | IllegalStateException e) {
            return new Result(ERROR_UNAVAILABLE, null);

        } finally {
            // 시간 초과나 인터럽트가 발생해도 위치 요청을 정리합니다.
            cancellation.cancel();
        }
    }

    // API 24~29에서는 기존 위치 콜백으로 한 번만 읽는다.
    private Result readLegacy(String provider, long timeoutMillis) {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Result> received = new AtomicReference<>();
        LocationListener listener = new LocationListener() {
            private void finish(Result result) {
                if (received.compareAndSet(null, result)) {
                    completed.countDown();
                }
            }

            @Override
            public void onLocationChanged(Location location) {
                if (location == null) {
                    finish(new Result(ERROR_NO_LOCATION, null));
                } else {
                    finish(new Result(SUCCESS, new Location(location)));
                }
            }

            @Override
            public void onProviderDisabled(String provider) {
                finish(new Result(ERROR_GPS_DISABLED, null));
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }
        };
        boolean registered = false;

        try {
            locationManager.requestLocationUpdates(
                    provider, 0L, 0f, listener, Looper.getMainLooper()
            );
            registered = true;

            if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return new Result(ERROR_TIMEOUT, null);
            }
            return received.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(ERROR_INTERRUPTED, null);
        } finally {
            if (registered) {
                locationManager.removeUpdates(listener);
            }
        }
    }
}
