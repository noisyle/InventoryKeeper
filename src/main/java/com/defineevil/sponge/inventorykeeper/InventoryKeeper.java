package com.defineevil.sponge.inventorykeeper;

import com.google.common.reflect.TypeToken;
import com.google.inject.Inject;
import ninja.leaping.configurate.ConfigurationOptions;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.objectmapping.GuiceObjectMapperFactory;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.data.manipulator.mutable.PotionEffectData;
import org.spongepowered.api.effect.potion.PotionEffect;
import org.spongepowered.api.effect.potion.PotionEffectType;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.EventContext;
import org.spongepowered.api.event.entity.DestructEntityEvent;
import org.spongepowered.api.event.entity.living.humanoid.player.RespawnPlayerEvent;
import org.spongepowered.api.event.game.GameReloadEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.service.ChangeServiceProviderEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.service.economy.EconomyService;
import org.spongepowered.api.service.economy.account.UniqueAccount;
import org.spongepowered.api.world.gamerule.DefaultGameRules;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Plugin(id = Versioning.ID, name = Versioning.NAME, version = Versioning.VERSION, authors = "DefineEvil")
public class InventoryKeeper {

    @Inject
    private Logger logger;

    @Inject
    private GuiceObjectMapperFactory factory;

    @Inject
    @DefaultConfig(sharedRoot = true)
    private ConfigurationLoader<CommentedConfigurationNode> configManager;

    private Config config;
    private EconomyService economyService;

    @Listener
    public void onInit(GamePreInitializationEvent event) throws IOException, ObjectMappingException {
        loadConfig();
//        Sponge.getEventManager().registerListeners(this, this);
        logger.info("InventoryKeeper loaded.");

    }

    @Listener
    public void onReload(GameReloadEvent event) throws IOException, ObjectMappingException {
        loadConfig();
        logger.info("InventoryKeeper reloaded.");
    }

    @Listener
    public void onNewEconomyService(ChangeServiceProviderEvent event) {
        if (event.getService() == EconomyService.class) {
            this.economyService = (EconomyService) event.getNewProviderRegistration().getProvider();
        }
    }

    @Listener
    public void onPlayerDie(DestructEntityEvent entityEvent) {
        if (!(entityEvent.getTargetEntity() instanceof Player)) {
            return;
        }

        Optional<Player> player = entityEvent.getCause().first(Player.class);
        if (player.isPresent() && !player.get().getUniqueId().equals(entityEvent.getTargetEntity().getUniqueId()) && config.deathTypes.pvp) {
            return;
        }

        config.recentlyDiedPlayers.add(entityEvent.getTargetEntity().getUniqueId());
        try {
            saveConfig();
        } catch (IOException|ObjectMappingException e) {
            logger.warn("Failed to save config!", e);
        }
    }

    @Listener
    public void onPlayerRespawn(RespawnPlayerEvent event) {
        Player player = event.getTargetEntity();

        if (config.recentlyDiedPlayers.contains(player.getUniqueId())) {

            Map<String, Object> textTemplateParameters = new HashMap<>();

            if (valueAffected(config.moneyReduction)) {
                //Check if financial punishment can be done(economy plugin present?)
                if (economyService == null) {
                    logger.warn("$NAME can't perform financial punishment on just respawned player because there is no economy plugin present!");
                } else {
                    BigDecimal lostMoney = doFinancialPunishment(player, config.moneyReduction);
                    textTemplateParameters.put("moneyLoss", lostMoney);
                }
            }

            if (Boolean.valueOf(player.getWorld().getGameRule(DefaultGameRules.KEEP_INVENTORY).orElse("false")) && valueAffected(config.xpReduction)) {
                int lostXp = doXpPunishment(player, config.xpReduction);
                textTemplateParameters.put("xpLoss", lostXp);
            }
            if (config.potionEffects.size() > 0) {
                List<PotionEffect> appliedPotionEffects = doPotionEffectsPunishment(player, config.potionEffects);
                textTemplateParameters.put("potionEffects", appliedPotionEffects.stream().map(potionEffect -> potionEffect.getType().getId().replace("minecraft:", "")).collect(Collectors.joining(", ")));
            }

            if (config.sendDeathMessage)
                player.sendMessage(config.deathMessage.apply(textTemplateParameters).build());
            config.recentlyDiedPlayers.remove(player.getUniqueId());
            try {
                saveConfig();
            } catch (IOException|ObjectMappingException e) {
                logger.warn("Failed to save config!", e);
            }
        }
    }

