# Phase 1 — Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An external Python script drives the real, rendered Minecraft player over a localhost WebSocket (walk a square, turn, stop), with all controls released within one tick of the agent dying — ROADMAP milestones M1.1, M1.2, M1.3.

**Architecture:** Three bounded subsystems per the approved spec (`docs/superpowers/specs/2026-07-08-phase-1-walking-skeleton-design.md`): a `control` package (pure `ControlState` + a `ControlStateApplier` seam behind which the D4 injection experiment runs), a `bridge` package (Netty WebSocket server, Minecraft-free and headless-testable), and wiring in the existing `MarionetteClient` singleton (mailbox drain → apply → observe, plus the safety release). The network thread never touches game state; the client tick thread is the only reader/writer of game state.

**Tech Stack:** Java 21, NeoForge 21.8.53 / MC 1.21.8, ModDevGradle 2.0.141, Mojang mappings at runtime (no refmaps needed), MC-bundled Netty core 4.1.118.Final + Jar-in-Jar'd `netty-codec-http:4.1.118.Final` (D1a), MC-bundled Gson 2.11, JUnit 5 (new in this phase), Python 3 + `websockets` for examples.

## Global Constraints

- Mod id `marionette`, package `com.prattlemob.marionette` — fixed, never rename.
- Java 21 toolchain; MC 1.21.8 / NeoForge 21.8.53 pin (from `gradle.properties`).
- The only new runtime dependency permitted is `io.netty:netty-codec-http:4.1.118.Final` via Jar-in-Jar (D1a, settled in `docs/decisions.md`). JUnit is test-only.
- The bridge (`com.prattlemob.marionette.bridge`) must not import any `net.minecraft` or `net.neoforged` class — it is headless-testable by design.
- Network thread never touches game state; game state is touched only from the client tick thread.
- WebSocket server binds `127.0.0.1` only, default port 24680.
- Agent-facing wire format: protocol v0 as specced (one JSON object per WS text frame); document verbatim in `protocol/v0-draft.md`, marked as a draft that M2.1 replaces.
- `src/main/templates/META-INF/neoforge.mods.toml` must never contain a literal `${` outside its Gradle-expanded placeholders (breaks `generateModMetadata`). This plan does not touch that file.
- Design decisions marked open/deferred in `docs/decisions.md` must not be locked in without asking the user. D4's *outcome* is experiment-gated — record it, don't pre-decide it.
- Commit after every task (the steps say when). Branch: `phase-1-walking-skeleton` (already checked out; merge to `1.21.8` happens later, locally).

## Verified 1.21.8 facts (do not re-derive; checked against decompiled sources)

- `net.minecraft.world.entity.player.Input` is a record: `Input(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint)`, with `Input.EMPTY`.
- `net.minecraft.client.player.ClientInput` has `public Input keyPresses` and `protected Vec2 moveVector`; `net.minecraft.client.player.KeyboardInput extends ClientInput` and its `tick()` rebuilds both from `options.keyUp/keyDown/keyLeft/keyRight/keyJump/keyShift/keySprint` (`moveVector = new Vec2(leftImpulse, forwardImpulse).normalized()`).
- `LocalPlayer` has `public ClientInput input`. Yaw/pitch set via `player.setYRot(float)` / `player.setXRot(float)`.
- `Options.keyShift` and `Options.keySprint` are `ToggleKeyMapping`s: when the user enables toggle-crouch/toggle-sprint, `setDown(true)` **toggles** the held state instead of setting it, and `setDown(false)` is a no-op. This is a core D4 risk to probe, not a bug in this plan.
- NeoForge events: `net.neoforged.neoforge.client.event.ClientTickEvent.Pre` / `.Post`; `net.neoforged.neoforge.event.GameShuttingDownEvent`; `ClientPlayerNetworkEvent.LoggingIn/LoggingOut` (LoggingOut fires with a **null player** during pre-connect defensive disconnects — the existing null guard in `MarionetteClient` must stay).
- MC's bundled Netty is core-only (buffer/codec/common/handler/resolver/transport/epoll) — **no** `netty-codec-http`; that is why D1a Jar-in-Jars it.

## Scripted in-game verification harness (referenced by several tasks)

The dev box is KDE Plasma 6 / Wayland with no keyboard-injection tooling. The
client ignores SIGTERM; closing its window via KWin scripting is the clean
shutdown. Runs auto-join a world via `--quickPlaySingleplayer`. "Title screen
reached" marker in `run/logs/latest.log`: `Sound engine started`.

Full recipe (each verification step below references this):

```bash
# 1. Launch (background) — the clientDemo run config is added in Task 3:
./gradlew runClientDemo   # run in background; window appears on the desktop

# 2. Wait (poll every ~10 s, timeout ~5 min) for the expected log marker:
grep -c "Demo complete" run/logs/latest.log

# 3. Close the client cleanly:
scripts/close-minecraft-window.sh   # added in Task 3

# 4. Analyze run/logs/latest.log (grep patterns given per task).
```

---

### Task 1: JUnit infrastructure + ControlState

**Files:**
- Modify: `build.gradle` (repositories block at line 29-31, dependencies block at line 115-137)
- Create: `src/main/java/com/prattlemob/marionette/control/ControlState.java`
- Test: `src/test/java/com/prattlemob/marionette/control/ControlStateTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ControlState` with `boolean forward()/back()/left()/right()/jump()/sneak()/sprint()`, matching `setForward(boolean)`-style setters, `void setLook(float yaw, float pitch)`, `ControlState.Look consumeLook()` (record `Look(float yaw, float pitch)`, null when none pending), `boolean anyHeld()`, `void releaseAll()`. Later tasks (3, 4, 8) rely on these exact names.

- [ ] **Step 1: Wire JUnit into the build**

In `build.gradle`, change the empty repositories block:

```groovy
repositories {
    // For test-only dependencies (JUnit). Mod dependencies come from the NeoForge plugin.
    mavenCentral()
}
```

and add to the `dependencies` block (keep the existing commented examples):

