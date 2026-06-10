package com.ToasterStudios.jenga;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.minecraft.world.GameRules;

public final class JengaGameRules {

    public static final GameRules.Key<GameRules.IntRule> JENGA_MAX_LAYERS =
        GameRuleRegistry.register(
            "jengaMaxLayers",
            GameRules.Category.MISC,
            GameRuleFactory.createIntRule(999)
        );

    public static void register() {}

    private JengaGameRules() {}
}
