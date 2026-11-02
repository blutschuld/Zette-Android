package kr.ac.ssu.zette.dto;

import androidx.annotation.Nullable;

// 장소 데이터 클래스
public class Place {
    public String id;
    public String name;
    public String address;
    public double lat;
    public double lng;  
    @Nullable
    public Integer distance; // 검색 화면용

    public Place() {}

    public Place(String id, String name, String address, double lat, double lng, @Nullable Integer distance) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.distance = distance;
    }
}