```groovy
dependencies {
    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

then add after the `dependencies` block:

```groovy
tasks.named('test', Test) {
    useJUnitPlatform()
    testLogging {
        events 'passed', 'skipped', 'failed'
    }
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/prattlemob/marionette/control/ControlStateTest.java`:

```java
package com.prattlemob.marionette.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ControlStateTest {
    @Test
    void controlsAreNeutralInitially() {
        ControlState state = new ControlState();
        assertFalse(state.anyHeld());
        assertNull(state.consumeLook());
    }

    @Test
    void heldControlsPersistUntilChanged() {
        ControlState state = new ControlState();
        state.setForward(true);
        state.setSprint(true);
        // unrelated updates must not disturb held controls (set-and-hold)
        state.setJump(true);
        state.setJump(false);
        assertTrue(state.forward());
        assertTrue(state.sprint());
        assertFalse(state.jump());
        assertTrue(state.anyHeld());
    }

    @Test
    void releaseAllReturnsEverythingToNeutral() {
        ControlState state = new ControlState();
        state.setForward(true);
        state.setBack(true);
        state.setLeft(true);
        state.setRight(true);
        state.setJump(true);
        state.setSneak(true);
        state.setSprint(true);
        state.setLook(90.0F, -10.0F);
        state.releaseAll();
        assertFalse(state.forward());
        assertFalse(state.back());
        assertFalse(state.left());
        assertFalse(state.right());
        assertFalse(state.jump());
        assertFalse(state.sneak());
        assertFalse(state.sprint());
        assertFalse(state.anyHeld());
        assertNull(state.consumeLook());
    }

    @Test
    void lookIsConsumedExactlyOnce() {
        ControlState state = new ControlState();
        state.setLook(45.0F, 10.0F);
        ControlState.Look look = state.consumeLook();
        assertEquals(45.0F, look.yaw());
        assertEquals(10.0F, look.pitch());
        assertNull(state.consumeLook());
    }

    @Test
    void newestLookWins() {
        ControlState state = new ControlState();
        state.setLook(10.0F, 0.0F);
        state.setLook(20.0F, 5.0F);
        assertEquals(new ControlState.Look(20.0F, 5.0F), state.consumeLook());
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.prattlemob.marionette.control.ControlStateTest'`
Expected: compilation FAILS with "package com.prattlemob.marionette.control does not exist" / cannot find symbol `ControlState`.

- [ ] **Step 4: Implement ControlState**

`src/main/java/com/prattlemob/marionette/control/ControlState.java`:

```java
package com.prattlemob.marionette.control;

/**
 * Set-and-hold control intent: values persist until changed by a later
 * command, mirroring keys a player holds down. One instance is owned by the
 * client tick loop and only ever touched from the client tick thread. Pure
 * data — no Minecraft imports — so it stays unit-testable.
 */
public final class ControlState {
    /** A one-shot raw camera rotation intent, consumed when applied. */
    public record Look(float yaw, float pitch) {}

    private boolean forward;
    private boolean back;
    private boolean left;
    private boolean right;
    private boolean jump;
    private boolean sneak;
    private boolean sprint;
    private Look pendingLook;

    public boolean forward() { return forward; }
    public boolean back() { return back; }
    public boolean left() { return left; }
    public boolean right() { return right; }
    public boolean jump() { return jump; }
    public boolean sneak() { return sneak; }
    public boolean sprint() { return sprint; }

    public void setForward(boolean held) { forward = held; }
    public void setBack(boolean held) { back = held; }
    public void setLeft(boolean held) { left = held; }
    public void setRight(boolean held) { right = held; }
    public void setJump(boolean held) { jump = held; }
    public void setSneak(boolean held) { sneak = held; }
    public void setSprint(boolean held) { sprint = held; }

    /** Queue a raw camera set; replaces any unconsumed intent. */
    public void setLook(float yaw, float pitch) {
        pendingLook = new Look(yaw, pitch);
    }

    /** The pending look intent, clearing it; {@code null} when none queued. */
    public Look consumeLook() {
        Look look = pendingLook;
        pendingLook = null;
        return look;
    }

    /** True when any control is held. */
    public boolean anyHeld() {
        return forward || back || left || right || jump || sneak || sprint;
    }

    /** Return every control to neutral and drop any pending look intent. */
    public void releaseAll() {
        forward = back = left = right = jump = sneak = sprint = false;
        pendingLook = null;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.prattlemob.marionette.control.ControlStateTest'`
Expected: 5 tests PASS.

- [ ] **Step 6: Full build sanity check**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (test task now part of `check`).

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/main/java/com/prattlemob/marionette/control/ControlState.java src/test/java/com/prattlemob/marionette/control/ControlStateTest.java
git commit -m "Add ControlState set-and-hold model with first JUnit infrastructure (M1.1)"
```

---

### Task 2: DemoScript state machine

**Files:**
- Create: `src/main/java/com/prattlemob/marionette/control/DemoScript.java`
- Test: `src/test/java/com/prattlemob/marionette/control/DemoScriptTest.java`

**Interfaces:**
- Consumes: `ControlState` from Task 1 (`setForward`, `setSprint`, `setSneak`, `setLook`, `releaseAll`, `consumeLook`, getters).
- Produces: `DemoScript` with constructor `DemoScript(boolean guiStunts)`, `DemoScript.Stunt tick(float currentYaw, ControlState state)` (enum `Stunt { NONE, OPEN_INVENTORY, CLOSE_SCREEN }`), `boolean isDone()`. Task 3's wiring calls exactly these.

Timeline (client ticks, 20/s): WAIT 100 (5 s settle after world join) → WALK_1 60 (sprint+forward; if guiStunts: OPEN_INVENTORY at walk tick 20, CLOSE_SCREEN at 40) → TURN 1 (release movement, queue look at yaw+90°) → WALK_2 60 (sneak+forward) → COAST 40 (all released but applier still engaged — drift here means stuck state) → DONE.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/prattlemob/marionette/control/DemoScriptTest.java`:

```java
package com.prattlemob.marionette.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DemoScriptTest {
    private static final float YAW = 30.0F;

    /** Run n ticks, collecting any non-NONE stunts. */
    private static java.util.List<DemoScript.Stunt> run(DemoScript demo, ControlState state, int ticks) {
        java.util.List<DemoScript.Stunt> stunts = new java.util.ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            DemoScript.Stunt stunt = demo.tick(YAW, state);
            if (stunt != DemoScript.Stunt.NONE) {
                stunts.add(stunt);
            }
        }
        return stunts;
    }

    @Test
    void waitsBeforeMoving() {
        DemoScript demo = new DemoScript(false);
        ControlState state = new ControlState();
        run(demo, state, 99);
        assertFalse(state.anyHeld());
        demo.tick(YAW, state);
        assertTrue(state.forward());
        assertTrue(state.sprint());
    }

    @Test
    void turnsNinetyDegreesBetweenLegs() {
        DemoScript demo = new DemoScript(false);
        ControlState state = new ControlState();
        run(demo, state, 100 + 60); // WAIT + WALK_1 complete
        assertFalse(state.forward());
        assertFalse(state.sprint());
        assertEquals(new ControlState.Look(YAW + 90.0F, 0.0F), state.consumeLook());
        demo.tick(YAW, state); // TURN tick starts leg two
        assertTrue(state.forward());
        assertTrue(state.sneak());
    }

    @Test
    void releasesThenCoastsThenFinishes() {
        DemoScript demo = new DemoScript(false);
        ControlState state = new ControlState();
        run(demo, state, 100 + 60 + 1 + 60); // through WALK_2
        assertFalse(state.anyHeld());
        assertFalse(demo.isDone());
        run(demo, state, 40); // COAST
        assertTrue(demo.isDone());
        assertFalse(state.anyHeld());
    }

    @Test
    void guiStuntsFireAtWalkTicks20And40OnlyWhenEnabled() {
        DemoScript demo = new DemoScript(true);
        ControlState state = new ControlState();
        run(demo, state, 100); // WAIT
        var stunts = run(demo, state, 60); // WALK_1
        assertEquals(java.util.List.of(DemoScript.Stunt.OPEN_INVENTORY, DemoScript.Stunt.CLOSE_SCREEN), stunts);

        DemoScript quiet = new DemoScript(false);
        ControlState quietState = new ControlState();
        assertEquals(0, run(quiet, quietState, 300).size());
    }

    @Test
    void staysDoneAndInertAfterFinishing() {
        DemoScript demo = new DemoScript(false);
        ControlState state = new ControlState();
        run(demo, state, 400);
        assertTrue(demo.isDone());
        assertEquals(DemoScript.Stunt.NONE, demo.tick(YAW, state));
        assertFalse(state.anyHeld());
        assertNull(state.consumeLook());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.prattlemob.marionette.control.DemoScriptTest'`
Expected: compilation FAILS — cannot find symbol `DemoScript`.

- [ ] **Step 3: Implement DemoScript**

`src/main/java/com/prattlemob/marionette/control/DemoScript.java`:

```java
package com.prattlemob.marionette.control;

/**
 * Temporary hardcoded M1.1 demo: after a settle delay, sprint-walk forward,
 * turn 90° right, sneak-walk, then release and coast (any drift while
 * coasting means stuck controls). Pure logic — no Minecraft imports — so the
 * phase machine is unit-testable; the wiring executes returned {@link Stunt}s.
 * Armed only when {@code -Dmarionette.demo=true}; slated for removal or
 * permanent disablement after M1.3.
 */
public final class DemoScript {
    /** Side effects the wiring must perform (GUI stunts for the D4 criteria). */
    public enum Stunt { NONE, OPEN_INVENTORY, CLOSE_SCREEN }

    private static final long WAIT_TICKS = 100;
    private static final long WALK_TICKS = 60;
    private static final long COAST_TICKS = 40;
    private static final long OPEN_INVENTORY_AT = 20;
    private static final long CLOSE_SCREEN_AT = 40;

    private enum Phase { WAIT, WALK_1, TURN, WALK_2, COAST, DONE }

    private final boolean guiStunts;
    private Phase phase = Phase.WAIT;
    private long phaseTicks;

    public DemoScript(boolean guiStunts) {
        this.guiStunts = guiStunts;
    }

    public boolean isDone() {
        return phase == Phase.DONE;
    }

    /**
     * Advance one client tick, mutating {@code state} in place.
     * {@code currentYaw} is the player's yaw this tick.
     */
    public Stunt tick(float currentYaw, ControlState state) {
        phaseTicks++;
        switch (phase) {
            case WAIT:
                if (phaseTicks >= WAIT_TICKS) {
                    state.setForward(true);
                    state.setSprint(true);
                    enter(Phase.WALK_1);
                }
                return Stunt.NONE;
            case WALK_1:
                if (guiStunts && phaseTicks == OPEN_INVENTORY_AT) {
                    return Stunt.OPEN_INVENTORY;
                }
                if (guiStunts && phaseTicks == CLOSE_SCREEN_AT) {
                    return Stunt.CLOSE_SCREEN;
                }
                if (phaseTicks >= WALK_TICKS) {
                    state.setForward(false);
                    state.setSprint(false);
                    state.setLook(currentYaw + 90.0F, 0.0F);
                    enter(Phase.TURN);
                }
                return Stunt.NONE;
            case TURN:
                state.setForward(true);
                state.setSneak(true);
                enter(Phase.WALK_2);
                return Stunt.NONE;
            case WALK_2:
                if (phaseTicks >= WALK_TICKS) {
                    state.releaseAll();
                    enter(Phase.COAST);
                }
                return Stunt.NONE;
            case COAST:
                if (phaseTicks >= COAST_TICKS) {
                    enter(Phase.DONE);
                }
                return Stunt.NONE;
            default:
                return Stunt.NONE;
        }
    }

    private void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.prattlemob.marionette.control.DemoScriptTest'`
Expected: 5 tests PASS. If `turnsNinetyDegreesBetweenLegs` fails on an off-by-one, recount: WALK_1's transition happens on its 60th tick, i.e. overall tick 160 with the WAIT transition consuming tick 100 (phaseTicks resets on `enter`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/prattlemob/marionette/control/DemoScript.java src/test/java/com/prattlemob/marionette/control/DemoScriptTest.java
git commit -m "Add hardcoded walk-turn-walk demo state machine (M1.1)"
```

---

### Task 3: Applier seam, KeyMapping variant, tick wiring, first rendered actuation

**Files:**
- Create: `src/main/java/com/prattlemob/marionette/control/ControlStateApplier.java`
- Create: `src/main/java/com/prattlemob/marionette/control/KeyMappingApplier.java`
- Modify: `src/main/java/com/prattlemob/marionette/MarionetteClient.java` (full replacement shown)
- Modify: `build.gradle` (add `clientDemo` run config inside `neoForge.runs`)
- Create: `scripts/close-minecraft-window.sh`
- No JUnit here: everything in this task touches live-client classes; verification is the scripted in-game run.

**Interfaces:**
- Consumes: `ControlState`, `DemoScript` (Tasks 1–2).
- Produces: `ControlStateApplier` interface — `void apply(ControlState state)`, `void release()` — the D4 seam Task 4 implements again; `MarionetteClient` wiring that Task 8 extends (fields `controlState`, `applier`, `demo`, `controlsEngaged`; methods `releaseControls()`, `executeStunt(...)`, handler `onClientTickPre`).

- [ ] **Step 1: Create the applier seam**

`src/main/java/com/prattlemob/marionette/control/ControlStateApplier.java`:

```java
package com.prattlemob.marionette.control;

/**
 * Injects a {@link ControlState} into the running client, once per client
 * tick (pre). The D4 experiment lives behind this seam: two implementations
 * are prototyped, the winner stays. Implementations may touch game state and
 * are therefore client-tick-thread-only.
 */
public interface ControlStateApplier {
    /** Impose {@code state} on the player's input for this tick. */
    void apply(ControlState state);

    /** Return input to vanilla immediately; must never leave stuck state. */
    void release();
}
```

- [ ] **Step 2: Implement the KeyMapping-forcing variant**

`src/main/java/com/prattlemob/marionette/control/KeyMappingApplier.java`:

```java
package com.prattlemob.marionette.control;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * D4 variant 1: force the pressed state of the vanilla movement key
 * mappings. Known risk (probed by the experiment, not worked around here):
 * keyShift/keySprint are ToggleKeyMappings — with toggle-crouch/-sprint
 * enabled, setDown(true) toggles instead of holding and setDown(false)
 * no-ops, so this variant may flicker or leak stuck toggle state.
 */
public final class KeyMappingApplier implements ControlStateApplier {
    @Override
    public void apply(ControlState state) {
        Options options = Minecraft.getInstance().options;
        options.keyUp.setDown(state.forward());
        options.keyDown.setDown(state.back());
        options.keyLeft.setDown(state.left());
        options.keyRight.setDown(state.right());
        options.keyJump.setDown(state.jump());
        options.keyShift.setDown(state.sneak());
        options.keySprint.setDown(state.sprint());
    }

    @Override
    public void release() {
        Options options = Minecraft.getInstance().options;
        for (KeyMapping key : new KeyMapping[] {
                options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
                options.keyJump, options.keyShift, options.keySprint }) {
            key.setDown(false);
        }
    }
}
```

- [ ] **Step 3: Rewire MarionetteClient**

Replace `src/main/java/com/prattlemob/marionette/MarionetteClient.java` entirely with:

```java
package com.prattlemob.marionette;

import com.prattlemob.marionette.control.ControlState;
import com.prattlemob.marionette.control.ControlStateApplier;
import com.prattlemob.marionette.control.DemoScript;
import com.prattlemob.marionette.control.KeyMappingApplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-side lifecycle anchor and tick-loop orchestrator. Owns the single
 * {@link ControlState}, applies it through the {@link ControlStateApplier}
 * each tick (pre), and enforces the safety rule: whenever nothing should be
 * controlling the player, all controls release immediately.
 *
 * This class never loads on dedicated servers ({@code dist = Dist.CLIENT}),
 * so client-only code is safe to reference from here.
 */
@Mod(value = Marionette.MODID, dist = Dist.CLIENT)
public class MarionetteClient {
    /** How often (in ticks) to emit the heartbeat log line: 100 ticks = 5 s. */
    private static final long TICK_LOG_INTERVAL = 100;
    /** How often (in ticks) to log puppet position evidence while controlled. */
    private static final long PUPPET_LOG_INTERVAL = 20;

    private static MarionetteClient instance;

    private final ControlState controlState = new ControlState();
    private final ControlStateApplier applier = createApplier();

    private boolean inWorld;
    private long ticksInWorld;
    private DemoScript demo;
    private boolean controlsEngaged;

    public MarionetteClient(ModContainer container) {
        instance = this;
        NeoForge.EVENT_BUS.addListener(this::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
    }

    /** The singleton, or {@code null} until FML constructs the mod during client startup. */
    public static MarionetteClient instance() {
        return instance;
    }

    /** D4 experiment switch; the winner gets hardcoded when D4 is decided. */
    private static ControlStateApplier createApplier() {
        String variant = System.getProperty("marionette.applier", "keymapping");
        Marionette.LOGGER.info("Control applier: {}", variant);
        return switch (variant) {
            case "keymapping" -> new KeyMappingApplier();
            default -> throw new IllegalArgumentException("Unknown marionette.applier: " + variant);
        };
    }

    public boolean isInWorld() {
        return inWorld;
    }

    /** Client ticks since login; runs continuously across dimension changes and respawns. */
    public long ticksInWorld() {
        return ticksInWorld;
    }

    private void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!inWorld || player == null) {
            return;
        }
        if (demo != null) {
            DemoScript.Stunt stunt = demo.tick(player.getYRot(), controlState);
            executeStunt(minecraft, player, stunt);
            if (demo.isDone()) {
                demo = null;
                Marionette.LOGGER.info("Demo complete");
            }
        }
        boolean shouldControl = demo != null;
        if (shouldControl) {
            ControlState.Look look = controlState.consumeLook();
            if (look != null) {
                player.setYRot(look.yaw());
                player.setXRot(look.pitch());
            }
            applier.apply(controlState);
            controlsEngaged = true;
        } else if (controlsEngaged) {
            releaseControls();
        }
    }

    private void executeStunt(Minecraft minecraft, LocalPlayer player, DemoScript.Stunt stunt) {
        switch (stunt) {
            case OPEN_INVENTORY -> {
                minecraft.setScreen(new InventoryScreen(player));
                Marionette.LOGGER.info("Demo stunt: opened inventory");
            }
            case CLOSE_SCREEN -> {
                minecraft.setScreen(null);
                Marionette.LOGGER.info("Demo stunt: closed screen");
            }
            case NONE -> { }
        }
    }

    /** The safety rule: neutral ControlState, applier released, evidence logged. */
    private void releaseControls() {
        controlState.releaseAll();
        applier.release();
        controlsEngaged = false;
        Marionette.LOGGER.info("Controls released; vanilla input restored");
    }

    private void onClientTickPost(ClientTickEvent.Post event) {
        if (!inWorld) {
            return;
        }
        ticksInWorld++;
        if (ticksInWorld % TICK_LOG_INTERVAL == 0) {
            Marionette.LOGGER.info("Client tick {} in world", ticksInWorld);
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (controlsEngaged && player != null && ticksInWorld % PUPPET_LOG_INTERVAL == 0) {
            Marionette.LOGGER.info(String.format("Puppet pos %.2f %.2f %.2f yaw %.1f",
                    player.getX(), player.getY(), player.getZ(), player.getYRot()));
        }
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        inWorld = true;
        ticksInWorld = 0;
        if (Boolean.getBoolean("marionette.demo")) {
            demo = new DemoScript(Boolean.getBoolean("marionette.demo.gui"));
            Marionette.LOGGER.info("Demo armed (gui stunts: {})", Boolean.getBoolean("marionette.demo.gui"));
        }
        Marionette.LOGGER.info("Entered world");
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // LoggingOut also fires with a null player during the defensive
        // disconnect that precedes creating an integrated server or joining a
        // multiplayer server; only a non-null player marks a real session end.
        if (event.getPlayer() == null) {
            return;
        }
        inWorld = false;
        demo = null;
        if (controlsEngaged) {
            releaseControls();
        }
        Marionette.LOGGER.info("Left world");
    }
}
```

- [ ] **Step 4: Add the clientDemo run config**

In `build.gradle`, inside `neoForge { runs { ... } }`, after the existing `client` block:

```groovy
        // Scripted-verification run: auto-joins the Phase1Verify world and
        // arms the hardcoded demo. The three properties below are edited per
        // D4 experiment run (variant / gui stunts).
        clientDemo {
            client()
            programArguments.addAll '--quickPlaySingleplayer', 'Phase1Verify'
            systemProperty 'marionette.demo', 'true'
            systemProperty 'marionette.demo.gui', 'false'
            systemProperty 'marionette.applier', 'keymapping'
        }
```

- [ ] **Step 5: Add the window-close helper**

`scripts/close-minecraft-window.sh` (then `chmod +x` it):

```bash
#!/usr/bin/env bash
# Close the running Minecraft dev-client window via KWin scripting.
# The client ignores SIGTERM; a window close performs a clean shutdown
# (fires the real LoggingOut event, saves the world). KDE Plasma 6 only.
set -euo pipefail

script=$(mktemp --suffix=.js)
trap 'rm -f "$script"' EXIT
cat > "$script" <<'EOF'
for (const w of workspace.windowList()) {
    if (w.caption.indexOf("Minecraft") !== -1) {
        w.closeWindow();
    }
}
EOF

id=$(qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.loadScript "$script" marionette-close)
qdbus6 org.kde.KWin "/Scripting/Script${id}" org.kde.kwin.Script.run
qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.unloadScript marionette-close
```

- [ ] **Step 6: Generate the verification world (one-time)**

A deterministic superflat creative world avoids terrain/mob interference
across the many runs this phase does. Generate it with the dedicated-server
run, then move it into the client's saves:

```bash
cat > run/eula.txt <<'EOF'
eula=true
EOF
cat > run/server.properties <<'EOF'
level-name=Phase1Verify
level-type=minecraft\:flat
gamemode=creative
difficulty=peaceful
online-mode=false
spawn-protection=0
EOF
# Let the server generate the world, then stop it (it handles SIGTERM cleanly):
timeout --signal=TERM 90 ./gradlew runServer --console=plain || true
mkdir -p run/saves
cp -r run/Phase1Verify run/saves/Phase1Verify
```

Verify: `ls run/saves/Phase1Verify/level.dat` exists. (Fallback if the server
route misbehaves: `cp -r run/saves/Phase0Verify run/saves/Phase1Verify` — an
existing world from Phase 0 — and accept non-flat terrain.)

- [ ] **Step 7: Compile check**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Scripted in-game verification (M1.1 KeyMapping half)**

Use the harness recipe from the plan header: launch `./gradlew runClientDemo`
in the background, poll `run/logs/latest.log` for `Demo complete` (timeout
5 min), run `scripts/close-minecraft-window.sh`, then analyze:

```bash
grep -E "Control applier|Demo armed|Entered world|Puppet pos|Demo complete|Controls released|Left world" run/logs/latest.log
```

Expected evidence, in order:
1. `Control applier: keymapping`, `Demo armed (gui stunts: false)`, `Entered world`.
2. Three `Puppet pos` lines during WALK_1 with horizontal position advancing (sprint ≈ 5.6 m/s → ≈ 5–6 blocks between 20-tick samples), constant yaw.
3. Yaw exactly +90 from the WALK_1 value on subsequent lines; slower advance during WALK_2 (sneak ≈ 1.3 m/s → ≈ 1–1.5 blocks/sample) along the perpendicular axis.
4. COAST samples: position change < 0.5 blocks between samples (stopping momentum only), then `Demo complete` followed by `Controls released; vanilla input restored`.
5. Clean `Left world` from the window close.

If position doesn't advance, debug before proceeding (superpowers:systematic-debugging) — this is the milestone gate risk, not a formality.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/prattlemob/marionette build.gradle scripts/close-minecraft-window.sh
git commit -m "Wire tick-loop control injection with KeyMapping applier and demo run (M1.1)"
```

---

### Task 4: Mixin injection variant

**Files:**
- Create: `src/main/java/com/prattlemob/marionette/mixin/KeyboardInputMixin.java`
- Create: `src/main/java/com/prattlemob/marionette/control/MixinInputApplier.java`
- Modify: `src/main/resources/marionette.mixins.json`
- Modify: `src/main/java/com/prattlemob/marionette/MarionetteClient.java` (one method)

**Interfaces:**
- Consumes: `ControlStateApplier`, `ControlState` (Tasks 1, 3).
- Produces: `MixinInputApplier` with `static ControlState activeState()` (used by the mixin); selectable via `-Dmarionette.applier=mixin`.

- [ ] **Step 1: Implement the applier (the mixin's switchboard)**

`src/main/java/com/prattlemob/marionette/control/MixinInputApplier.java`:

```java
package com.prattlemob.marionette.control;

/**
 * D4 variant 2: publish the active {@link ControlState} for the
 * KeyboardInput mixin to merge into the player's semantic input each input
 * tick. Manipulates meaning rather than faking key presses, so vanilla key
 * state stays visible underneath (human keys OR-merge with agent controls).
 */
public final class MixinInputApplier implements ControlStateApplier {
    private static volatile ControlState active;

    /** Read by the mixin on the client tick thread; null = agent inactive. */
    public static ControlState activeState() {
        return active;
    }

    @Override
    public void apply(ControlState state) {
        active = state;
    }

    @Override
    public void release() {
        active = null;
    }
}
```

- [ ] **Step 2: Implement the mixin**

`src/main/java/com/prattlemob/marionette/mixin/KeyboardInputMixin.java`:

```java
package com.prattlemob.marionette.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.prattlemob.marionette.control.ControlState;
import com.prattlemob.marionette.control.MixinInputApplier;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * D4 variant 2: after vanilla populates the semantic input from key state,
 * OR-merge the agent's held controls in and recompute the move vector.
 * Extends ClientInput (the mixin target's superclass) for access to the
 * protected moveVector field.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void marionette$mergeAgentInput(CallbackInfo ci) {
        ControlState state = MixinInputApplier.activeState();
        if (state == null) {
            return;
        }
        this.keyPresses = new Input(
                this.keyPresses.forward() || state.forward(),
                this.keyPresses.backward() || state.back(),
                this.keyPresses.left() || state.left(),
                this.keyPresses.right() || state.right(),
                this.keyPresses.jump() || state.jump(),
                this.keyPresses.shift() || state.sneak(),
                this.keyPresses.sprint() || state.sprint());
        float forwardImpulse = this.keyPresses.forward() == this.keyPresses.backward()
                ? 0.0F : (this.keyPresses.forward() ? 1.0F : -1.0F);
        float leftImpulse = this.keyPresses.left() == this.keyPresses.right()
                ? 0.0F : (this.keyPresses.left() ? 1.0F : -1.0F);
        this.moveVector = new Vec2(leftImpulse, forwardImpulse).normalized();
    }
}
```

- [ ] **Step 3: Register the mixin**

`src/main/resources/marionette.mixins.json` — add the `client` array:

```json
{
  "required": true,
  "package": "com.prattlemob.marionette.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [],
  "client": [
    "KeyboardInputMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  },
  "overwrites": {
    "requireAnnotations": true
  }
}
```

- [ ] **Step 4: Add the variant to the applier switch**

In `MarionetteClient.createApplier()`, add the `mixin` case:

```java
    /** D4 experiment switch; the winner gets hardcoded when D4 is decided. */
    private static ControlStateApplier createApplier() {
        String variant = System.getProperty("marionette.applier", "keymapping");
        Marionette.LOGGER.info("Control applier: {}", variant);
        return switch (variant) {
            case "keymapping" -> new KeyMappingApplier();
            case "mixin" -> new MixinInputApplier();
            default -> throw new IllegalArgumentException("Unknown marionette.applier: " + variant);
        };
    }
```

(add `import com.prattlemob.marionette.control.MixinInputApplier;`)

- [ ] **Step 5: Compile check**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. (A mixin target mismatch fails at *runtime*, not compile — the next step catches that.)

- [ ] **Step 6: Scripted in-game verification (mixin variant)**

Edit `build.gradle`'s `clientDemo` run: `systemProperty 'marionette.applier', 'mixin'`. Run the harness recipe (launch, await `Demo complete`, close window), then:

```bash
grep -E "Control applier|Puppet pos|Demo complete|Controls released|ERROR.*[Mm]ixin|Mixin apply failed" run/logs/latest.log
```

Expected: `Control applier: mixin`; the same movement evidence pattern as Task 3 Step 8 (sprint-speed leg, +90 yaw, sneak-speed leg, stationary COAST, `Demo complete`, `Controls released`); **no** mixin apply errors anywhere in the log.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/prattlemob/marionette src/main/resources/marionette.mixins.json build.gradle
git commit -m "Add input-path mixin injection variant for the D4 experiment (M1.1)"
```

---

### Task 5: Run the D4 experiment, record the decision, delete the loser

**Files:**
- Modify: `docs/decisions.md` (D4 section)
- Modify: `ROADMAP.md` (M1.1 checkboxes)
- Modify: `src/main/java/com/prattlemob/marionette/MarionetteClient.java` (hardcode winner)
- Delete: the losing applier (and mixin + mixins.json `client` entry, if the mixin loses)

**Interfaces:**
- Consumes: both appliers, `clientDemo` run config, harness.
- Produces: a settled D4; exactly one `ControlStateApplier` implementation remains, constructed directly (no `marionette.applier` property).

This task is experiment-driven, not TDD. Each scripted run: edit the
`clientDemo` properties in `build.gradle`, launch, await `Demo complete`
(or failure), close window, archive the log
(`cp run/logs/latest.log <scratchpad>/d4-<variant>-<scenario>.log`) before the
next run overwrites it.

- [ ] **Step 1: Scripted grid — six runs**

For each variant `V` in {`keymapping`, `mixin`} × scenario in {plain, gui, toggles}:

- *plain*: `marionette.demo.gui=false`, default `run/options.txt` — baseline movement evidence (already captured in Tasks 3/4; re-run only if those logs weren't archived).
- *gui*: `marionette.demo.gui=true` — expect `Demo stunt: opened inventory` / `closed screen` mid-WALK_1; afterwards the run must still reach a stationary COAST and `Controls released`. Note (data, not failure): whether movement continues or halts while the inventory is open — the variants are expected to differ here.
- *toggles*: `marionette.demo.gui=false`, and before launching append toggle settings to the client's options file:
  ```bash
  # run/options.txt is created on first client run; set toggle modes:
  sed -i 's/^toggleCrouch:.*/toggleCrouch:true/; s/^toggleSprint:.*/toggleSprint:true/' run/options.txt
  grep -E "^toggle(Crouch|Sprint)" run/options.txt   # must print both set to true;
  # if either line is missing entirely, append it:
  #   printf 'toggleCrouch:true\ntoggleSprint:true\n' >> run/options.txt
  ```
  Expected divergence: the KeyMapping variant calls `ToggleKeyMapping.setDown(true)` every tick, which *toggles per tick* — watch WALK_1 speed (sprint flicker) and WALK_2 speed (sneak flicker), and above all the COAST/post-demo state: `setDown(false)` no-ops for toggle keys, so sneak/sprint may stay latched after release (**stuck-state criterion**). Evidence of latched sneak after `Controls released`: player pose/speed in a follow-up manual check, or the next run starting slow. The mixin variant reads ControlState directly and should be immune. Reset `run/options.txt` toggles to `false` after the runs.

Judge each run against the D4 criteria: (a) stuck state after release, (b) GUI open/close survival, (c) toggle-logic correctness.

- [ ] **Step 2: Human checklist — coexistence and focus loss** ⚠️ NEEDS-HUMAN

Ask the user to run, for each variant (`./gradlew runClientDemo` with the variant set; they play at the keyboard during the demo):
1. Press W and S mid-WALK_1 — observe and note the behavior (expected: KeyMapping variant ignores human keys entirely while engaged; mixin variant lets human input *add* via OR-merge but not counter). "Sane, defined behavior" is the bar.
2. Alt-tab away and back mid-walk — the demo must continue or stop *cleanly* (Minecraft calls `KeyMapping.releaseAll()` on focus loss — expected to fight the KeyMapping variant, be invisible to the mixin) and end with a clean release.

Record their observations verbatim for the decision record.

- [ ] **Step 3: Decide and record in docs/decisions.md**

Outcome rules from the spec: both pass all criteria → mixin wins (cleaner
M5.1 override story). One passes → it wins. Both fail any criterion → STOP,
present findings to the user, do not proceed to Task 6.

Rewrite the D4 section of `docs/decisions.md` following the existing house style for settled decisions, containing: status `**Settled** (M1.1 experiment, <date>)`; the winner; per-criterion observed results for both variants (from the archived logs and the human checklist, including the toggle-key and focus-loss behavior actually seen); the loser's concrete failure mode; and the note that the human-override semantics observed here feed the M5.1 precedence policy.

- [ ] **Step 4: Hardcode the winner, delete the loser**

If the mixin wins (expected): in `MarionetteClient`, replace `createApplier()` with a direct `new MixinInputApplier()` field initializer, delete the method, delete `KeyMappingApplier.java`. If the KeyMapping variant wins: direct `new KeyMappingApplier()`, delete `MixinInputApplier.java`, `KeyboardInputMixin.java`, and the `client` array entry in `marionette.mixins.json`. Either way, delete the `systemProperty 'marionette.applier', ...` line from `clientDemo` in `build.gradle` and remove the `Control applier:` log line.

- [ ] **Step 5: Verify the winner still walks**

Run: `./gradlew build` (expect BUILD SUCCESSFUL), then one more harness run
(plain scenario) confirming the Task 3 Step 8 evidence pattern end-to-end.

- [ ] **Step 6: Tick the M1.1 boxes and commit**

In `ROADMAP.md`, mark M1.1 items 1–5 complete (`ControlState`, D4 experiment,
movement injection, raw camera injection, release-all). Leave the demo-script
item (`deleted/disabled after M1.3`) unticked until Task 10.

```bash
git add -A
git commit -m "Settle D4 from the injection experiment; keep the winning applier (M1.1)"
```

---

### Task 6: Protocol v0 inbound parsing

**Files:**
- Create: `src/main/java/com/prattlemob/marionette/bridge/AgentCommand.java`
- Create: `src/main/java/com/prattlemob/marionette/bridge/ProtocolException.java`
- Create: `src/main/java/com/prattlemob/marionette/bridge/CommandParser.java`
- Test: `src/test/java/com/prattlemob/marionette/bridge/CommandParserTest.java`

**Interfaces:**
- Consumes: Gson (already on the compile classpath via NeoForge).
- Produces: `AgentCommand` sealed interface with nested records `Hello(int version)`, `InputUpdate(Boolean forward, Boolean back, Boolean left, Boolean right, Boolean jump, Boolean sneak, Boolean sprint)` (null = unchanged), `Look(float yaw, float pitch)`, `Release()`; `CommandParser.parse(String) -> AgentCommand` throwing `ProtocolException(String message)` (unchecked). Tasks 7–8 rely on these exact shapes.

No Minecraft imports anywhere in this package — enforced constraint.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/prattlemob/marionette/bridge/CommandParserTest.java`:

```java
package com.prattlemob.marionette.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommandParserTest {
    @Test
    void parsesHello() {
        AgentCommand command = CommandParser.parse("{\"type\": \"hello\", \"version\": 0}");
        assertEquals(new AgentCommand.Hello(0), command);
    }

    @Test
    void parsesPartialInputWithOmittedFieldsNull() {
        AgentCommand command = CommandParser.parse("{\"type\": \"input\", \"forward\": true, \"sprint\": false}");
        AgentCommand.InputUpdate update = assertInstanceOf(AgentCommand.InputUpdate.class, command);
        assertEquals(Boolean.TRUE, update.forward());
        assertEquals(Boolean.FALSE, update.sprint());
        assertNull(update.back());
        assertNull(update.left());
        assertNull(update.right());
        assertNull(update.jump());
        assertNull(update.sneak());
    }

    @Test
    void parsesLookAndRelease() {
        assertEquals(new AgentCommand.Look(90.0F, -12.5F),
                CommandParser.parse("{\"type\": \"look\", \"yaw\": 90.0, \"pitch\": -12.5}"));
        assertEquals(new AgentCommand.Release(),
                CommandParser.parse("{\"type\": \"release\"}"));
    }

    @Test
    void ignoresUnknownFields() {
        AgentCommand command = CommandParser.parse("{\"type\": \"release\", \"extra\": 42}");
        assertEquals(new AgentCommand.Release(), command);
    }

    @Test
    void rejectsGarbage() {
        assertThrows(ProtocolException.class, () -> CommandParser.parse("garbage"));
        assertThrows(ProtocolException.class, () -> CommandParser.parse("{\"type\": \"input\", \"forward\":"));
        assertThrows(ProtocolException.class, () -> CommandParser.parse("[1, 2, 3]"));
    }

    @Test
    void rejectsMissingOrUnknownType() {
        ProtocolException missing = assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"forward\": true}"));
        assertTrue(missing.getMessage().contains("type"));
        ProtocolException unknown = assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"type\": \"fly\"}"));
        assertTrue(unknown.getMessage().contains("fly"));
    }

    @Test
    void rejectsWrongFieldTypes() {
        assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"type\": \"input\", \"forward\": \"yes\"}"));
        assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"type\": \"look\", \"yaw\": \"north\", \"pitch\": 0}"));
        assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"type\": \"look\", \"yaw\": 0}")); // pitch missing
        assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"type\": \"hello\"}")); // version missing
    }

    @Test
    void rejectsNonFiniteLook() {
        assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"type\": \"look\", \"yaw\": \"NaN\", \"pitch\": 0}"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.prattlemob.marionette.bridge.CommandParserTest'`
Expected: compilation FAILS — package `com.prattlemob.marionette.bridge` does not exist.

- [ ] **Step 3: Implement the three types**

`src/main/java/com/prattlemob/marionette/bridge/ProtocolException.java`:

```java
package com.prattlemob.marionette.bridge;

/**
 * A malformed or invalid agent message. Thrown on the network thread and
 * answered with a protocol error frame; never crosses to the tick thread.
 */
public class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }
}
```

`src/main/java/com/prattlemob/marionette/bridge/AgentCommand.java`:

```java
package com.prattlemob.marionette.bridge;

/**
 * A validated protocol v0 message from the agent. Parsed on the network
 * thread, applied on the client tick thread. See protocol/v0-draft.md.
 */
public sealed interface AgentCommand {
    /** Handshake opener; must be the first message on a connection. */
    record Hello(int version) implements AgentCommand {}

    /** Partial set-and-hold update; a null field means "unchanged". */
    record InputUpdate(Boolean forward, Boolean back, Boolean left, Boolean right,
                       Boolean jump, Boolean sneak, Boolean sprint) implements AgentCommand {}

    /** Raw instant camera set (smoothing arrives in M3.2). */
    record Look(float yaw, float pitch) implements AgentCommand {}

    /** Release every held control immediately. */
    record Release() implements AgentCommand {}
}
```

`src/main/java/com/prattlemob/marionette/bridge/CommandParser.java`:

```java
package com.prattlemob.marionette.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Parses protocol v0 text frames into {@link AgentCommand}s. Network-thread code. */
public final class CommandParser {
    private CommandParser() {}

    public static AgentCommand parse(String text) {
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                throw new ProtocolException("message must be a JSON object");
            }
            json = element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new ProtocolException("malformed JSON");
        }
        String type = optionalString(json, "type");
        if (type == null) {
            throw new ProtocolException("missing \"type\"");
        }
        return switch (type) {
            case "hello" -> new AgentCommand.Hello(requiredInt(json, "version"));
            case "input" -> new AgentCommand.InputUpdate(
                    optionalBoolean(json, "forward"),
                    optionalBoolean(json, "back"),
                    optionalBoolean(json, "left"),
                    optionalBoolean(json, "right"),
                    optionalBoolean(json, "jump"),
                    optionalBoolean(json, "sneak"),
                    optionalBoolean(json, "sprint"));
            case "look" -> new AgentCommand.Look(
                    requiredFiniteFloat(json, "yaw"),
                    requiredFiniteFloat(json, "pitch"));
            case "release" -> new AgentCommand.Release();
            default -> throw new ProtocolException("unknown type: " + type);
        };
    }

    private static String optionalString(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            return null;
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new ProtocolException("field \"" + name + "\" must be a string");
        }
        return primitive.getAsString();
    }

    private static Boolean optionalBoolean(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            return null;
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
            throw new ProtocolException("field \"" + name + "\" must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    private static int requiredInt(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            throw new ProtocolException("missing \"" + name + "\"");
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new ProtocolException("field \"" + name + "\" must be a number");
        }
        return primitive.getAsInt();
    }

    private static float requiredFiniteFloat(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            throw new ProtocolException("missing \"" + name + "\"");
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new ProtocolException("field \"" + name + "\" must be a number");
        }
        float value = primitive.getAsFloat();
        if (!Float.isFinite(value)) {
            throw new ProtocolException("field \"" + name + "\" must be finite");
        }
        return value;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.prattlemob.marionette.bridge.CommandParserTest'`
Expected: 8 tests PASS. Note: Gson's `JsonParser.parseString` is lenient — a
bare word like `garbage` may parse as a string primitive rather than throw;
the `isJsonObject` check catches it either way. If `rejectsNonFiniteLook`
fails because lenient Gson accepted the string `"NaN"` as a number, the
`isNumber()` check already rejects it (it is a string primitive) — if instead
a bare `NaN` literal arrives it parses as a number and `Float.isFinite`
rejects it. Both paths must throw.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/prattlemob/marionette/bridge src/test/java/com/prattlemob/marionette/bridge
git commit -m "Add protocol v0 command model and validating parser (M1.2)"
```

---

### Task 7: BridgeServer with headless integration tests

**Files:**
- Modify: `build.gradle` (Jar-in-Jar dependency; test modding classpath)
- Create: `src/main/java/com/prattlemob/marionette/bridge/BridgeServer.java`
- Test: `src/test/java/com/prattlemob/marionette/bridge/BridgeServerTest.java`

**Interfaces:**
- Consumes: `AgentCommand`, `CommandParser`, `ProtocolException` (Task 6); Netty core (MC classpath) + `netty-codec-http` (added here); Gson.
- Produces: `BridgeServer` with: constructor `BridgeServer(int port, String modVersion)` (port 0 = ephemeral, for tests), `void start()`, `int port()`, `boolean hasController()`, `boolean pollDisconnected()`, `List<AgentCommand> drainCommands()`, `void sendObservation(String json)`, `void stop()`, constants `PROTOCOL_VERSION = 0` and `DEFAULT_PORT = 24680`. Task 8's wiring uses exactly these.

- [ ] **Step 1: Add the D1a dependency and test classpath**

In `build.gradle` `dependencies` block (see D1a in `docs/decisions.md`: MC
ships no `netty-codec-http`; version-matched, non-transitive so no Netty core
classes are duplicated in the jar):

```groovy
    // D1a: MC bundles only Netty core; the WebSocket codecs live in
    // netty-codec-http, so we jar-in-jar exactly the version matching MC's
    // Netty (4.1.118.Final on 1.21.8). transitive=false keeps the bundled
    // jar codec-only.
    jarJar(implementation('io.netty:netty-codec-http')) {
        version {
            strictly '[4.1, 4.2)'
            prefer '4.1.118.Final'
        }
        transitive = false
    }
```

and inside the `neoForge { }` block add:

```groovy
    // Puts the modding runtime (Netty core, Gson, ...) on the unit-test
    // classpath so the bridge's headless integration tests can run.
    addModdingDependenciesTo sourceSets.test
```

Run: `./gradlew build` — expected BUILD SUCCESSFUL, and verify the bundled jar:
`unzip -l build/libs/marionette-0.1.0.jar | grep -E "jarjar|netty"` shows
`META-INF/jarjar/` containing exactly one Netty jar (codec-http), no others.

- [ ] **Step 2: Write the failing integration test**

`src/test/java/com/prattlemob/marionette/bridge/BridgeServerTest.java` —
uses the JDK's built-in WebSocket client (`java.net.http`), so the test adds
no dependencies:

```java
package com.prattlemob.marionette.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class BridgeServerTest {
    private static final String HELLO = "{\"type\": \"hello\", \"version\": 0}";

    private BridgeServer server;

    @BeforeEach
    void startServer() {
        server = new BridgeServer(0, "test-version");
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    /** Poll a condition until it holds or 5 s pass. */
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for condition");
            }
            Thread.sleep(10);
        }
    }

    /** Minimal JDK WebSocket client collecting text messages and the close code. */
    private static final class TestClient implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        WebSocket ws;

        static TestClient connect(int port) throws Exception {
            TestClient client = new TestClient();
            client.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/"), client)
                    .get(5, TimeUnit.SECONDS);
            return client;
        }

        void send(String text) {
            ws.sendText(text, true).join();
        }

        String awaitMessage() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "timed out waiting for a message");
            return message;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }
    }

    private TestClient connectAndHello() throws Exception {
        TestClient client = TestClient.connect(server.port());
        client.send(HELLO);
        JsonObject reply = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("hello", reply.get("type").getAsString());
        return client;
    }

    @Test
    void helloHandshakeRepliesWithVersionAndModVersion() throws Exception {
        TestClient client = TestClient.connect(server.port());
        assertFalse(server.hasController(), "not a controller before hello");
        client.send(HELLO);
        JsonObject reply = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("hello", reply.get("type").getAsString());
        assertEquals(0, reply.get("version").getAsInt());
        assertEquals("test-version", reply.get("mod").getAsString());
        await(server::hasController);
    }

    @Test
    void commandsAreQueuedInOrderForTheTickThread() throws Exception {
        TestClient client = connectAndHello();
        client.send("{\"type\": \"input\", \"forward\": true}");
        client.send("{\"type\": \"look\", \"yaw\": 90.0, \"pitch\": 0.0}");
        List<AgentCommand> drained = new java.util.ArrayList<>();
        await(() -> {
            drained.addAll(server.drainCommands());
            return drained.size() >= 2;
        });
        assertInstanceOf(AgentCommand.InputUpdate.class, drained.get(0));
        assertInstanceOf(AgentCommand.Look.class, drained.get(1));
    }

    @Test
    void nonHelloFirstMessageClosesTheConnection() throws Exception {
        TestClient client = TestClient.connect(server.port());
        client.send("{\"type\": \"input\", \"forward\": true}");
        assertEquals(1002, (int) client.closeCode.get(5, TimeUnit.SECONDS));
        assertFalse(server.hasController());
    }

    @Test
    void wrongVersionIsRejected() throws Exception {
        TestClient client = TestClient.connect(server.port());
        client.send("{\"type\": \"hello\", \"version\": 99}");
        assertEquals(1002, (int) client.closeCode.get(5, TimeUnit.SECONDS));
    }

    @Test
    void secondConnectionIsRefusedWhileControllerAttached() throws Exception {
        TestClient first = connectAndHello();
        TestClient second = TestClient.connect(server.port());
        assertEquals(1013, (int) second.closeCode.get(5, TimeUnit.SECONDS));
        // the original controller is unaffected:
        first.send("{\"type\": \"input\", \"forward\": true}");
        await(() -> {
            List<AgentCommand> drained = server.drainCommands();
            return drained.stream().anyMatch(c -> c instanceof AgentCommand.InputUpdate);
        });
    }

    @Test
    void malformedMessageGetsErrorReplyAndConnectionSurvives() throws Exception {
        TestClient client = connectAndHello();
        client.send("garbage");
        JsonObject error = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("error", error.get("type").getAsString());
        assertNotNull(error.get("message"));
        // still alive and functional:
        client.send("{\"type\": \"release\"}");
        await(() -> server.drainCommands().stream().anyMatch(c -> c instanceof AgentCommand.Release));
    }

    @Test
    void abruptDisconnectSignalsOnceAndAllowsReconnect() throws Exception {
        TestClient client = connectAndHello();
        client.ws.abort(); // no close frame — the kill -9 analogue
        await(server::pollDisconnected);
        assertFalse(server.pollDisconnected(), "signal is one-shot");
        await(() -> !server.hasController());
        // reconnect works without restarting the server:
        TestClient again = connectAndHello();
        await(server::hasController);
        again.ws.abort();
        await(server::pollDisconnected);
    }

    @Test
    void observationsReachTheAgentAndAreDroppedWhenNoController() throws Exception {
        server.sendObservation("{\"type\": \"observation\", \"tick\": 0}"); // no controller: silently dropped
        TestClient client = connectAndHello();
        server.sendObservation("{\"type\": \"observation\", \"tick\": 1}");
        JsonObject observation = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals(1, observation.get("tick").getAsInt());
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.prattlemob.marionette.bridge.BridgeServerTest'`
Expected: compilation FAILS — cannot find symbol `BridgeServer`.

- [ ] **Step 4: Implement BridgeServer**

`src/main/java/com/prattlemob/marionette/bridge/BridgeServer.java`:

```java
package com.prattlemob.marionette.bridge;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/**
 * Localhost-only WebSocket bridge (protocol v0). One controller connection
 * at a time; a later connect while one is attached is refused with close
 * code 1013. The single Netty event-loop thread parses and enqueues
 * commands but never touches game state; the tick thread drains them.
 * Deliberately free of Minecraft imports so it is testable headless.
 */
public final class BridgeServer {
    public static final int PROTOCOL_VERSION = 0;
    public static final int DEFAULT_PORT = 24680;

    private final int requestedPort;
    private final String modVersion;
    private final Queue<AgentCommand> inbound = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Channel> controller = new AtomicReference<>();
    private final AtomicBoolean controllerReady = new AtomicBoolean();
    private final AtomicBoolean disconnected = new AtomicBoolean();

    private NioEventLoopGroup group;
    private Channel listener;

    /** @param port TCP port on loopback; 0 binds an ephemeral port (tests). */
    public BridgeServer(int port, String modVersion) {
        this.requestedPort = port;
        this.modVersion = modVersion;
    }

    /** Bind to loopback. Blocks briefly; call once. Throws on bind failure. */
    public void start() {
        group = new NioEventLoopGroup(1);
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(65536),
                                new WebSocketServerProtocolHandler("/", null, true),
                                new AgentConnectionHandler());
                    }
                });
        listener = bootstrap.bind("127.0.0.1", requestedPort).syncUninterruptibly().channel();
    }

    /** The actually bound port (differs from requested when that was 0). */
    public int port() {
        return ((InetSocketAddress) listener.localAddress()).getPort();
    }

    /** True while a controller that completed the hello handshake is attached. */
    public boolean hasController() {
        return controllerReady.get();
    }

    /**
     * True exactly once after a connection is lost; the tick loop turns this
     * into release-all. The one-tick safety guarantee rests on the tick loop
     * calling this every tick.
     */
    public boolean pollDisconnected() {
        return disconnected.getAndSet(false);
    }

    /** All commands received since the last drain, in arrival order. */
    public List<AgentCommand> drainCommands() {
        List<AgentCommand> commands = new ArrayList<>();
        AgentCommand command;
        while ((command = inbound.poll()) != null) {
            commands.add(command);
        }
        return commands;
    }

    /** Send one observation frame; silently dropped when no controller is ready. */
    public void sendObservation(String json) {
        Channel channel = controller.get();
        if (controllerReady.get() && channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    /** Close listener, connection, and event loop. Safe to call once at shutdown. */
    public void stop() {
        if (listener != null) {
            listener.close().syncUninterruptibly();
        }
        Channel channel = controller.get();
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    private static String errorJson(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("message", message);
        return error.toString();
    }

    private final class AgentConnectionHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        private boolean helloDone;

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete
                    && !controller.compareAndSet(null, ctx.channel())) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1013, "controller already connected"))
                        .addListener(ChannelFutureListener.CLOSE);
            }
            super.userEventTriggered(ctx, event);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
            if (controller.get() != ctx.channel()) {
                return; // a refused extra connection; it is already closing
            }
            AgentCommand command;
            try {
                command = CommandParser.parse(frame.text());
            } catch (ProtocolException e) {
                ctx.writeAndFlush(new TextWebSocketFrame(errorJson(e.getMessage())));
                return;
            }
            if (!helloDone) {
                handleHello(ctx, command);
                return;
            }
            if (command instanceof AgentCommand.Hello) {
                ctx.writeAndFlush(new TextWebSocketFrame(errorJson("duplicate hello")));
                return;
            }
            inbound.add(command);
        }

        private void handleHello(ChannelHandlerContext ctx, AgentCommand command) {
            if (!(command instanceof AgentCommand.Hello hello)) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1002, "hello required first"))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            if (hello.version() != PROTOCOL_VERSION) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1002, "unsupported protocol version"))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            helloDone = true;
            JsonObject reply = new JsonObject();
            reply.addProperty("type", "hello");
            reply.addProperty("version", PROTOCOL_VERSION);
            reply.addProperty("mod", modVersion);
            ctx.writeAndFlush(new TextWebSocketFrame(reply.toString()));
            controllerReady.set(true);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (controller.compareAndSet(ctx.channel(), null)) {
                controllerReady.set(false);
                inbound.clear(); // commands from a dead controller must not act
                disconnected.set(true);
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close(); // channelInactive handles the release signal
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.prattlemob.marionette.bridge.BridgeServerTest'`
Expected: 8 tests PASS. Common failure: `NoClassDefFoundError: io/netty/handler/codec/http/HttpServerCodec` in tests means `addModdingDependenciesTo sourceSets.test` isn't taking effect or `implementation` didn't propagate — check Step 1.

- [ ] **Step 6: Full build + all tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all suites green.

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/main/java/com/prattlemob/marionette/bridge/BridgeServer.java src/test/java/com/prattlemob/marionette/bridge/BridgeServerTest.java
git commit -m "Add localhost WebSocket bridge server with headless integration tests (M1.2)"
```

---

### Task 8: Wire the bridge, write the protocol draft, first external control

**Files:**
- Modify: `src/main/java/com/prattlemob/marionette/MarionetteClient.java`
- Create: `protocol/v0-draft.md`
- Create: `examples/probe.py`
- Modify: `examples/README.md`

**Interfaces:**
- Consumes: `BridgeServer`, `AgentCommand` (Tasks 6–7); the Task 3/5 wiring.
- Produces: the full M1.2 loop — external JSON in, observations out. No new interfaces for later tasks.

- [ ] **Step 1: Extend MarionetteClient**

Apply these changes (shown as the complete relevant members; the Task 3/5
structure and everything not shown stays):

New imports:

```java
import java.util.List;

import com.google.gson.JsonObject;
import com.prattlemob.marionette.bridge.AgentCommand;
import com.prattlemob.marionette.bridge.BridgeServer;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
```

New field + constructor body (bind failure must not kill the client — log
loudly and run without a bridge):

```java
    private BridgeServer bridge;

    public MarionetteClient(ModContainer container) {
        instance = this;
        NeoForge.EVENT_BUS.addListener(this::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(this::onGameShuttingDown);
        try {
            BridgeServer server = new BridgeServer(BridgeServer.DEFAULT_PORT,
                    container.getModInfo().getVersion().toString());
            server.start();
            bridge = server;
            Marionette.LOGGER.info("Bridge listening on 127.0.0.1:{}", server.port());
        } catch (Exception e) {
            bridge = null;
            Marionette.LOGGER.error("Bridge failed to start; running without external control", e);
        }
    }

    private void onGameShuttingDown(GameShuttingDownEvent event) {
        if (bridge != null) {
            bridge.stop();
            Marionette.LOGGER.info("Bridge stopped");
        }
    }
```

Replace `onClientTickPre` with:

```java
    private void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        boolean agentLost = bridge != null && bridge.pollDisconnected();

        if (!inWorld || player == null) {
            if (bridge != null) {
                bridge.drainCommands(); // no world to act in: discard
            }
            if (agentLost && controlsEngaged) {
                Marionette.LOGGER.info("Agent disconnected — releasing all controls");
                releaseControls();
            }
            return;
        }
        if (agentLost) {
            Marionette.LOGGER.info("Agent disconnected — releasing all controls");
            releaseControls();
        }
        if (bridge != null) {
            for (AgentCommand command : bridge.drainCommands()) {
                applyCommand(command);
            }
        }
        if (demo != null) {
            DemoScript.Stunt stunt = demo.tick(player.getYRot(), controlState);
            executeStunt(minecraft, player, stunt);
            if (demo.isDone()) {
                demo = null;
                Marionette.LOGGER.info("Demo complete");
            }
        }
        boolean shouldControl = demo != null || (bridge != null && bridge.hasController());
        if (shouldControl) {
            ControlState.Look look = controlState.consumeLook();
            if (look != null) {
                player.setYRot(look.yaw());
                player.setXRot(look.pitch());
            }
            applier.apply(controlState);
            controlsEngaged = true;
        } else if (controlsEngaged) {
            releaseControls();
        }
    }

    private void applyCommand(AgentCommand command) {
        switch (command) {
            case AgentCommand.InputUpdate update -> {
                if (update.forward() != null) controlState.setForward(update.forward());
                if (update.back() != null) controlState.setBack(update.back());
                if (update.left() != null) controlState.setLeft(update.left());
                if (update.right() != null) controlState.setRight(update.right());
                if (update.jump() != null) controlState.setJump(update.jump());
                if (update.sneak() != null) controlState.setSneak(update.sneak());
                if (update.sprint() != null) controlState.setSprint(update.sprint());
            }
            case AgentCommand.Look look -> controlState.setLook(look.yaw(), look.pitch());
            case AgentCommand.Release release -> controlState.releaseAll();
            case AgentCommand.Hello hello -> { } // handshake handled by the bridge
        }
    }
```

Extend `onClientTickPost` — after the existing `Puppet pos` block, add the
observation send:

```java
        if (bridge != null && inWorld && player != null) {
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "observation");
            frame.addProperty("tick", ticksInWorld);
            frame.addProperty("x", player.getX());
            frame.addProperty("y", player.getY());
            frame.addProperty("z", player.getZ());
            frame.addProperty("yaw", player.getYRot());
            frame.addProperty("pitch", player.getXRot());
            bridge.sendObservation(frame.toString());
        }
