# Phase 0 — Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A verified, green do-nothing scaffold (M0.1) plus the mod's heartbeat: a client-tick hook with in-world awareness and world join/leave detection (M0.2).

**Architecture:** Two `@Mod` entrypoints already exist: `Marionette` (loads on both dists, stays a no-op shell) and `MarionetteClient` (`dist = Dist.CLIENT`, never loads on dedicated servers). M0.1 adds a startup log line proving the entrypoint ran. M0.2 turns `MarionetteClient` into the lifecycle singleton: it registers listeners on the NeoForge game bus for client ticks and client-player login/logout, tracks an `inWorld` flag, and no-ops every handler while not in a world. Everything later (input injection, the bridge, observations) hangs off this class.

**Tech Stack:** Java 21, NeoForge 21.8.53 (Minecraft 1.21.8), Gradle via `./gradlew`, ModDevGradle 2.0.141.

## Global Constraints

- Minecraft version: `1.21.8`; NeoForge: `21.8.53` (from `gradle.properties` — do not bump).
- Java toolchain: 21 (`java.toolchain.languageVersion` in `build.gradle`).
- Mod id `marionette`, package/group `com.prattlemob.marionette` — fixed, never rename.
- The mod ships no AI and makes no gameplay decisions; Phase 0 code is infrastructure only.
- Never add a literal `${` anywhere in `src/main/templates/META-INF/neoforge.mods.toml` (even in comments) — it breaks `generateModMetadata`.
- Design decisions live in `docs/decisions.md`; nothing in Phase 0 touches an open decision.
- There is no unit-test framework in this repo and Phase 0's deliverables are engine hooks with no pure logic to unit-test. The roadmap's own Definition of Done for both milestones is build + rendered-client log evidence, so each task's "test" is `./gradlew build` plus a scripted `runClient`/`runServer` log check with exact expected lines given below.
- `./gradlew runClient` opens a real game window and keeps running until the game is closed — run it in the background (or let the human run it), then inspect `run/logs/latest.log`. Joining a singleplayer world (Task 3, Step 3) is a manual in-game action; ask the human partner to do it if you cannot drive the GUI.
- Current branch is `1.21.8` (also the main branch). Commit after each task; push only at the CI-verification step (Task 2) or when the human partner says to.

---

### Task 1: M0.1 — Startup log line + green local build

**Files:**
- Modify: `src/main/java/com/prattlemob/marionette/Marionette.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Marionette.LOGGER` (public static `org.slf4j.Logger`, already exists) and `Marionette.MODID` (public static `String`, already exists) — Task 3 uses both. The constructor logs exactly one startup line: `Marionette <version> initialising`.

- [ ] **Step 1: Confirm the baseline builds before touching anything**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. If this fails, stop — the scaffold itself is broken and that must be fixed before Phase 0 proceeds (report to the human partner; do not improvise fixes to `build.gradle`).

- [ ] **Step 2: Add the startup log line to the common entrypoint**

Replace the entire contents of `src/main/java/com/prattlemob/marionette/Marionette.java` with:

```java
package com.prattlemob.marionette;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Marionette.MODID)
public class Marionette {
    public static final String MODID = "marionette";
    public static final Logger LOGGER = LogUtils.getLogger();

    // Intentionally minimal: Marionette registers no gameplay content.
    // Client-side lifecycle lives in MarionetteClient; this common entrypoint
    // stays a no-op so the mod is inert on dedicated servers.
    public Marionette(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Marionette {} initialising", modContainer.getModInfo().getVersion());
    }
}
```

