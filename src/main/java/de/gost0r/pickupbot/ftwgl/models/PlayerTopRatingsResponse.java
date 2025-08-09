package de.gost0r.pickupbot.ftwgl.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class PlayerTopRatingsResponse {

    private List<PlayerRatingEntry> players;

    @Data
    public static class PlayerRatingEntry {
        @JsonProperty(value = "discord_user_id")
        private Long discordUserId;
        private Double rating;
        private Integer gp;
    }
} 