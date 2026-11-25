package com.wardrobe.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherVo {
    private String city;
    private int temp;
    private Integer apparentTemp;
    private Integer humidity;
    private Double windSpeed;
    private String condition;
    private String icon;
    private String tip;
    private String seasonHint;
    private String source;
    private String updatedAt;
    private boolean live;
}