```

(`sendObservation` no-ops without a ready controller, so this is cheap when
idle. `player` here means re-reading `Minecraft.getInstance().player` into
the existing local.)

- [ ] **Step 2: Write protocol/v0-draft.md**

Create `protocol/v0-draft.md`:

```markdown
# Marionette protocol v0 — DRAFT

> **Status: throwaway draft.** This documents exactly what the Phase 1
> walking skeleton speaks, nothing more. Protocol v1 (M2.1) replaces it
> with a versioned envelope, capability flags, and a documented change
> process. Do not build lasting tooling against v0.

## Transport

- WebSocket, text frames, one JSON object per frame.
- Endpoint: `ws://127.0.0.1:24680/` (loopback only; port fixed in v0).
- One controller connection at a time. While one is attached, further
  connections are closed with WebSocket close code `1013`, reason
  `controller already connected`. When the controller drops, the next
  connect is accepted.
- Unknown JSON fields are ignored. Unknown `type` values are errors.

## Handshake

The first message on a connection MUST be `hello`:

```json
{"type": "hello", "version": 0}
```

The mod replies:

```json
{"type": "hello", "version": 0, "mod": "0.1.0"}
```

Any other first message → close code `1002`, reason `hello required first`.
A `version` other than `0` → close code `1002`, reason
`unsupported protocol version`. A repeated `hello` later → an `error`
message (connection stays open).

