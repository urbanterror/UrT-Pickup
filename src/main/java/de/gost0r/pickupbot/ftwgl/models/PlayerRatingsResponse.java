package de.gost0r.pickupbot.ftwgl.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Collections;
import java.util.Map;

@Data
public class PlayerRatingsResponse {

    private Map<Long, Double> ratings;

    @JsonProperty("secondary_ratings")
    private Map<Long, Double> secondaryRatings = Collections.emptyMap();

}
