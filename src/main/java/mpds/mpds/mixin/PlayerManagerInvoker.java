package mpds.mpds.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerList.class)
public interface PlayerManagerInvoker {
    @Invoker("save")
    void invokesavePlayerData(ServerPlayer player);
}