## Agent → mod messages (after hello)

### input — set-and-hold movement controls

```json
{"type": "input", "forward": true, "sprint": true}
```

Fields (all optional booleans): `forward`, `back`, `left`, `right`,
`jump`, `sneak`, `sprint`. **Omitted fields are unchanged.** Held values
persist until a later message changes them (set-and-hold). While no world
is loaded, `input`, `look`, and `release` are discarded.

### look — raw instant camera set

```json
{"type": "look", "yaw": 90.0, "pitch": 0.0}
```

Both fields required, finite numbers, degrees (Minecraft convention:
yaw 0 = south, increases clockwise; pitch −90 = up, +90 = down). Applied
next tick with no smoothing (smoothing arrives in M3.2).

### release — everything to neutral

```json
{"type": "release"}
```

## Mod → agent messages

### observation — one per client tick while in a world

```json
{"type": "observation", "tick": 1234, "x": 12.5, "y": 64.0, "z": -8.25, "yaw": 90.0, "pitch": 0.0}
```

`tick` counts client ticks since world join. Position is the player's feet;
`yaw`/`pitch` are the actual current rotation. No observations are sent on
the title screen or while no controller is attached.

### error — malformed or invalid input

```json
{"type": "error", "message": "unknown type: fly"}
```

Sent in reply to unparseable/invalid messages; the connection stays open
and held controls are unaffected.

