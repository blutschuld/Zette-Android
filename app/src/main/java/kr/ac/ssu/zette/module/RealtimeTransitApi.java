package kr.ac.ssu.zette;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 서울시 버스 정류소·지하철역의 실시간 도착 조회. 경로 조회 없이 사용할 수 있다. */
public final class RealtimeTransitApi {
    private static final String BUS_URL = "http://ws.bus.go.kr/api/rest/stationinfo/";
    private static final String SUBWAY_URL = "http://swopenapi.seoul.go.kr/api/subway/";
    private static final Pattern BUS_DURATION = Pattern.compile(
            "^(?:(\\d{1,4})분\\s*(?:(\\d{1,2})초)?|(\\d{1,4})초)\\s*후");
    private static final int TIMEOUT_MS = 8000;

    public enum Mode { BUS, SUBWAY }

    public static final class BusStop {
        public final String arsId;
        public final String name;
        public final double longitude; // 좌표 미제공 시 NaN
        public final double latitude;

        private BusStop(String arsId, String name, double longitude, double latitude) {
            this.arsId = arsId;
            this.name = name;
            this.longitude = longitude;
            this.latitude = latitude;
        }
    }

    public static final class Arrival {
        public final Mode mode;
        public final String stopName;
        /** 버스 노선 ID 또는 지하철 호선 ID. */
        public final String lineId;
        /** 버스 노선명. 지하철은 lineId를 사용한다. */
        public final String busRouteName;
        public final String direction;
        public final String destination;
        public final String vehicleId;
        public final String trainType;
        public final String message;
        /** 응답을 받은 시각 기준 도착까지 남은 초. 제공되지 않거나 예측이 지난 경우 null. */
        public final Integer etaSeconds;
        public final long fetchedAtMillis;
        /** null이면 제공사가 막차 여부를 주지 않았다. */
        public final Boolean lastVehicle;
        public final String lastServiceTime;
        public final String terminalName;

        private Arrival(Mode mode, String stopName, String lineId, String busRouteName,
                        String direction, String destination, String vehicleId, String trainType,
                        String message, Integer etaSeconds, long fetchedAtMillis,
                        Boolean lastVehicle, String lastServiceTime, String terminalName) {
            this.mode = mode;
            this.stopName = stopName;
            this.lineId = lineId;
            this.busRouteName = busRouteName;
            this.direction = direction;
            this.destination = destination;
            this.vehicleId = vehicleId;
            this.trainType = trainType;
            this.message = message;
            this.etaSeconds = etaSeconds;
            this.fetchedAtMillis = fetchedAtMillis;
            this.lastVehicle = lastVehicle;
            this.lastServiceTime = lastServiceTime;
            this.terminalName = terminalName;
        }
    }

    private final String busKey;
    private final String subwayKey;
    private final Executor networkExecutor;

    /**
     * 키는 URL 인코딩 전의 값을 전달한다. 한 수단만 사용하면 다른 키는 null이어도 된다.
     * networkExecutor에는 Android UI 스레드에서 실행하지 않는 작업 실행기를 전달한다.
     */
    public RealtimeTransitApi(String busKey, String subwayKey, Executor networkExecutor) {
        this.busKey = busKey;
        this.subwayKey = subwayKey;
        this.networkExecutor = Objects.requireNonNull(networkExecutor);
    }

    boolean hasBusKey() {
        return busKey != null && !busKey.trim().isEmpty();
    }

    boolean hasSubwayKey() {
        return subwayKey != null && !subwayKey.trim().isEmpty();
    }

