package de.gost0r.pickupbot.ftwgl.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PlayerRatingsRequest {

    @JsonProperty(value = "discord_ids")
    List<Long> discordIds;

}
