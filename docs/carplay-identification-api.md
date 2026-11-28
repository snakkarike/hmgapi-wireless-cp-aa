# CarPlay identification API

Wireless CarPlay + Android Auto owns the native patch and serializer for the iAP2
accessory-identification message. A consumer mod owns only the semantic facts it knows,
such as an EV's name, engine type and charging connectors or a CarPlay theme identifier.

This keeps one mod from replacing another mod's native patch and does not make Wireless
responsible for vehicle profiles, theme assets, display code or CarPlay session behavior.

## Add the dependency

Declare Wireless in the consumer's `mod.json`. Version `2.2.0-rc.4` is the first Wireless
release that contains this API:

```json
{
  "requires": [
    "wireless-carplay-aa >=2.2.0-rc.4 <3.0.0"
  ]
}
```

`requires` does two jobs: HMG-API installs and loads the provider first, and the consumer's
class loader can see Wireless's exported `hmgapi.mods.wireless.api` package.

Compile against
[`CarPlayIdentification.java`](../wireless-carplay-aa/src/hmgapi/mods/wireless/api/CarPlayIdentification.java)
or a jar containing that class as a **compile-only** dependency. Do not package a second
copy of `hmgapi.mods.wireless.api` in the consumer's hooks jar. At runtime the class must
come from the required Wireless mod. One useful package check is:

```sh
jar tf out/build/<consumer>/hooks.jar | grep '^hmgapi/mods/wireless/api/'
```

That command should print nothing.

## Get the service

Wireless publishes the service during zygote initialization. A consumer can obtain it from
its own `zygoteInit` and retain the process-local object:

```java
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;
import hmgapi.mods.wireless.api.CarPlayIdentification;

public final class ExampleConsumer extends ModEntry {
    private CarPlayIdentification identity;

    @Override
    public void zygoteInit(ModContext mod) {
        identity = (CarPlayIdentification) mod.use(
                "wireless-carplay-aa", CarPlayIdentification.SERVICE);
        if (identity == null) {
            mod.log("CarPlay identification API was not published");
            return;
        }

        // Register this mod's contribution here.
    }
}
```

`mod.use(...)` is permitted only because the provider is listed in `requires`. A `null`
result means the required mod did not publish the service in this process; the consumer
should log that condition and continue without its optional CarPlay identity feature.

## Contribute vehicle identity

```java
CarPlayIdentification.Connector[] connectors = {
    new CarPlayIdentification.Connector(
            CarPlayIdentification.VehicleIdentity.CONNECTOR_CCS, 235000),
    new CarPlayIdentification.Connector(
            CarPlayIdentification.VehicleIdentity.CONNECTOR_AC, 10900)
};

identity.registerVehicle(mod.id,
        new CarPlayIdentification.VehicleIdentity(
                "Kia EV6",
                CarPlayIdentification.VehicleIdentity.ENGINE_ELECTRIC,
                connectors));
```

Connector power is expressed in watts. Supported engine constants are `ENGINE_GASOLINE`,
`ENGINE_DIESEL`, `ENGINE_ELECTRIC` and `ENGINE_CNG`. Supported connector constants are
`CONNECTOR_CCS`, `CONNECTOR_CHADEMO` and `CONNECTOR_AC`.

The display name is the vehicle name contributed to CarPlay's vehicle information. It is
not the name shown by HMG-API itself, and this API does not provide live battery, range or
routing data.

## Contribute theme identity

```java
identity.registerTheme(mod.id,
        new CarPlayIdentification.ThemeIdentity(
                "EXAMPLE", 1, "19.0"));
```

This advertises the theme asset identifier, asset version and SDK version to CarPlay. It
does not install theme assets or implement a display. Those remain the consumer's work.

Vehicle and theme identity are separate contribution types. One vehicle mod and one theme
mod can therefore cooperate even when they are different mods.

## Ownership and lifecycle

Exactly one mod may own each contribution type at a time:

- Calling `registerVehicle` or `registerTheme` again with the same `mod.id` replaces that
  mod's previous value.
- A different mod trying to register an already-owned type receives
  `IllegalStateException`. It does not replace the existing owner.
- `removeVehicle(mod.id)` and `removeTheme(mod.id)` remove only that mod's contribution.
  Removing a contribution that is not present is safe.
- A mod should remove a contribution when its corresponding feature is disabled, and
  register the new immutable value when its settings or selected vehicle profile change.

Use the real HMG-API mod ID (`mod.id`) for every call. Invalid IDs, fields, connector types
or power values produce `IllegalArgumentException`; constructors validate values before
anything reaches the native serializer.

## Wireless setting and failure behavior

The **Shared CarPlay identity** Wireless setting is on by default and takes effect after a
head-unit restart.

When it is off, Wireless still publishes the API and accepts contributions, but does not
install or update the native identity serializer. This lets consumer mods start normally
and makes their API activity visible for diagnosis. Ordinary Wireless CarPlay and Android
Auto behavior is independent of this feature.

Native identity setup is also fail-open: Wireless logs an error and continues its normal
projection behavior. It retries when the iAP2 native library was simply not initialized
at the first app-load callback.

## Logs

Wireless logs every valid API request with the caller's mod ID, including a request later
rejected because another mod already owns that contribution type. Examples:

```text
CarPlay identity API request from egmp-carplay-ev-routing: register vehicle identity
CarPlay identity API request from example-theme: remove theme identity
```

The log records the operation and owner, not the contributed values. Serializer install,
retry and native failure messages are logged separately under `CarPlay identification`.

## API surface

The exported contract is
[`CarPlayIdentification`](../wireless-carplay-aa/src/hmgapi/mods/wireless/api/CarPlayIdentification.java).
`API_VERSION` is currently `2`. Compatibility should be expressed primarily through the
consumer's Wireless version range: a future incompatible API belongs in a new Wireless
major version rather than being guessed from the runtime object.
