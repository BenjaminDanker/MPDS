package mpds.mpds.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.nio.file.CopyOption;
import java.util.function.BooleanSupplier;
import net.minecraft.util.Util;

@Mixin(Util.class)
public class UtilMixin {

    @Inject(method = "createRenamer(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)Ljava/util/function/BooleanSupplier;", at = @At("RETURN"), cancellable = true)
    private static void mpds_wrapRenameTask(Path src, Path dest, CopyOption[] options, CallbackInfoReturnable<BooleanSupplier> cir) {
        BooleanSupplier original = cir.getReturnValue();

        cir.setReturnValue(() -> {
            try {
                return original.getAsBoolean();
            } catch (Throwable t) {
                if (isNoSuchFileException(t)) {
                    String destName = dest.getFileName() != null ? dest.getFileName().toString() : "";
                    if (destName.endsWith("_old")) {
                        return true;
                    }
                }

                if (t instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (t instanceof Error error) {
                    throw error;
                }
                Util.throwAsRuntime(t);
                return false;
            }
        });
    }

    private static boolean isNoSuchFileException(Throwable t) {
        while (t != null) {
            if (t instanceof java.nio.file.NoSuchFileException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
