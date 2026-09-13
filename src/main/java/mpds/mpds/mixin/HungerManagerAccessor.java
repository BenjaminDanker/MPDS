package mpds.mpds.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FoodData.class)
public interface HungerManagerAccessor {
    @Accessor("tickTimer")
    void mpds$setFoodTickTimer(int foodTickTimer);

    @Accessor("tickTimer")
    int mpds$getFoodTickTimer();

    @Accessor("exhaustionLevel")
    void mpds$setExhaustion(float exhaustion);

    @Accessor("exhaustionLevel")
    float mpds$getExhaustion();
}
