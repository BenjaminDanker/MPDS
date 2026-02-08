package mpds.mpds.mixin;

import net.minecraft.entity.player.HungerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HungerManager.class)
public interface HungerManagerAccessor {
    @Accessor("foodTickTimer")
    void mpds$setFoodTickTimer(int foodTickTimer);

    @Accessor("foodTickTimer")
    int mpds$getFoodTickTimer();

    @Accessor("exhaustion")
    void mpds$setExhaustion(float exhaustion);

    @Accessor("exhaustion")
    float mpds$getExhaustion();
}
