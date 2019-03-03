package com.defineevil.sponge.inventorykeeper;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.TextTemplate;
import org.spongepowered.api.text.format.TextColors;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ConfigSerializable
public class Config {

    @Setting(comment = "Relative or fixed => with or without percent sign")
    public String xpReduction = "50%";

    @Setting(comment = "Relative or fixed => with or without percent sign")
    public String moneyReduction = "0%";

    @Setting(comment = "Potion effects applied at death. You can do 'copy & paste' to add new effects.")
    public List<PotionEffectConfig> potionEffects;

    @Setting
    public boolean sendDeathMessage = true;

    @Setting(comment = "The message that gets sent to the died player.")
    public TextTemplate deathMessage = TextTemplate.of(
            Text.of(TextColors.AQUA, "[InventoryKeeper]"), " You lost ",
            TextTemplate.arg("moneyLoss").optional(true).defaultValue(Text.of("[Not provided]")),
            "$, ",
            TextTemplate.arg("xpLoss").optional(true).defaultValue(Text.of("[Not provided]")),
            " XP's and got the potion effect(s) ",
            TextTemplate.arg("potionEffects").optional(true).defaultValue(Text.of("[Not provided]"))
    );

    @Setting(comment = "Ways to die when this plugin should NOT take any action.")
    public DeathType deathTypes = new DeathType();

    @Setting(comment = "Don't modify this value! It is for internal use.")
    public List<UUID> recentlyDiedPlayers = new ArrayList<>();

    @ConfigSerializable
    public static class PotionEffectConfig {
        @Setting
        public String id = "slowness";
        @Setting(comment = "Duration in seconds")
        public int duration = 180;
        @Setting
        public int amplifier = 1;
        @Setting
        public boolean showParticles = false;
    }

    @ConfigSerializable
    public static class DeathType {

        @Setting
        public boolean pvp = true;

    }


}