    CompletableFuture<List<BusStop>> findNearbyBusStops(double longitude, double latitude) {
        return request(() -> {
            String key = required(busKey, "서울시 버스 서비스키");
            JSONObject response = getJson(BUS_URL + "getStationByPos?ServiceKey=" + encode(key)
                    + "&tmX=" + longitude + "&tmY=" + latitude + "&radius=100&resultType=json");
            JSONArray rows = busRows(response);
            List<BusStop> stops = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String arsId = text(row, "arsId");
                if (arsId.matches("[0-9]{5}") && !"00000".equals(arsId)) {
                    String name = text(row, "stnNm");
                    if (name.isEmpty()) name = text(row, "stationNm");
                    stops.add(new BusStop(arsId, name, row.optDouble("gpsX"),
                            row.optDouble("gpsY")));
                }
            }
            return Collections.unmodifiableList(stops);
        });
    }

    /** 맞은편의 같은 이름 정류소도 후보로 반환한다. 선택한 정류소의 ARS 번호로 도착을 조회한다. */
    public CompletableFuture<List<BusStop>> findBusStops(String keyword) {
        String name = required(keyword, "정류소 이름");
        return request(() -> {
            JSONArray rows = busRows(getBus("getStationByName", "stSrch", name));
            List<BusStop> stops = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String arsId = text(row, "arsId");
                if (arsId.matches("[0-9]{5}") && !"00000".equals(arsId)) {
                    stops.add(new BusStop(arsId, text(row, "stNm"), row.optDouble("posX"),
                            row.optDouble("posY")));
                }
            }
            return Collections.unmodifiableList(stops);
        });
    }

    /** 정류소별 도착 예정 버스. routeName을 null로 넘기면 모든 노선을 조회한다. */
    public CompletableFuture<List<Arrival>> getBusArrivals(String arsId, String routeName) {
        String stop = required(arsId, "정류소 ARS 번호").replace("-", "");
        if (!stop.matches("[0-9]{5}") || "00000".equals(stop)) {
            throw new IllegalArgumentException("정류소 ARS 번호는 숫자 5자리입니다.");
        }
        return request(() -> parseBusArrivals(getBus("getStationByUid", "arsId", stop),
                routeName, System.currentTimeMillis()));
    }

    /**
     * 서울시 API에 등록된 역 이름으로 조회한다(예: 강남).
     * lineId(예: 1007), direction(예: 상행, 내선)은 각각 null이면 전체를 조회한다.
     */
    public CompletableFuture<List<Arrival>> getSubwayArrivals(
            String stationName, String lineId, String direction) {
        String station = required(stationName, "지하철역 이름");
        return request(() -> {
            String key = required(subwayKey, "서울시 실시간 지하철 인증키");
            JSONObject response = getJson(SUBWAY_URL + encode(key)
                    + "/json/realtimeStationArrival/1/100/" + encode(station));
            return parseSubwayArrivals(response, lineId, direction, System.currentTimeMillis());
        });
    }

    static List<Arrival> parseBusArrivals(JSONObject response, String routeName, long now)
            throws IOException, JSONException {
        JSONArray rows = busRows(response);
        List<Arrival> arrivals = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String route = text(row, "rtNm");
            if (!matches(routeName, route)) continue;
            for (int order = 1; order <= 2; order++) {
                String vehicleId = text(row, "vehId" + order);
                String message = text(row, "arrmsgSec" + order);
                Integer eta = busEta(message);
                String fallback = text(row, "arrmsg" + order);
                if (eta == null && !fallback.isEmpty()) {
                    message = fallback;
                    eta = busEta(message);
                }
                Boolean last = lastVehicle(text(row, "isLast" + order));
                String lastTime = text(row, "lastTm");
                if (eta == null && !vehicleId.matches("[1-9][0-9]*")
                        && !Boolean.TRUE.equals(last) && (order != 1 || lastTime.isEmpty())) continue;
                arrivals.add(new Arrival(Mode.BUS, text(row, "stNm"), text(row, "busRouteId"),
                        route, text(row, "adirection"), text(row, "stationNm" + order),
                        vehicleId, "", message, eta, now, last, lastTime,
                        text(row, "stationNm" + order)));
            }
        }
        return Collections.unmodifiableList(arrivals);
    }

    static List<Arrival> parseSubwayArrivals(JSONObject response, String lineId, String direction,
                                             long now) throws IOException, JSONException {
        JSONObject status = response.optJSONObject("errorMessage");
        String code = status == null ? text(response, "code") : text(status, "code");
        if ("INFO-200".equals(code)) return Collections.emptyList();
        if (!"INFO-000".equals(code)) throw providerError("지하철", code);

        JSONArray rows = rows(response, "realtimeArrivalList");
        List<Arrival> arrivals = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String line = text(row, "subwayId");
            String way = text(row, "updnLine");
            if (!matches(lineId, line) || !matches(direction, way)) continue;

            int arrivalCode = row.optInt("arvlCd", -1);
            if (arrivalCode == 2) continue; // 조회한 역에서 이미 출발한 열차
            String message = text(row, "arvlMsg2");
            if (message.isEmpty()) message = text(row, "arvlMsg3");
            arrivals.add(new Arrival(Mode.SUBWAY, text(row, "statnNm"), line, "",
                    way, text(row, "trainLineNm"), text(row, "btrainNo"),
                    text(row, "btrainSttus"), message, subwayEta(row, arrivalCode, now), now,
                    lastVehicle(text(row, "lstcarAt")), "", text(row, "bstatnNm")));
        }
        return Collections.unmodifiableList(arrivals);
    }

    private static Integer busEta(String message) {
        if (message.startsWith("곧 도착") || message.startsWith("곧도착")) return 0;
        Matcher matcher = BUS_DURATION.matcher(message);
        if (!matcher.find()) return null;
        if (matcher.group(3) != null) return Integer.parseInt(matcher.group(3));
        int seconds = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        return Integer.parseInt(matcher.group(1)) * 60 + seconds;
    }

    private static Boolean lastVehicle(String value) {
        if ("1".equals(value) || "Y".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("0".equals(value) || "N".equalsIgnoreCase(value)) return Boolean.FALSE;
        return null;
    }

    private static Integer subwayEta(JSONObject row, int arrivalCode, long now) {
        int seconds = row.optInt("barvlDt", -1);
        if (seconds <= 0) return arrivalCode == 0 || arrivalCode == 1 ? 0 : null;
        String timestamp = text(row, "recptnDt");
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        parser.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        parser.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date generated = parser.parse(timestamp, position);
        if (generated == null || position.getIndex() != timestamp.length()) return seconds;
        long elapsed = Math.max(0, (now - generated.getTime()) / 1000L);
        return elapsed > seconds ? null : seconds - (int) elapsed;
    }

    private JSONObject getBus(String operation, String parameter, String value)
            throws IOException, JSONException {
        String key = required(busKey, "서울시 버스 서비스키");
        return getJson(BUS_URL + operation + "?ServiceKey=" + encode(key)
                + "&" + parameter + "=" + encode(value) + "&resultType=json");
    }

    private static JSONArray busRows(JSONObject response) throws IOException, JSONException {
        String code = text(response.getJSONObject("msgHeader"), "headerCd");
        if ("4".equals(code)) return new JSONArray(); // 결과 없음
        if (!"0".equals(code)) throw providerError("버스", code);
        JSONObject body = response.optJSONObject("msgBody");
        return body == null ? new JSONArray() : rows(body, "itemList");
    }

    private static JSONArray rows(JSONObject parent, String key) throws JSONException {
        return parent.isNull(key) ? new JSONArray() : parent.getJSONArray(key);
    }

    private static JSONObject getJson(String url) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("실시간 교통 조회 HTTP " + status);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                return new JSONObject(body.toString());
            }
        } finally {
            connection.disconnect();
        }
    }

    private interface Request<T> {
        T run() throws IOException, JSONException;
    }

    private <T> CompletableFuture<T> request(Request<T> request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return request.run();
            } catch (IOException error) {
                throw new CompletionException(error);
            } catch (JSONException error) {
                throw new CompletionException(new IOException("실시간 교통 응답 형식을 읽지 못했습니다."));
            }
        }, networkExecutor);
    }

    private static String text(JSONObject row, String key) {
        return row.isNull(key) ? "" : row.optString(key, "").trim();
    }

    private static boolean matches(String filter, String actual) {
        return filter == null || filter.trim().equalsIgnoreCase(actual);
    }

    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " 값이 필요합니다.");
        }
        return value.trim();
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static IOException providerError(String provider, String code) {
        String safeCode = code.matches("[A-Z0-9-]{1,16}") ? code : "UNKNOWN";
        return new IOException("서울시 " + provider + " 조회 오류 (" + safeCode + ")");
    }
}
