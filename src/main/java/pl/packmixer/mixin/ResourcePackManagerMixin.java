package pl.packmixer.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.packmixer.config.MixConfig;
import pl.packmixer.license.LicenseManager;
import pl.packmixer.pack.MixerResourcePack;
import pl.packmixer.pack.PackIndex;

import java.util.ArrayList;
import java.util.List;

@Mixin(ResourcePackManager.class)
public abstract class ResourcePackManagerMixin {
    /** Dokłada pack-mikser na koniec listy (= najwyższy priorytet), ale tylko dla klienta i tylko z licencją. */
    @Inject(method = "createResourcePacks", at = @At("RETURN"), cancellable = true)
    private void packmixer$addMixer(CallbackInfoReturnable<List<ResourcePack>> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getResourcePackManager() != (Object) this) return;
        PackIndex.invalidate();
        if (!LicenseManager.isLicensed()) return;

        MixConfig cfg = MixConfig.get();
        if (cfg.overrides.isEmpty()) return;

        List<ResourcePack> list = new ArrayList<>(cir.getReturnValue());
        list.add(new MixerResourcePack((ResourcePackManager) (Object) this, cfg.snapshot()));
        cir.setReturnValue(list);
    }
}
