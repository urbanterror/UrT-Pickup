package de.gost0r.pickupbot.ftwgl.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompareUrlRequest {

    @JsonProperty(value = "discord_ids")
    List<Long> discordIds;

}