## Safety behavior (normative)

- On disconnect, socket error, or the client leaving the world, **all
  controls release within one client tick**. The player idles; nothing
  stays held.
- Malformed input never crashes the client and never alters held controls.
```

- [ ] **Step 3: Write the probe example**

`examples/probe.py`:

```python
#!/usr/bin/env python3
"""Minimal Marionette bridge probe (protocol v0).

Connects, performs the hello handshake, watches observations for a second,
then holds `forward` for three seconds and releases it — the M1.2
definition of done, visible in the game window.

Requires:  pip install websockets
Usage:     python probe.py [port]     (default 24680)
"""
import asyncio
import json
import sys

import websockets

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 24680


async def watch(ws, seconds):
    """Print observations for `seconds`, keeping the socket drained."""
    loop = asyncio.get_running_loop()
    end = loop.time() + seconds
    while (remaining := end - loop.time()) > 0:
        try:
            print(await asyncio.wait_for(ws.recv(), timeout=remaining))
        except TimeoutError:
            break


async def main():
    async with websockets.connect(f"ws://127.0.0.1:{PORT}/") as ws:
        await ws.send(json.dumps({"type": "hello", "version": 0}))
        print("hello reply:", await ws.recv())
        print("-- observing for 1 s --")
        await watch(ws, 1.0)
        print("-- forward ON for 3 s --")
        await ws.send(json.dumps({"type": "input", "forward": True}))
        await watch(ws, 3.0)
        print("-- forward OFF --")
        await ws.send(json.dumps({"type": "input", "forward": False}))
        await watch(ws, 1.0)


