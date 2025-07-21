package de.gost0r.pickupbot.ftwgl.models;

import lombok.Data;

import java.util.List;

@Data
public class PlayerTopRatingsResponse {

    private List<PlayerRatingEntry> players;

    @Data
    public static class PlayerRatingEntry {
        private Long discord_user_id;
        private Double rating;
        private Integer gp;
    }
} 