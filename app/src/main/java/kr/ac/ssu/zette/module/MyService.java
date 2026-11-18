package kr.ac.ssu.zette;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Manifest에 .MyService을 등록하고 stopWithTask="false"를 설정한다.
 * FOREGROUND_SERVICE 권한과 실제 작업에 맞는 서비스 유형 및 권한도 선언한다.
 * 화면에 표시된 Activity에서 API 26 이상은 startForegroundService(),
 * API 25 이하는 startService()로 시작한다.
 */

public class MyService extends Service {
    private static final String CHANNEL_ID = "my_service";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification());

        //업데이트 빈도 컨트롤러 변수
        int updateController;

        //GpsReader 객체 생성
        GpsReader gpsReader = new GpsReader(getApplicationContext());

    /**
     * thred 1
     *
     *          //가장 임박한 일정 픽커
     *
     *         //위치 확인 및 타임 체커*/
        Runnable checkerTread = () -> {
            doWork();        // 오래 걸리는 작업을 백그라운드에서 실행

        };

        new Thread(checkerTread).start();

    /**
     * thred 2
     *
     *         //알람 보내기*/
        Runnable callerTread = () -> {
            doWork();        // 오래 걸리는 작업을 백그라운드에서 실행

        };

        new Thread(callerTread).start();

    /**
     * thred 3
     *
     *         //intent리시버? 여튼 ui 통신 액티비티에서 내뱉으면 정보 주면 됨
     *         //일정 전달 받으면 출발 시간 몇분 남았는지 보내는 함수
     *         //타야하는 버스(대중교통) 언제 출발하는지 알려주는 함수*/
        Runnable infoThread = () -> {
            doWork();        // 오래 걸리는 작업을 백그라운드에서 실행
        };

        new Thread(infoThread).start();


        return START_NOT_STICKY;
    }

    //여기서 thread3 관련 정보 연결 할 것
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    //실행 상태 알림 등록
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