    /**
     * Calculates a new [BigDecimal] based on the [oldValue] which is manipulated by the [nodeValue]. It can be a fixed
     * value or a relative value with a percent sign: e.g. "25", "40%". The [nodeKey] is for logging purposes on failure.
     * [BigDecimal]s are used in this method to cover the use case of calculating the player's balance after he died.
     */
    private BigDecimal getNewValueAfterReduction(BigDecimal oldValue, String nodeKey, String nodeValue) {
        if (nodeValue.contains("%")) {
            return oldValue.multiply(BigDecimal.valueOf(1 - (tryToNumber(nodeValue.split("%")[0], nodeKey)) / 100.0));
        } else {
            tryToNumber(nodeValue, nodeKey);
            return oldValue.subtract(new BigDecimal(nodeValue));
        }
    }

    /**
     * Must only be executed when [economyService] isn't null. See [getNewValueAfterReduction] for more information on
     * the formatting of the [reductionString].
     * @return The amount of lost money
     */
    private BigDecimal doFinancialPunishment(Player player, String reductionString) {
        UniqueAccount account = economyService.getOrCreateAccount(player.getUniqueId()).orElseThrow(() -> new RuntimeException("Failed to create an economy account for ${player.name}!"));
        BigDecimal oldBalance = account.getBalance(economyService.getDefaultCurrency());
        BigDecimal newBalance = getNewValueAfterReduction(oldBalance, "moneyReduction", reductionString);
        account.setBalance(economyService.getDefaultCurrency(), newBalance, Cause.builder().append(this).build(EventContext.empty()));
        return oldBalance.subtract(newBalance);
    }

    /**
     * See [getNewValueAfterReduction] for more information on the formatting of the [reductionString].
     * @return The amount of lost XP's
     */
    private int doXpPunishment(Player player, String reductionString) {
        Optional<Integer> optXps = player.get(Keys.TOTAL_EXPERIENCE);
        if (optXps.isPresent()) {
            int xps = optXps.get();
            BigDecimal newExperience = getNewValueAfterReduction(new BigDecimal(xps), "xpReduction", reductionString);
            player.offer(Keys.TOTAL_EXPERIENCE, newExperience.intValue());
            return xps - newExperience.intValue();
        } else {
            return 0;
        }
    }

    private boolean isNumber(String s) {
        Stream<Character> cStream = IntStream.range(0, s.toCharArray().length).mapToObj(i -> s.toCharArray()[i]);
        return cStream.allMatch(Character::isDigit);
    }

    private int tryToNumber(String s, String nodeKey) {
        if (isNumber(s)) {
            return Integer.valueOf(s);
        } else {
            throw new IllegalArgumentException("Config: Invalid " + nodeKey + " config node!");
        }
    }

    private boolean valueAffected(String s) {
        return !s.equals(("0%")) && !s.equals("0");
    }

    private void loadConfig() throws IOException, ObjectMappingException {
        CommentedConfigurationNode node =
                configManager.load(ConfigurationOptions.defaults().setObjectMapperFactory(factory));
        this.config = node.getValue(TypeToken.of(Config.class));
        if (config == null) {
            config = new Config();
            node.setValue(TypeToken.of(Config.class), config);
            configManager.save(node);
        }
    }

    private List<PotionEffect> doPotionEffectsPunishment(Player player, List<Config.PotionEffectConfig> potionEffectConfigs) {
        List<PotionEffect> potionEffects = potionEffectConfigs.stream().map(potionEffectConfig -> {
            Optional<PotionEffectType> optEffect = Sponge.getRegistry().getType(PotionEffectType.class, potionEffectConfig.id);
            if (optEffect.isPresent()) {
                return PotionEffect.builder()
                        .potionType(optEffect.get())
                        .amplifier(potionEffectConfig.amplifier)
                        .duration(potionEffectConfig.duration * 20)
                        .particles(potionEffectConfig.showParticles)
                        .build();
            } else {
                logger.warn("Config: PotionEffect ID " + potionEffectConfig.id + " isn't registered!");
                return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());

        player.getOrCreate(PotionEffectData.class).ifPresent(potionEffectData -> {
            potionEffects.forEach(potionEffectData::addElement);
            player.offer(potionEffectData);
        });

        return potionEffects;
    }

    private void saveConfig() throws IOException, ObjectMappingException {
        CommentedConfigurationNode node =
                configManager.load(ConfigurationOptions.defaults().setObjectMapperFactory(factory));
        node.setValue(TypeToken.of(Config.class), config);
        configManager.save(node);
    }

}