- [ ] **Step 3: Verify it still builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/prattlemob/marionette/Marionette.java
git commit -m "Log a startup line from the mod entrypoint (M0.1)"
```

---

### Task 2: M0.1 — Rendered-client and CI verification

**Files:**
- Modify: `ROADMAP.md` (tick the three M0.1 item checkboxes, lines 41–43)

**Interfaces:**
- Consumes: the startup log line from Task 1 (`Marionette 0.1.0 initialising`).
- Produces: a verified green baseline; no code surface.

- [ ] **Step 1: Launch the dev client in the background**

Run: `./gradlew runClient` (in the background — it blocks until the game closes).
Expected: a rendered Minecraft 1.21.8 window reaches the title screen without crashing.

- [ ] **Step 2: Verify the entrypoint log line**

Run: `grep "Marionette 0.1.0 initialising" run/logs/latest.log`
Expected: exactly one matching line. If absent, the mod did not load — check `run/logs/latest.log` for `marionette` loading errors before proceeding.

- [ ] **Step 3: Verify Marionette appears in the mod list**

In the running client: title screen → **Mods** button → confirm **Marionette** is listed with version 0.1.0. This is a GUI check — ask the human partner to confirm if you cannot drive the window. Then close the game.
Expected: Marionette listed; description text from `neoforge.mods.toml` shown.

- [ ] **Step 4: Push and verify CI is green**

```bash
git push origin 1.21.8
gh run watch --exit-status
```

Expected: the `Build` workflow completes with success. (It triggers because `src/**` changed.)

- [ ] **Step 5: Tick the M0.1 checkboxes in ROADMAP.md and commit**

In `ROADMAP.md`, change the three M0.1 items (lines 41–43) from `- [ ]` to `- [x]`:

```markdown
- [x] `./gradlew build` passes locally and in CI
- [x] Mod entrypoint class exists and logs a startup line
- [x] `runClient` reaches the title screen with Marionette in the mod list
```

```bash
git add ROADMAP.md
git commit -m "Mark M0.1 complete: verified do-nothing scaffold"
```

(This commit touches only `ROADMAP.md`, so it will not trigger the build workflow — that is expected; the workflow deliberately runs only on build-affecting paths.)

---

### Task 3: M0.2 — Client lifecycle singleton: tick hook + world join/leave

**Files:**
- Modify: `src/main/java/com/prattlemob/marionette/MarionetteClient.java`

**Interfaces:**
- Consumes: `Marionette.LOGGER`, `Marionette.MODID` from Task 1.
- Produces: the anchor for all later milestones:
  - `MarionetteClient.instance()` → `MarionetteClient` (static accessor for the FML-constructed singleton)
  - `instance().isInWorld()` → `boolean` (true between client login and logout)
  - `instance().ticksInWorld()` → `long` (client ticks since entering the current world)
  - a private per-tick hook `onClientTickPost(ClientTickEvent.Post)` that no-ops outside a world — M1.1's control-state application will be called from inside its in-world branch.

- [ ] **Step 1: Implement the lifecycle singleton**

Replace the entire contents of `src/main/java/com/prattlemob/marionette/MarionetteClient.java` with:

```java
package com.prattlemob.marionette;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-side lifecycle anchor. Receives every client tick and tracks whether
 * the player is currently in a world; every later subsystem (input injection,
 * the bridge, observations) hangs off this class.
 *
 * This class never loads on dedicated servers ({@code dist = Dist.CLIENT}),
 * so client-only code is safe to reference from here.
 */
@Mod(value = Marionette.MODID, dist = Dist.CLIENT)
public class MarionetteClient {
    /** How often (in ticks) to emit the heartbeat log line: 100 ticks = 5 s. */
    private static final long TICK_LOG_INTERVAL = 100;

    private static MarionetteClient instance;

    private boolean inWorld;
    private long ticksInWorld;

    public MarionetteClient(ModContainer container) {
        instance = this;
        NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
    }

    public static MarionetteClient instance() {
        return instance;
    }

    public boolean isInWorld() {
        return inWorld;
    }

    public long ticksInWorld() {
        return ticksInWorld;
    }

