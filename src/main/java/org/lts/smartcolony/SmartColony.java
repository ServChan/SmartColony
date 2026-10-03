package org.lts.smartcolony;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import org.lts.smartcolony.colony.ColonyManager;
import org.lts.smartcolony.colony.ColonyEvents;
import org.lts.smartcolony.command.ColonyCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SmartColony implements ModInitializer {
    public static final String MOD_ID = "smartcolony";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        SmartColonyConfig.load();
        ServerLifecycleEvents.SERVER_STARTED.register(ColonyManager::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(ColonyManager::stop);
        ServerTickEvents.END_SERVER_TICK.register(ColonyManager::tick);
        CommandRegistrationCallback.EVENT.register(ColonyCommand::register);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(ColonyEvents::allowDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(ColonyEvents::afterDeath);
        PlayerBlockBreakEvents.BEFORE.register(ColonyEvents::beforeBreak);
        AttackEntityCallback.EVENT.register(ColonyEvents::attackEntity);
        LOGGER.info("Smart Colony initialized");
    }
}