if __name__ == "__main__":
    asyncio.run(main())
```

(Note: `asyncio.wait_for` raises the builtin `TimeoutError` on Python 3.11+,
which this box has.)

Replace `examples/README.md`'s placeholder content with:

```markdown
# Marionette examples

Reference agents speaking the current protocol
([`protocol/v0-draft.md`](../protocol/v0-draft.md)). All need Python 3.11+
and `pip install websockets`, plus a running Marionette client that has
joined a world.

- `probe.py` — connect, print observations, hold forward for 3 s, release.
- `walk_square.py` — walk a ~5-block square and report the return error.
  Also the target for the disconnect-safety test: `kill -9` it mid-walk and
  the player must stop within one tick.
```

(If `examples/README.md` has other content the executor should keep, merge
rather than replace — check it first.)

- [ ] **Step 4: Build + unit tests still green**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Scripted in-game verification (M1.2 definition of done)**

Disable the demo for this run: in `build.gradle` `clientDemo`, set
`systemProperty 'marionette.demo', 'false'` (the run config is now just
"auto-join world"). Launch via the harness, wait for `Entered world` in
`run/logs/latest.log`, then from the repo root:

```bash
# One-time venv in the session scratchpad (path from the system prompt):
SCRATCH=<scratchpad-dir>            # substitute the real scratchpad path
python -m venv "$SCRATCH/venv"
"$SCRATCH/venv/bin/pip" install websockets
"$SCRATCH/venv/bin/python" examples/probe.py | tee "$SCRATCH/probe-output.txt"
```

(Reuse `$SCRATCH/venv` for every later Python run in Tasks 9–10.)

Expected in `probe-output.txt`: a `hello reply` line with `"mod"`; ~20
observation lines for the first second with stable `x/z`; then during
"forward ON" the observations' position advancing ~4.3 blocks/s; then
stable again after OFF. Expected in `run/logs/latest.log`: `Bridge
listening on 127.0.0.1:24680`, `Puppet pos` lines during the walk,
`Controls released; vanilla input restored` shortly after probe exit
(disconnect release), no exceptions. Close the window with
`scripts/close-minecraft-window.sh`; the log must then show `Bridge stopped`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/prattlemob/marionette/MarionetteClient.java protocol/v0-draft.md examples/probe.py examples/README.md build.gradle
git commit -m "Wire bridge into the tick loop; protocol v0 draft and probe example (M1.2)"
```