    private void onClientTickPost(ClientTickEvent.Post event) {
        if (!inWorld) {
            return;
        }
        ticksInWorld++;
        if (ticksInWorld % TICK_LOG_INTERVAL == 0) {
            Marionette.LOGGER.info("Client tick {} in world", ticksInWorld);
        }
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        inWorld = true;
        ticksInWorld = 0;
        Marionette.LOGGER.info("Entered world");
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        inWorld = false;
        Marionette.LOGGER.info("Left world");
    }
}
```

Notes for the implementer:
- `ClientTickEvent.Post` and `ClientPlayerNetworkEvent.LoggingIn`/`LoggingOut` are game-bus events (`net.neoforged.neoforge.client.event`), so they register on `NeoForge.EVENT_BUS`, **not** the mod event bus passed to the constructor. If either class name fails to resolve against NeoForge 21.8.53, list the actual event classes with `find ~/.gradle -path "*neoforge*" -name "*.jar" | head` and inspect, or check the NeoForge 21.8 javadoc — do not silently substitute a different mechanism.
- The heartbeat log line is milestone evidence and will be demoted to `debug` or removed once M1.x hangs real work off the tick hook. Keep it `info` for now so it shows in the default dev-console log level.
- `LoggingOut` also fires when quitting to the title screen from singleplayer; that is exactly the transition M0.2 wants logged.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Verify lifecycle behaviour in a rendered client**

Run `./gradlew runClient` in the background, then (manually or via the human partner):
1. From the title screen, create/enter a singleplayer world.
2. Stay in the world ~15 seconds.
3. Quit to the title screen (Esc → Save and Quit to Title).
4. Close the game.

Then run: `grep -E "Entered world|Left world|Client tick" run/logs/latest.log`
Expected output shape (tick numbers grow only between the enter/leave pair, and no `Client tick` lines appear before "Entered world" or after "Left world"):

```
[Render thread/INFO] [com.prattlemob.marionette.Marionette/]: Entered world
[Render thread/INFO] [com.prattlemob.marionette.Marionette/]: Client tick 100 in world
[Render thread/INFO] [com.prattlemob.marionette.Marionette/]: Client tick 200 in world
[Render thread/INFO] [com.prattlemob.marionette.Marionette/]: Left world
```

If `Client tick` lines appear while on the title screen, the `inWorld` guard is broken — fix before committing.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/prattlemob/marionette/MarionetteClient.java
git commit -m "Add client lifecycle singleton with tick hook and world join/leave tracking (M0.2)"
```

---

### Task 4: M0.2 — Dedicated-server no-op verification + milestone close-out

**Files:**
- Modify: `ROADMAP.md` (tick the four M0.2 item checkboxes, lines 60–63)

**Interfaces:**
- Consumes: `MarionetteClient` from Task 3 (`dist = Dist.CLIENT` is what this task verifies).
- Produces: Phase 0 marked complete; no code surface.

- [ ] **Step 1: Verify the mod is a no-op on a dedicated server**

Run: `./gradlew runServer` in the background.
The first run writes `run/eula.txt` (or the server-run equivalent under the server run directory) and exits; accept the EULA (`eula=true`) and re-run — this is the standard dev-server dance, and it is local-only.
Expected once running: the server reaches `Done (...)! For help, type "help"`, the log contains `Marionette 0.1.0 initialising` (common entrypoint ran), and contains **no** `Entered world` / `Client tick` lines and no `MarionetteClient` class-loading errors — proving the client entrypoint never loaded. Stop the server with the `stop` console command (or kill the gradle task).

- [ ] **Step 2: Confirm no crash and clean log, then tick the M0.2 checkboxes**

In `ROADMAP.md`, change the four M0.2 items (lines 60–63) from `- [ ]` to `- [x]`:

```markdown
- [x] Client tick event handler wired (pre/post tick as appropriate)
- [x] In-world vs. menu state tracked; handlers no-op outside a world
- [x] World join/leave detection with log evidence
- [x] Mod marked client-side (no-op / not required on dedicated servers)
```

Note on "not required on dedicated servers": Marionette registers no content, no registries, and no network payloads, so a Marionette client can join servers without the mod and vice versa; the `dist = Dist.CLIENT` split plus the inert common entrypoint is the whole mechanism. If a stronger declaration (e.g. a mods.toml side marker) is ever wanted, that is a `docs/decisions.md` entry, not a Phase 0 improvisation.

- [ ] **Step 3: Commit**

```bash
git add ROADMAP.md
git commit -m "Mark M0.2 complete: client lifecycle and tick hook verified"
```

- [ ] **Step 4: Final green check**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. Push only if the human partner wants CI re-verified now (the Task 3 code commit will trigger the build workflow on push).
