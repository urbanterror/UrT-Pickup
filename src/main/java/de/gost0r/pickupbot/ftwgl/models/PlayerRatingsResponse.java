package de.gost0r.pickupbot.ftwgl.models;

import lombok.Data;

import java.util.Map;

@Data
public class PlayerRatingsResponse {

    private Map<Long, Double> ratings;

}
