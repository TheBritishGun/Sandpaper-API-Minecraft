# Sandpaper API

Frame time budget scheduler for mods of java Minecraft. A port of my steam version of Sandpaper API, which was created because i got mad at a VTOL VR Mod's performance, and decided to make an engine that cannot cause frame stutter if used properly.

Minecraft 26.2, Fabric, Java 25. Client side.

## What it does

- One scheduler and one frame budget. Mods register periodic work, and the scheduler runs it inside the budget, so no
  single frame spikes.
- One background pool. Work computes off the game thread, and its result is applied on the game thread, inside the
  same budget.
- One settings screen. Each mod adds its own pages.

## For players

Sandpaper comes inside the mods that use it. You can also put the jar in your `mods` folder: Fabric loads the newest
copy.

## For mod authors

Nest the jar, and depend on it, in `fabric.mod.json`:

```json
"jars": [ { "file": "META-INF/jars/sandpaper-1.0.0+26.2.jar" } ],
"depends": { "sandpaper": ">=1.0.0" }
```

Run work on a cadence:

```java
Sandpaper.scheduler().register(
        JobSpec.everyMillis(Lane.TICK, 250).withOwner("mymod").withLabel("mymod-poll"),
        tick -> pollOnce());
```

Run work in batches, 1 batch a step, until the budget is spent:

```java
Sandpaper.scheduler().registerSliced(
        JobSpec.everyPump(Lane.TICK).withOwner("mymod").withLabel("mymod-work"),
        tick -> {
            if (!hasWork()) {
                return Step.YIELD;
            }
            doOneBatch();
            return Step.MORE;
        });
```

`Tick.hasTimeLeft()` does not change inside 1 run: never loop on it.

Compute off the game thread, and apply on it:

```java
Sandpaper.workPool().submit("mymod", () -> buildTheExpensiveThing(), result -> useIt(result));
```

Add a settings page: implement `dev.sandpaper.api.settings.SettingsContributor`, and declare it under the
`sandpaper-settings` entrypoint.

The rules a caller must obey are in `docs/CONTRACT.md`. Every public type is in `docs/API-INDEX.md`.

## Build

JDK 25 and Python 3. Give the JDK with `--jdk <path>` or `JAVA_HOME`.

```
python tools/fetch_deps.py
python build.py
```

The jar is `build/sandpaper-<version>+26.2.jar`.

## License

GPL-3.0-or-later. See `LICENSE`.

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
