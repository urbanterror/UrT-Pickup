package de.gost0r.pickupbot.ftwgl.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LaunchClientRequest {

    @JsonProperty(value = "discord_id")
    private Long discordId;

    private String address;

    private String password;

}
