package arsonist.autospear;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class AutoSpearAddon extends MeteorAddon {
    @Override
    public void onInitialize() {
        Modules.get().add(new AutoSpear());
    }

    @Override
    public String getPackage() {
        return "arsonist.autospear";
    }
}
