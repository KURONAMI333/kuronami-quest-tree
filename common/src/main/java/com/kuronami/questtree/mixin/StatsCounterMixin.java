package com.kuronami.questtree.mixin;

import com.kuronami.questtree.server.SkillExperience;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** increase_stat experience: every statistic increase on the server. */
@Mixin(StatsCounter.class)
public abstract class StatsCounterMixin {

    @Inject(method = "increment", at = @At("HEAD"))
    private void questTree$increment(Player player, Stat<?> stat, int amount, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp && amount > 0) {
            SkillExperience.onStat(sp, stat, amount);
        }
    }
}
