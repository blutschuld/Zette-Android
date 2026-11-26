package kr.ac.ssu.zette;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.CalendarContract;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyService extends Service {
    public static final String ACTION_REQUEST_INFO = "kr.ac.ssu.zette.action.REQUEST_INFO";
    public static final String ACTION_REQUEST_CURRENT_EVENT_INFO =
            "kr.ac.ssu.zette.action.REQUEST_CURRENT_EVENT_INFO";
    public static final String EXTRA_EVENT_ID = "kr.ac.ssu.zette.extra.EVENT_ID";
    public static final String EXTRA_INFO_RECEIVER = "kr.ac.ssu.zette.extra.INFO_RECEIVER";

    private static final String TAG = "MyService";
    private static final String CHANNEL_ID = "my_service";
    private static final int NOTIFICATION_ID = 1001;
    private static final long CHECK_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private ExecutorService worker;
    private ExecutorService infoWorker;
    private ExecutorService realtimeWorker;
    private GpsReader gpsReader;
    private CheckerThread.RouteSource infoRoutes;
    private CheckerThread checker;
    // 확인 요청과 완료 상태는 메인 스레드에서 관리한다.
    private final Handler checkerHandler = new Handler(Looper.getMainLooper());
    private final Runnable checkRequest = this::requestCheck;
    private ContentObserver calendarObserver;
    private boolean checkerStarted;
    private boolean checkRunning;
    private boolean checkRequested;

    @Override
    public void onCreate() {
        super.onCreate();
        worker = Executors.newSingleThreadExecutor();
        infoWorker = Executors.newSingleThreadExecutor();
        gpsReader = new GpsReader(getApplicationContext());
        infoRoutes = createRouteSource();
        String busKey = optionalString("zette_realtime_bus_key");
        String subwayKey = optionalString("zette_realtime_subway_key");
        RealtimeTransitApi realtime = null;
        if (!busKey.isEmpty() || !subwayKey.isEmpty()) {
            realtimeWorker = new ThreadPoolExecutor(6, 6, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(48));
            realtime = new RealtimeTransitApi(busKey, subwayKey, realtimeWorker);
        }
        checker = new CheckerThread(getApplicationContext(), gpsReader, createRouteSource(), realtime);
    }

    private String optionalString(String name) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        return id == 0 ? "" : getString(id).trim();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean currentRequest = intent != null && ACTION_REQUEST_CURRENT_EVENT_INFO.equals(intent.getAction());
        ResultReceiver infoReceiver = intent != null && (currentRequest || ACTION_REQUEST_INFO.equals(intent.getAction()))
                ? intent.getParcelableExtra(EXTRA_INFO_RECEIVER) : null;
        String eventId = infoReceiver == null ? null : intent.getStringExtra(EXTRA_EVENT_ID);

        boolean hasFineLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarseLocation = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!hasFineLocation && !hasCoarseLocation) {
            Log.w(TAG, "위치 권한이 없어 서비스를 종료합니다.");
            if (currentRequest && infoReceiver != null) {
                InfoThread.currentEventInfo(checker, infoReceiver);
            } else if (infoReceiver != null) {
                InfoThread.sendError(infoReceiver, InfoThread.LOCATION_UNAVAILABLE, eventId);
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, createNotification());

        if (!checkerStarted) {
            checkerStarted = true;
            requestCheck();
        }

        if (currentRequest && infoReceiver != null) {
            InfoThread.currentEventInfo(checker, infoReceiver);
        } else if (infoReceiver != null) {
            infoWorker.execute(new InfoThread(getApplicationContext(), gpsReader,
                    infoRoutes, eventId, infoReceiver));
        }

        return START_STICKY;
    }

    /** Activity는 요청마다 주 스레드 Handler를 사용하는 ResultReceiver를 전달한다. */
    public static Intent createInfoRequest(Context context, String eventId, ResultReceiver receiver) {
        return new Intent(context, MyService.class).setAction(ACTION_REQUEST_INFO)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_INFO_RECEIVER, receiver);
    }

    /** 현재 추적 중인 일정 정보를 요청한다. 응답에는 새 GPS·경로 조회를 하지 않는다. */
    public static Intent currentEventInfo(Context context, ResultReceiver receiver) {
        return new Intent(context, MyService.class).setAction(ACTION_REQUEST_CURRENT_EVENT_INFO)
                .putExtra(EXTRA_INFO_RECEIVER, receiver);
    }

    private void requestCheck() {
        if (!checkerStarted) return;
        if (checkRunning) {
            checkRequested = true;
            return;
        }
        observeCalendar();
        checkRunning = true;
        checkerHandler.removeCallbacks(checkRequest);
        checkerHandler.postDelayed(checkRequest, CHECK_INTERVAL_MILLIS);
        worker.execute(() -> {
            try {
                checker.run();
            } finally {
                checkerHandler.post(this::onCheckComplete);
            }
        });
    }

    private void onCheckComplete() {
        checkRunning = false;
        if (checkRequested) {
            checkRequested = false;
            requestCheck();
        }
    }

    private void observeCalendar() {
        if (calendarObserver != null || checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ContentObserver observer = new ContentObserver(checkerHandler) {
            @Override
            public void onChange(boolean selfChange) {
                checker.refreshCallers();
                requestCheck();
            }
        };
        try {
            getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true, observer);
            calendarObserver = observer;
        } catch (SecurityException e) {
            Log.w(TAG, "캘린더 변경 감시를 등록할 수 없습니다.", e);
        }
    }

    protected CheckerThread.RouteSource createRouteSource() {
        return new KakaoTransitRouteSource();
    }

    @Override
    public void onDestroy() {
        checkerStarted = false;
        checkerHandler.removeCallbacks(checkRequest);
        if (calendarObserver != null) {
            getContentResolver().unregisterContentObserver(calendarObserver);
            calendarObserver = null;
        }
        // 진행 중인 위치 조회를 인터럽트하면 GpsReader가 위치 요청을 정리한다.
        checker.close();
        worker.shutdownNow();
        infoWorker.shutdownNow();
        if (realtimeWorker != null) realtimeWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "서비스 실행 상태", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("서비스 실행 중")
                .setOngoing(true)
                .build();
    }
}
