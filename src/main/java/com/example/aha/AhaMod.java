package com.example.aha;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(AhaGeckoRegistration.MODID)
public class AhaMod {
    public AhaMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        AhaGeckoRegistration.register(bus);
        AhaSounds.register(bus);
    }
}
