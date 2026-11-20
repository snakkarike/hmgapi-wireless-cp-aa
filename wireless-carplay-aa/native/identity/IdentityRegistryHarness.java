package hmgapi.mods.wireless;

import hmgapi.mods.wireless.api.CarPlayIdentification;

/** Host fixture proving independent mods can own vehicle and theme contributions. */
public final class IdentityRegistryHarness {
    public static void main(String[] args) {
        IdentityRegistry registry = new IdentityRegistry();
        final StringBuilder log = new StringBuilder();
        registry.logger(new IdentityRegistry.Logger() {
            @Override public void log(String message) { log.append(message).append('\n'); }
        });
        CarPlayIdentification.Connector[] connectors = {
            new CarPlayIdentification.Connector(CarPlayIdentification.VehicleIdentity.CONNECTOR_CCS, 235000),
            new CarPlayIdentification.Connector(CarPlayIdentification.VehicleIdentity.CONNECTOR_AC, 10900)
        };
        registry.registerVehicle("fixture.ev", new CarPlayIdentification.VehicleIdentity("Fixture EV",
                CarPlayIdentification.VehicleIdentity.ENGINE_ELECTRIC, connectors));
        registry.registerTheme("fixture.theme", new CarPlayIdentification.ThemeIdentity("THEME1", 985, "19.0"));
        check("Fixture EV".equals(registry.vehicle().displayName), "vehicle contribution");
        check("THEME1:985:19.0".equals(registry.theme().encoded()), "theme contribution");
        try {
            registry.registerTheme("fixture.conflict", new CarPlayIdentification.ThemeIdentity("OTHER", 1, "19.0"));
            throw new AssertionError("second theme owner accepted");
        } catch (IllegalStateException expected) {}
        registry.removeTheme("fixture.theme");
        check(registry.theme() == null && registry.vehicle() != null, "independent removal");
        check(log.toString().contains("fixture.ev: register vehicle identity"), "vehicle request logged");
        check(log.toString().contains("fixture.theme: register theme identity"), "theme request logged");
        check(log.toString().contains("fixture.conflict: register theme identity"), "rejected request logged");
        check(log.toString().contains("fixture.theme: remove theme identity"), "removal request logged");
        System.out.println("PASS mock mods compose independent vehicle and theme contributions");
    }

    private static void check(boolean value, String what) {
        if (!value) throw new AssertionError(what);
    }
}
