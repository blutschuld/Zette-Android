package kr.ac.ssu.zette.dto;

import java.time.LocalDate;
import java.time.LocalTime;

// 일정 데이터 클래스
public class Schedule {
    public long id;
    public String name;
    public LocalDate date;
    public LocalTime arrivalTime;
    public Place place;
    public RouteType routeType; // 최소 시간 / 최소 도보 / 최소 환승
    public int arrivalBuffer; // 도착 여유 (분)

    public Schedule() {}

    public Schedule(long id, String name, LocalDate date, LocalTime arrivalTime, Place place,
                    RouteType routeType, int arrivalBuffer) {
        this.id = id;
        this.name = name;
        this.date = date;
        this.arrivalTime = arrivalTime;
        this.place = place;
        this.routeType = routeType;
        this.arrivalBuffer = arrivalBuffer;
    }

    public enum RouteType { // 최소 시간 / 최소 도보 / 최소 환승
        MinTime, MinWalk, MinTransfer
    }
}