---

### Task 9: Walk-square demo + safety verification (M1.3)

**Files:**
- Create: `examples/walk_square.py`
- No mod-code changes expected — this task *verifies* the safety behavior
  built in Tasks 7–8. Any failure here is a bug to fix (systematic-debugging),
  not a spec change.

**Interfaces:**
- Consumes: protocol v0 as documented in `protocol/v0-draft.md`.
- Produces: the M1.3 demo script; verified safety evidence.

- [ ] **Step 1: Write walk_square.py**

`examples/walk_square.py`:

```python
#!/usr/bin/env python3
"""Marionette walking-skeleton demo (protocol v0): walk a square.

Walks four ~5-block sides with 90° turns and reports how far from the
start the player ended up. Doubles as the disconnect-safety test target:
`kill -9` this script mid-walk and the player must stop within one tick.

Requires:  pip install websockets
Usage:     python walk_square.py [port]     (default 24680)
"""
import asyncio
import json
import math
import sys

import websockets

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 24680
SIDE_BLOCKS = 5.0


async def next_observation(ws):
    while True:
        message = json.loads(await ws.recv())
        if message.get("type") == "observation":
            return message
        print("non-observation message:", message)


async def main():
    async with websockets.connect(f"ws://127.0.0.1:{PORT}/") as ws:
        await ws.send(json.dumps({"type": "hello", "version": 0}))
        hello = json.loads(await ws.recv())
        assert hello.get("type") == "hello", hello

        start = await next_observation(ws)
        yaw = start["yaw"]
        print(f"start ({start['x']:.1f}, {start['z']:.1f}) yaw {yaw:.0f}")

        for side in range(4):
            await ws.send(json.dumps({"type": "look", "yaw": yaw, "pitch": 0.0}))
            origin = await next_observation(ws)
            await ws.send(json.dumps({"type": "input", "forward": True}))
            while True:
                obs = await next_observation(ws)
                walked = math.dist((obs["x"], obs["z"]), (origin["x"], origin["z"]))
                if walked >= SIDE_BLOCKS:
                    break
            await ws.send(json.dumps({"type": "input", "forward": False}))
            print(f"side {side + 1}: walked {walked:.1f} blocks")
            yaw += 90.0

        await asyncio.sleep(0.5)  # let momentum settle
        end = await next_observation(ws)
        error = math.dist((end["x"], end["z"]), (start["x"], start["z"]))
        print(f"finished {error:.1f} blocks from start")
        await ws.send(json.dumps({"type": "release"}))


if __name__ == "__main__":
    asyncio.run(main())
```

