#manifest 수정
1)manifest 수정
AndroidManifest.xml에서 <manifest> 안, <application> 바깥
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<!-- <application> 안 -->
<service
    android:name="kr.ac.ssu.zette.MyServiceTemplate"
    android:exported="false"
    android:foregroundServiceType="location"
    android:stopWithTask="false" />

<receiver
    android:name="kr.ac.ssu.zette.BootReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>

#GpsReader.java 사용법

1)호츌
GpsReader gpsReader = new GpsReader(getApplicationContext());

// 최대 15초 대기; timeout 설정
GpsReader.Result result = gpsReader.read(15_000L);

if (result.isSuccess()) {
    double latitude = result.location.getLatitude();
    double longitude = result.location.getLongitude();
    float accuracy = result.location.getAccuracy();
} else {
    int errorCode = result.code;
    // 에러코드에 따른 처리
}

2)에러코드
    public static final int SUCCESS = 0;
    public static final int ERROR_PERMISSION = 1;
    public static final int ERROR_GPS_DISABLED = 2;
    public static final int ERROR_TIMEOUT = 3;
    public static final int ERROR_NO_LOCATION = 4;
    public static final int ERROR_INTERRUPTED = 5;
    public static final int ERROR_UNAVAILABLE = 6;

