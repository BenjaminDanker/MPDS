package mpds.mpds.mixin;

import mpds.mpds.AdvancementRewards;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Notices when a player completes an advancement that carries an MPDS reward (see AdvancementRewards). */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
    @Shadow
    private ServerPlayer player;

    @Shadow
    public abstract net.minecraft.advancements.AdvancementProgress getOrStartProgress(AdvancementHolder advancement);

    @Unique
    private boolean mpds$wasDone;

    @Inject(method = "award", at = @At("HEAD"))
    private void mpds$rememberProgress(AdvancementHolder advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        mpds$wasDone = AdvancementRewards.isRewarded(advancement.id()) && getOrStartProgress(advancement).isDone();
    }

    @Inject(method = "award", at = @At("RETURN"))
    private void mpds$rewardOnCompletion(AdvancementHolder advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !mpds$wasDone && AdvancementRewards.isRewarded(advancement.id())
            && getOrStartProgress(advancement).isDone()) {
            AdvancementRewards.onCompleted(player, advancement.id());
        }
    }
}
