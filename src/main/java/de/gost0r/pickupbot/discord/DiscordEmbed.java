package de.gost0r.pickupbot.discord;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class DiscordEmbed {

    private String title;
    private String description;
    private String thumbnail;
    private int color;
    private final List<Field> fields = new ArrayList<>();
    private String footerText;
    private String footerIcon;
    private Long timestamp;

    public void addField(String name, String value, boolean inline) {
        fields.add(new Field(name, value, inline));
    }

    public record Field(String name, String value, boolean inline) {
    }
}