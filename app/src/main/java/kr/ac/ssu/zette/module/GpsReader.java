package com.example.myapplication;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.os.Looper;

import androidx.annotation.RequiresApi;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

//경도 위도를 반환하거나, 오류값 반환
//api 24버전까지 만들것!
@RequiresApi(30)
public final class GpsReader {

    //성공 or 에러코드
    public static final int SUCCESS = 0;
    public static final int ERROR_PERMISSION = 1;
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
        //시스템 상태 확인
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "GpsReader.read()는 보조 스레드에서 호출해야 합니다."
            );
        }

        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "timeoutMillis는 0보다 커야 합니다."
            );
        }

        if (locationManager == null) {
            return new Result(ERROR_UNAVAILABLE, null);
        }

        //권한 확인
        if (context.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {
            return new Result(ERROR_PERMISSION, null);
        }

        CancellationSignal cancellation = new CancellationSignal();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Location> received = new AtomicReference<>();

        //에러 코드를 받거나 로케이션 값 받기
        try {
            if (!locationManager.getAllProviders().contains(
                    LocationManager.GPS_PROVIDER)) {
                return new Result(ERROR_UNAVAILABLE, null);
            }

            if (!locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER)) {
                return new Result(ERROR_GPS_DISABLED, null);
            }

            locationManager.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
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
}