- [ ] **Step 2: Scripted verification — the square**

Launch the client via the harness (demo still disabled, auto-join world),
wait for `Entered world`, then (venv from Task 8 Step 5):

```bash
"$SCRATCH/venv/bin/python" examples/walk_square.py
```

Expected: four `side N: walked ≥5.0 blocks` lines and a final
`finished X blocks from start` with X ≤ ~3 (momentum overshoot on four
corners; "roughly its start" per the DoD). `run/logs/latest.log` shows
`Puppet pos` tracing a square (both horizontal axes move and return near
their starting values).

- [ ] **Step 3: Scripted verification — kill -9 mid-walk**

Fresh client run (or same session — reconnect is fine). Start
`"$SCRATCH/venv/bin/python" examples/walk_square.py` in the background,
wait ~4 s (mid side 1), then `kill -9 <pid>`. Analyze `run/logs/latest.log`:

```bash
grep -E "Agent disconnected|Controls released|Puppet pos" run/logs/latest.log | tail -20
```

Expected: `Agent disconnected — releasing all controls` and
`Controls released; vanilla input restored` appear; `Puppet pos` samples
after those lines stop advancing (≤ ~1 block of momentum, then static). The
player MUST NOT keep walking.

- [ ] **Step 4: Scripted verification — malformed input + reconnect cycling**

With the client still running, execute this from the scratchpad (ad-hoc
test, not committed):

```python
# scratchpad/malformed_and_reconnect.py
import asyncio, json
import websockets

async def main():
    for round_number in range(3):  # reconnect works repeatedly
        async with websockets.connect("ws://127.0.0.1:24680/") as ws:
            await ws.send(json.dumps({"type": "hello", "version": 0}))
            await ws.recv()
            await ws.send("garbage")
            reply = json.loads(await ws.recv())
            assert reply["type"] == "error", reply
            await ws.send(json.dumps({"type": "fly"}))
            while (reply := json.loads(await ws.recv()))["type"] != "error":
                pass
            # connection still works after two errors:
            await ws.send(json.dumps({"type": "input", "forward": True}))
            await asyncio.sleep(1.0)
            await ws.send(json.dumps({"type": "release"}))
            print(f"round {round_number}: ok")

asyncio.run(main())
```

Expected: `round 0/1/2: ok`; the game log shows three
`Agent disconnected — releasing all controls` lines (one per `async with`
exit) and **no** exceptions or crashes; the client stays up throughout.

- [ ] **Step 5: Scripted verification — world leave under agent control**

Start an agent that holds forward and lingers:

```bash
"$SCRATCH/venv/bin/python" - <<'EOF' &
import asyncio, json
import websockets

async def main():
    async with websockets.connect("ws://127.0.0.1:24680/") as ws:
        await ws.send(json.dumps({"type": "hello", "version": 0}))
        await ws.recv()
        await ws.send(json.dumps({"type": "input", "forward": True}))
        await asyncio.sleep(30)

asyncio.run(main())
EOF
```

then run `scripts/close-minecraft-window.sh` mid-walk (~3 s in). Expected in
the log, in order: `Left world` preceded (same tick) by
`Controls released; vanilla input restored`, then `Bridge stopped` — i.e.
leaving the world released controls before shutdown, nothing stuck, no
exception. (Window close exercises world-leave + shutdown in one shot; the
menu-quit path is covered by the same LoggingOut handler.)

- [ ] **Step 6: Commit**

```bash
git add examples/walk_square.py
git commit -m "Add walk-square demo agent; verify disconnect and error safety (M1.3)"
```

---

### Task 10: Phase close-out

**Files:**
- Modify: `ROADMAP.md` (M1.1 last item, all M1.2 + M1.3 items)
- Modify: `docs/decisions.md` (only if any experiment notes are still pending)
- Modify: `build.gradle` (`clientDemo`: leave
  `systemProperty 'marionette.demo', 'false'` as Task 8 set it — the demo
  stays disabled by default; the run config itself stays, as the
  scripted-verification harness for later phases)

**Interfaces:** none new.

- [ ] **Step 1: Confirm every verification actually passed**

Re-check the evidence trail (superpowers:verification-before-completion):
archived D4 logs, probe output, walk-square output, kill-9 log excerpt,
reconnect output. Anything missing or ambiguous → go back and redo that
verification, do not tick boxes on memory.

- [ ] **Step 2: Human eyeball checklist** ⚠️ NEEDS-HUMAN

Ask the user to (a) run `./gradlew runClient`, join a world, run
`python examples/walk_square.py`, and confirm it *looks* like walking (not
teleport-sliding), and (b) confirm the two D4 human checks from Task 5
Step 2 were done. This is the hybrid-verification contract from the spec.

- [ ] **Step 3: Tick the roadmap**

In `ROADMAP.md` mark complete: M1.1's remaining demo-script item (the demo
is disabled by default — armed only by `-Dmarionette.demo=true`), all five
M1.2 items, all five M1.3 items. Do not touch later milestones.

- [ ] **Step 4: Docs consistency sweep**

- `docs/decisions.md`: D4 section settled with evidence (Task 5); D1a
  settled (already committed). Both must name their milestone and date.
- `protocol/v0-draft.md` matches the implementation verbatim — re-read both
  sides and spot-check field names against `CommandParser` and the
  observation builder in `MarionetteClient`.
- `examples/README.md` lists both scripts with the `websockets` prerequisite.

- [ ] **Step 5: Final build + full test run**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, every suite green.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Close out Phase 1: walking skeleton verified end-to-end"
```

Then stop: merging `phase-1-walking-skeleton` → `1.21.8` is the user's call
(superpowers:finishing-a-development-branch).
