package arsonist.autospear;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.component.type.KineticWeaponComponent;
import net.minecraft.component.type.PiercingWeaponComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.AmbientEntity;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.WaterCreatureEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.lang.reflect.Method;

/**
 * AutoSpear v1.6 - flies you at the target and spears it by itself (Auto Fly), or leaves the flying
 * to your own ElytraFly (Auto Fly off).
 *
 * Auto Fly builds speed up gradually like an ElytraFly with acceleration, never stops to hover, and
 * backs off a little if the server pulls you back. Either way it:
 *  - turns your camera onto an intercept point (where the target will be when you get there), so a
 *    control-mode ElytraFly flies you straight into them at full speed
 *  - keeps the spear charge held and wound up
 *  - after a hit, pulls up and out a short distance, then swings straight back for the next hit
 *  - jabs on foot when the target is inside spear reach
 */
public class AutoSpear extends Module {
    public enum Sorting {
        Distance,
        Health,
        Angle
    }

    public enum AttackMode {
        Auto,
        Charge,
        Jab
    }

    public enum AttackStyle {
        Vertical,
        Straight
    }

    private enum Phase {
        Climb,
        Approach,
        PullOut
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    // Parts of the target's hitbox to aim at, as a fraction of its height. First visible one wins.
    private static final double[] AIM_HEIGHTS = {0.5, 0.85, 0.15};
    // Extra distance the target we are already fighting may drift past Range before it is dropped.
    private static final double KEEP_RANGE = 24.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpear = settings.createGroup("Spear");

    // ---- General ----

    private final Setting<Sorting> sorting = sgGeneral.add(new EnumSetting.Builder<Sorting>()
        .name("sorting")
        .description("How to pick the target: nearest, lowest health, or closest to your crosshair.")
        .defaultValue(Sorting.Distance)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far away a target can be.")
        .defaultValue(64.0)
        .min(1.0)
        .sliderRange(1.0, 128.0)
        .build()
    );

    private final Setting<Boolean> autoBoost = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-boost")
        .description("Uses firework rockets while attacking with an elytra. Leave off if your ElytraFly gives you the speed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> smart = sgGeneral.add(new BoolSetting.Builder()
        .name("smart")
        .description("Aims where the target is going instead of where it is, and picks a part of its hitbox that is not behind a block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Draws a box on the target and a line to the point you are aiming at.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Render color.")
        .defaultValue(new SettingColor(189, 153, 255, 255))
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> players = sgGeneral.add(new BoolSetting.Builder()
        .name("players")
        .description("Target players.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> nakeds = sgGeneral.add(new BoolSetting.Builder()
        .name("nakeds")
        .description("Target players who are wearing no armor.")
        .defaultValue(true)
        .visible(players::get)
        .build()
    );

    private final Setting<Boolean> animals = sgGeneral.add(new BoolSetting.Builder()
        .name("animals")
        .description("Target passive mobs.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> hostiles = sgGeneral.add(new BoolSetting.Builder()
        .name("hostiles")
        .description("Target hostile mobs (withers included).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> neutrals = sgGeneral.add(new BoolSetting.Builder()
        .name("neutrals")
        .description("Target neutral mobs (endermen, zombified piglins, wolves, iron golems...).")
        .defaultValue(false)
        .build()
    );

    // ---- Spear ----

    private final Setting<AttackMode> mode = sgSpear.add(new EnumSetting.Builder<AttackMode>()
        .name("mode")
        .description("Auto: charge while flying or riding, jab on foot. Charge / Jab: always use that attack.")
        .defaultValue(AttackMode.Auto)
        .build()
    );

    private final Setting<AttackStyle> style = sgSpear.add(new EnumSetting.Builder<AttackStyle>()
        .name("attack-style")
        .description("Vertical: climb above them and dive down on them. Straight: fly straight at them from wherever you are. Swap with Style Key.")
        .defaultValue(AttackStyle.Vertical)
        .build()
    );

    private final Setting<Keybind> styleKey = sgSpear.add(new KeybindSetting.Builder()
        .name("style-key")
        .description("Press to swap between Vertical and Straight. Works mid-fight, and says which one in chat.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Double> height = sgSpear.add(new DoubleSetting.Builder()
        .name("height")
        .description("Vertical: how many blocks above the target each dive starts.")
        .defaultValue(12.7)
        .min(2.0)
        .sliderRange(4.0, 40.0)
        .visible(() -> style.get() == AttackStyle.Vertical)
        .build()
    );

    private final Setting<Double> angle = sgSpear.add(new DoubleSetting.Builder()
        .name("angle")
        .description("Vertical: how steep the dive is. 90 is straight down, low numbers are flatter.")
        .defaultValue(64.5)
        .range(15.0, 90.0)
        .sliderRange(15.0, 90.0)
        .visible(() -> style.get() == AttackStyle.Vertical)
        .build()
    );

    private final Setting<Boolean> autoFly = sgSpear.add(new BoolSetting.Builder()
        .name("auto-fly")
        .description("On = this module flies you itself (turn your own ElytraFly off). Off = your ElytraFly (Mio etc.) does the flying and speed, and this steers it and holds the keys for you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoForward = sgSpear.add(new BoolSetting.Builder()
        .name("auto-forward")
        .description("Holds your forward key while attacking so your ElytraFly flies you at the target without you touching W.")
        .defaultValue(true)
        .visible(() -> !autoFly.get())
        .build()
    );

    private final Setting<Boolean> autoVertical = sgSpear.add(new BoolSetting.Builder()
        .name("auto-vertical")
        .description("Holds jump / sneak to climb or drop toward the target, for ElytraFly modes that use those keys for up and down.")
        .defaultValue(true)
        .visible(() -> !autoFly.get())
        .build()
    );

    private final Setting<Double> flySpeed = sgSpear.add(new DoubleSetting.Builder()
        .name("fly-speed")
        .description("Top speed in blocks per tick (3 = about 216 km/h). Lower it if you get pulled back a lot.")
        .defaultValue(3.0)
        .min(0.5)
        .sliderRange(1.0, 6.0)
        .visible(autoFly::get)
        .build()
    );

    private final Setting<Double> acceleration = sgSpear.add(new DoubleSetting.Builder()
        .name("acceleration")
        .description("Speed added each tick until Fly Speed is reached. Building up gradually is what keeps the server from rubber banding you.")
        .defaultValue(0.2)
        .range(0.01, 3.0)
        .sliderRange(0.05, 1.0)
        .visible(autoFly::get)
        .build()
    );

    private final Setting<Double> flyTurn = sgSpear.add(new DoubleSetting.Builder()
        .name("fly-turn")
        .description("How sharply your flight path bends toward the target each tick. 1 = instant, lower = smoother curves. Right before a hit it always flies straight at them.")
        .defaultValue(0.55)
        .range(0.05, 1.0)
        .sliderRange(0.05, 1.0)
        .visible(autoFly::get)
        .build()
    );

    private final Setting<Boolean> antiRubberband = sgSpear.add(new BoolSetting.Builder()
        .name("anti-rubberband")
        .description("If the server pulls you back, drops your top speed a little and builds it back up slowly.")
        .defaultValue(true)
        .visible(autoFly::get)
        .build()
    );

    private final Setting<Boolean> steer = sgSpear.add(new BoolSetting.Builder()
        .name("steer")
        .description("Turns your camera onto the target (needed if your own ElytraFly is flying). Off = camera stays free, the spear is aimed server side only.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> turnSpeed = sgSpear.add(new DoubleSetting.Builder()
        .name("turn-speed")
        .description("How fast the camera turns. Higher = snappier, lower = smoother. 0 = instant. Right before a hit it always locks on exactly.")
        .defaultValue(18.0)
        .range(0.0, 60.0)
        .sliderRange(0.0, 40.0)
        .visible(steer::get)
        .build()
    );

    private final Setting<Double> rehitDistance = sgSpear.add(new DoubleSetting.Builder()
        .name("rehit-distance")
        .description("How far you pull out after a hit before swinging back. Just past spear reach is enough for a full speed hit.")
        .defaultValue(5.5)
        .range(3.0, 20.0)
        .sliderRange(3.0, 12.0)
        .build()
    );

    private final Setting<Double> pullUpAngle = sgSpear.add(new DoubleSetting.Builder()
        .name("pull-up-angle")
        .description("How steeply you climb out after a hit, so you do not fly into the ground.")
        .defaultValue(20.0)
        .range(0.0, 60.0)
        .sliderRange(0.0, 60.0)
        .build()
    );

    private final Setting<Boolean> intercept = sgSpear.add(new BoolSetting.Builder()
        .name("intercept")
        .description("Flies at where the target will be when you reach it, cutting corners instead of chasing its tail.")
        .defaultValue(true)
        .visible(smart::get)
        .build()
    );

    private final Setting<Integer> maxLead = sgSpear.add(new IntSetting.Builder()
        .name("max-lead")
        .description("Furthest ahead (in ticks) Intercept will aim.")
        .defaultValue(20)
        .range(1, 60)
        .sliderRange(1, 40)
        .visible(() -> smart.get() && intercept.get())
        .build()
    );

    private final Setting<Double> prediction = sgSpear.add(new DoubleSetting.Builder()
        .name("ping-ticks")
        .description("Extra ticks to aim ahead for ping. About 1 per 50 ms of real ping.")
        .defaultValue(1.0)
        .range(0.0, 10.0)
        .sliderRange(0.0, 6.0)
        .visible(smart::get)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgSpear.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Swaps to a spear in your hotbar when a target is found. Scroll off the spear to pause, scroll back to resume.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> boostDelay = sgSpear.add(new IntSetting.Builder()
        .name("boost-delay")
        .description("Ticks between firework rockets.")
        .defaultValue(30)
        .range(5, 200)
        .sliderRange(5, 100)
        .visible(autoBoost::get)
        .build()
    );

    // ---- State ----

    private LivingEntity target;
    private boolean switched;
    private boolean paused;

    private Phase phase = Phase.Approach;
    private int pullTicks;
    private int climbTicks;
    private boolean diving;
    private boolean styleKeyDown;
    private Vec3d prevPos;
    private Vec3d prevMove;
    private int resyncTicks;
    private float pullYaw;
    private int lastHurtTime;

    private double tvx, tvy, tvz;
    private boolean hasTargetVel;

    private float aimYaw, aimPitch;
    private boolean steering;
    private long lastFrame;

    private boolean holdingUse;
    private boolean holdingMove;
    private int boostTimer;
    private int jabTimer;

    private Vec3d planned;
    private boolean controlling;
    private Vec3d flyDir;
    private double flyCur;
    private double speedCap = Double.MAX_VALUE;
    private Vec3d expectedPos;

    private static Method vecSetter;
    private static boolean vecSetterBroken;

    private Vec3d renderAim;
    private final Color sideColor = new Color();

    public AutoSpear() {
        super(Categories.Combat, "auto-spear", "Spear attacks on autopilot: flies you at the target, charges, hits, pulls out and comes straight back.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        stopCharge();
        releaseKeys();
        reset();
    }

    private void reset() {
        target = null;
        switched = false;
        paused = false;
        phase = Phase.Approach;
        pullTicks = 0;
        lastHurtTime = 0;
        hasTargetVel = false;
        steering = false;
        lastFrame = 0;
        planned = null;
        controlling = false;
        flyDir = null;
        flyCur = 0;
        speedCap = Double.MAX_VALUE;
        expectedPos = null;
        prevPos = null;
        prevMove = null;
        resyncTicks = 0;
        boostTimer = 0;
        jabTimer = 0;
        renderAim = null;
    }

    @Override
    public String getInfoString() {
        String tag = style.get() == AttackStyle.Vertical ? "Vertical" : "Straight";
        if (target == null) return tag;
        return target.getName().getString() + " | " + tag + (paused ? " (no spear)" : "");
    }

    // ---- Main loop ----

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        renderAim = null;
        steering = false;
        planned = null;

        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        checkRubberband();
        pollStyleKey();
        checkSetback();

        if (boostTimer > 0) boostTimer--;
        if (jabTimer > 0) jabTimer--;

        if (!mc.player.isAlive() || mc.player.isSpectator()) {
            idle();
            return;
        }

        // The current target is kept a bit past Range so pulling out after a hit does not drop it.
        if (target == null || !isValidTarget(target, KEEP_RANGE)) {
            LivingEntity next = findTarget();
            if (next != target) {
                target = next;
                hasTargetVel = false;
                phase = Phase.Climb;
                climbTicks = 0;
                lastHurtTime = target == null ? 0 : target.hurtTime;
            }
        }
        if (target == null) {
            idle();
            return;
        }

        // Swap to the spear once per fight. After that, not holding it means "paused".
        if (!switched) {
            if (autoSwitch.get() && !isSpear(mc.player.getMainHandStack())) {
                FindItemResult result = InvUtils.find(this::isSpear, 0, 8);
                if (result.found()) InvUtils.swap(result.slot(), false);
            }
            switched = true;
        }

        ItemStack spear = mc.player.getMainHandStack();
        if (!isSpear(spear)) {
            pause();
            return;
        }
        paused = false;

        KineticWeaponComponent kinetic = spear.get(DataComponentTypes.KINETIC_WEAPON);
        boolean gliding = mc.player.isGliding();

        boolean charge = switch (mode.get()) {
            case Charge -> true;
            case Jab -> false;
            case Auto -> gliding || mc.player.hasVehicle();
        };
        if (kinetic == null) charge = false;

        AttackRangeComponent reach = mc.player.getAttackRange();
        double minReach = reach.getEffectiveMinRange(mc.player);
        double maxReach = reach.getEffectiveMaxRange(mc.player);

        updateTargetVelocity();

        // Our own movement this tick, from position, so it is right whatever fly module is moving us.
        Vec3d myVel = new Vec3d(mc.player.getX() - mc.player.lastX, mc.player.getY() - mc.player.lastY, mc.player.getZ() - mc.player.lastZ);
        double mySpeed = myVel.length();

        Vec3d eye = mc.player.getEyePos();
        Vec3d from = eye.add(myVel);
        boolean flying = charge && (gliding || mc.player.hasVehicle());

        Vec3d aim = aimPoint(target, from, flying ? mySpeed : 0.0);
        double dist = from.distanceTo(aim);
        double centerDist = eye.distanceTo(new Vec3d(target.getX(), target.getY() + target.getHeight() * 0.5, target.getZ()));

        // A hit landed this tick (its hurt timer jumped back up).
        boolean hit = target.hurtTime > lastHurtTime;
        lastHurtTime = target.hurtTime;

        // ---- Climb / approach / pull out ----
        boolean dive = flying && useDive();
        Vec3d climbPoint = dive ? climbPoint(eye) : null;

        if (flying) {
            if (phase == Phase.Climb && !dive) phase = Phase.Approach;
            // Dive style but this run did not start from the top (just switched style, or the target
            // just landed): go up first.
            if (phase == Phase.Approach && dive && !diving && !highEnough(eye)) startRun(true);

            // Vertical: ground less than a tick away and already inside spear reach -> pull up now.
            boolean groundClose = vertical() && phase == Phase.Approach && centerDist <= maxReach + 0.5
                && groundAhead(myVel, mySpeed + 2.0) < mySpeed + 1.0;

            if (phase == Phase.Approach && (hit || groundClose || centerDist < minReach + 0.25)) {
                phase = Phase.PullOut;
                pullTicks = 0;
                pullYaw = pullOutYaw(myVel);
            } else if (phase == Phase.PullOut) {
                pullTicks++;
                if (centerDist >= rehitDistance.get() || pullTicks > 30) startRun(dive);
            } else if (phase == Phase.Climb) {
                climbTicks++;
                // At the top (within a tick or so of it), already up high above them, or taking too long: dive.
                if (eye.distanceTo(climbPoint) <= Math.max(2.0, mySpeed * 1.5) || highEnough(eye) || climbTicks > 120) {
                    phase = Phase.Approach;
                    diving = true;
                }
            }
        } else {
            phase = Phase.Approach;
        }

        // Where to steer this tick: the top of the dive while climbing, otherwise the target.
        Vec3d steerTo = phase == Phase.Climb ? climbPoint : aim;

        boolean aimed;
        if (phase == Phase.PullOut) {
            // Keep going the way we were, nosed up, instead of whipping round inside their hitbox.
            aimYaw = mc.player.getYaw() + MathHelper.wrapDegrees(pullYaw - mc.player.getYaw());
            // Vertical comes out of a dive pointing at the ground, so pull up hard.
            aimPitch = (float) -(vertical() ? Math.max(pullUpAngle.get(), 40.0) : pullUpAngle.get());
            aimed = true;
        } else {
            aimed = updateAim(from, steerTo);
            renderAim = steerTo;
        }

        // ---- Auto fly ----
        if (flying && gliding && autoFly.get()) {
            planned = planFlight(eye, steerTo, minReach, maxReach, myVel);
            controlling = true;
            expectedPos = new Vec3d(mc.player.getX() + planned.x, mc.player.getY() + planned.y, mc.player.getZ() + planned.z);
        } else {
            releaseFlight();
        }

        // ---- Drive your own ElytraFly ----
        if (flying && gliding && !autoFly.get()) driveElytraFly(eye, steerTo, maxReach, mySpeed);
        else releaseKeys();

        // ---- Rotation ----
        if (aimed) {
            if (steer.get()) {
                // Close to a hit, lock on exactly. Otherwise the render frames ease the camera round.
                boolean strikeZone = phase == Phase.Approach && dist <= maxReach + Math.max(mySpeed, 1.0) * 2.0 + 1.0;
                if (strikeZone || turnSpeed.get() <= 0.0) {
                    mc.player.setYaw(aimYaw);
                    mc.player.setPitch(aimPitch);
                } else {
                    steering = true;
                }
            } else {
                Rotations.rotate(aimYaw, aimPitch, 50);
            }
        }

        // ---- Attack ----
        if (charge) {
            // Re-starting the charge resets its wind-up, so only do it when there is time to wind up again.
            int delay = kinetic.delayTicks();
            boolean canInterrupt = dist > maxReach + Math.max(mySpeed, 0.5) * (delay + 4) && phase != Phase.PullOut;

            tickBoost(canInterrupt);
            tickCharge(kinetic, canInterrupt);
        } else {
            stopCharge();
            if (aimed) tickJab(spear, from, aim, minReach, maxReach);
        }
    }

    private void idle() {
        stopCharge();
        releaseFlight();
        releaseKeys();
        phase = Phase.Approach;
        switched = false;
        paused = false;
        target = null;
        hasTargetVel = false;
        steering = false;
    }

    /** We have a target but the spear is not in hand: stop attacking and leave the camera alone. */
    private void pause() {
        paused = true;
        steering = false;
        stopCharge();
        // Let go of the controls and just glide, no stopping dead in the air.
        releaseFlight();
        releaseKeys();
    }

    /** Server teleported us back: we moved backwards by more than a normal tick of flight. */
    private void checkSetback() {
        if (resyncTicks > 0) resyncTicks--;

        Vec3d pos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        if (prevPos != null && prevMove != null && prevMove.length() > 1.0) {
            Vec3d disp = pos.subtract(prevPos);
            if (disp.length() > 3.0 && disp.dotProduct(prevMove) < 0) resyncTicks = 5;
        }

        prevMove = prevPos == null ? null : pos.subtract(prevPos);
        prevPos = pos;
    }

    /** Style Key: swaps Vertical <-> Straight on each press (not while a menu or chat is open). */
    private void pollStyleKey() {
        boolean down = mc.currentScreen == null && styleKey.get().isPressed();

        if (down && !styleKeyDown) {
            AttackStyle next = style.get() == AttackStyle.Vertical ? AttackStyle.Straight : AttackStyle.Vertical;
            style.set(next);
            if (next == AttackStyle.Straight && phase == Phase.Climb) phase = Phase.Approach;
            diving = false;
            info("Attack style: %s", next.name());
        }

        styleKeyDown = down;
    }

    private boolean useDive() {
        return style.get() == AttackStyle.Vertical;
    }

    private boolean vertical() {
        return style.get() == AttackStyle.Vertical;
    }

    /** Blocks until we hit something solid going the way we are moving (up to the given look-ahead). */
    private double groundAhead(Vec3d myVel, double lookAhead) {
        if (myVel.lengthSquared() < 1.0E-4) return Double.MAX_VALUE;

        Vec3d feet = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d end = feet.add(myVel.normalize().multiply(lookAhead));
        HitResult result = mc.world.raycast(new RaycastContext(feet, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));

        return result.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : result.getPos().distanceTo(feet);
    }

    /** Start the next attack run: climb first when diving, otherwise straight at them. */
    private void startRun(boolean dive) {
        phase = dive ? Phase.Climb : Phase.Approach;
        climbTicks = 0;
        diving = false;
    }

    /** The top of the dive: Height above the target, out on our side at the dive Angle. */
    private Vec3d climbPoint(Vec3d eye) {
        double pitch = Math.toRadians(angle.get());
        double run = height.get() / Math.sin(pitch);
        double out = run * Math.cos(pitch);

        double dx = eye.x - target.getX();
        double dz = eye.z - target.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);

        if (h < 1.0E-3) {
            // Right above them: use the side behind where we are looking.
            double yaw = Math.toRadians(mc.player.getYaw());
            dx = Math.sin(yaw);
            dz = -Math.cos(yaw);
            h = 1.0;
        }

        return new Vec3d(target.getX() + dx / h * out, target.getY() + target.getHeight() * 0.5 + height.get(), target.getZ() + dz / h * out);
    }

    /** Already up high enough and steep enough above them to dive from here. */
    private boolean highEnough(Vec3d eye) {
        double dy = eye.y - (target.getY() + target.getHeight() * 0.5);
        double dx = eye.x - target.getX();
        double dz = eye.z - target.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        double elevation = Math.toDegrees(Math.atan2(dy, h));

        return dy >= height.get() * 0.75 && elevation >= angle.get() - 12.0;
    }

    /** Direction to pull out in: the way we are already flying, or straight away from the target. */
    private float pullOutYaw(Vec3d myVel) {
        double dx = myVel.x;
        double dz = myVel.z;

        if (dx * dx + dz * dz < 0.01) {
            dx = mc.player.getX() - target.getX();
            dz = mc.player.getZ() - target.getZ();
        }
        if (dx * dx + dz * dz < 1.0E-4) return mc.player.getYaw();

        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
    }

    // ---- Auto fly ----

    /**
     * Your ElytraFly does the speed. We just hold the keys it listens to: forward to fly where the
     * camera points, and jump / sneak to climb or drop toward the target.
     */
    private void driveElytraFly(Vec3d eye, Vec3d aim, double maxReach, double mySpeed) {
        holdingMove = true;

        // Vertical: the first two ticks of a pull-out let go of forward so the ElytraFly stops
        // driving us into the ground, and just climbs.
        // Just got pulled back by the server: let go of the keys for a few ticks so it can catch up,
        // instead of flying straight back into the same setback.
        if (resyncTicks > 0) {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
            return;
        }

        mc.options.forwardKey.setPressed(autoForward.get());

        boolean up = false;
        boolean down = false;

        if (autoVertical.get()) {
            double dy;
            if (phase == Phase.PullOut) dy = 2.0; // climb out after a hit
            else dy = aim.y - eye.y;

            // Small dead zone so it does not flick between up and down.
            double dead = phase == Phase.PullOut ? 0.0 : Math.max(0.75, Math.min(2.0, eye.distanceTo(aim) * 0.1));
            up = dy > dead;
            down = dy < -dead;

            // Last moments of a dive: the camera already points down at them, holding sneak on top
            // of that is what made it overshoot into the ground.
            if (phase == Phase.Approach && eye.distanceTo(aim) <= maxReach + Math.max(mySpeed, 1.0) * 3.0) down = false;
        }

        mc.options.jumpKey.setPressed(up);
        mc.options.sneakKey.setPressed(down);
    }

    private void releaseKeys() {
        if (!holdingMove) return;
        holdingMove = false;

        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    private void releaseFlight() {
        controlling = false;
        flyDir = null;
        expectedPos = null;
    }

    /** Server pulled us back: we are not where last tick's move should have put us. */
    private void checkRubberband() {
        if (speedCap < flySpeed.get()) speedCap = Math.min(flySpeed.get(), speedCap + 0.02);

        if (expectedPos == null || !controlling || mc.player == null) {
            expectedPos = null;
            return;
        }

        double dx = mc.player.getX() - expectedPos.x;
        double dy = mc.player.getY() - expectedPos.y;
        double dz = mc.player.getZ() - expectedPos.z;
        expectedPos = null;

        if (antiRubberband.get() && dx * dx + dy * dy + dz * dz > 2.25) {
            speedCap = Math.max(1.0, Math.min(speedCap, flyCur) * 0.85);
            flyCur = Math.min(flyCur, speedCap) * 0.6;
        }
    }

    /** This tick's movement: straight at the aim point (or out along the pull-out line), speeding up smoothly. */
    private Vec3d planFlight(Vec3d eye, Vec3d aim, double minReach, double maxReach, Vec3d myVel) {
        double max = flySpeed.get();
        if (antiRubberband.get()) max = Math.min(max, speedCap);

        Vec3d desired;
        if (phase == Phase.PullOut) {
            desired = direction(pullYaw, (float) -pullUpAngle.get());
        } else {
            Vec3d to = aim.subtract(eye);
            desired = to.lengthSquared() < 1.0E-6 ? mc.player.getRotationVec(1.0f) : to.normalize();
        }

        // Pick up from however fast and whichever way we are already going.
        if (flyDir == null) {
            flyCur = myVel.length();
            flyDir = myVel.lengthSquared() > 1.0E-4 ? myVel.normalize() : desired;
        }
        flyCur = Math.min(max, flyCur + acceleration.get());
        if (flyCur > max) flyCur = max;

        double dist = aim.distanceTo(eye);
        boolean strike = phase == Phase.Approach && dist <= maxReach + max * 3.0;

        double f = strike ? 1.0 : flyTurn.get();
        Vec3d dir = flyDir.multiply(1.0 - f).add(desired.multiply(f));
        if (dir.lengthSquared() < 1.0E-6) dir = desired;
        dir = dir.normalize();
        flyDir = dir;

        double step = flyCur;

        // A fast tick can jump straight over spear reach. Spread a small slow-down over the last few
        // ticks so one of them ends right in the middle of reach - no single stuttery short step.
        if (phase == Phase.Approach && smart.get() && step > 0.1) {
            double ideal = (minReach + maxReach) / 2.0;
            double gap = dist - ideal;

            if (gap > 0 && gap <= step * 5.0) {
                int n = (int) Math.ceil(gap / step);
                double even = gap / n;
                if (even >= step * 0.6) step = even;
            }
        }

        Vec3d move = dir.multiply(step);

        if (!pathClear(move, strike ? 0.0 : 1.0)) {
            // Something in the way: climb over it.
            Vec3d climb = new Vec3d(move.x * 0.5, Math.max(Math.abs(step) * 0.8, 0.5), move.z * 0.5);
            Vec3d up = new Vec3d(0, Math.min(Math.max(step, 0.5), 1.0), 0);

            if (pathClear(climb, 0.5)) move = climb;
            else if (pathClear(up, 0.0)) move = up;
            else move = new Vec3d(0, 0, 0);

            flyCur = move.length();
            if (move.lengthSquared() > 1.0E-6) flyDir = move.normalize();
        }

        return move;
    }

    private static Vec3d direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vec3d(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /** True if the player's hitbox can travel along this movement (plus extra blocks) without touching anything. */
    private boolean pathClear(Vec3d move, double extra) {
        double length = move.length();
        if (length < 1.0E-4) return true;

        Box box = mc.player.getBoundingBox();
        double total = length + extra;
        int steps = (int) Math.ceil(total / 0.5);

        for (int i = 1; i <= steps; i++) {
            double d = Math.min(total, i * 0.5) / length;
            if (!mc.world.isSpaceEmpty(mc.player, box.offset(move.x * d, move.y * d, move.z * d))) return false;
        }

        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPlayerMove(PlayerMoveEvent event) {
        if (planned == null || event.type != MovementType.SELF) return;
        if (mc.player == null || !mc.player.isGliding()) return;

        Vec3d move = planned;
        planned = null;

        // Meteor edits this vector in place, never touch the shared zero constant.
        if (event.movement == Vec3d.ZERO || !setVec(event.movement, move.x, move.y, move.z)) {
            mc.player.setVelocity(move.x, move.y, move.z);
            return;
        }

        // Keep the momentum, so letting go of the controls glides on instead of dropping dead.
        mc.player.setVelocity(move.x, move.y, move.z);
    }

    /**
     * Calls Meteor's IVec3d.meteor$set. Done by name because its return type changed between 1.21.11
     * builds of Meteor, and a direct call would crash on whichever build this was not compiled against.
     */
    private static boolean setVec(Vec3d vec, double x, double y, double z) {
        if (vecSetterBroken) return false;

        try {
            if (vecSetter == null) {
                vecSetter = Class.forName("meteordevelopment.meteorclient.mixininterface.IVec3d")
                    .getMethod("meteor$set", double.class, double.class, double.class);
            }

            vecSetter.invoke(vec, x, y, z);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            vecSetterBroken = true;
            return false;
        }
    }

    // ---- Charge ----

    private boolean usingSpear() {
        return mc.player.isUsingItem() && mc.player.getActiveHand() == Hand.MAIN_HAND;
    }

    private void tickCharge(KineticWeaponComponent kinetic, boolean canRestart) {
        // The key has to stay down or vanilla lets go of the spear.
        mc.options.useKey.setPressed(true);
        holdingUse = true;

        int delay = kinetic.delayTicks();
        int window = kinetic.damageConditions().map(KineticWeaponComponent.Condition::maxDurationTicks).orElse(100);

        boolean using = usingSpear();

        if (using) {
            int used = mc.player.getItemUseTime();
            boolean expired = used >= window - 3;
            boolean stale = used >= Math.max(delay + 20, window - 40);

            // A charge only deals damage for a few seconds. Re-start it while far away, before it runs out.
            if (expired || (stale && canRestart)) {
                mc.interactionManager.stopUsingItem(mc.player);
                using = false;
            }
        }

        if (!using && !mc.player.isUsingItem()) use(Hand.MAIN_HAND);
    }

    private void stopCharge() {
        if (!holdingUse) return;
        holdingUse = false;

        mc.options.useKey.setPressed(false);

        if (mc.player != null && mc.interactionManager != null && mc.player.isUsingItem() && isSpear(mc.player.getActiveItem())) {
            mc.interactionManager.stopUsingItem(mc.player);
        }
    }

    /** Uses the item in that hand with the server looking where we aim, not where the camera points. */
    private void use(Hand hand) {
        float yaw = mc.player.getYaw();
        float pitch = mc.player.getPitch();

        mc.player.setYaw(aimYaw);
        mc.player.setPitch(aimPitch);

        mc.interactionManager.interactItem(mc.player, hand);

        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    // ---- Rockets ----

    private void tickBoost(boolean canInterrupt) {
        if (!autoBoost.get() || boostTimer > 0 || !mc.player.isGliding()) return;

        FindItemResult rocket = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!rocket.found()) return;

        if (rocket.isOffhand()) {
            use(Hand.OFF_HAND);
        } else {
            // Swapping off the spear resets the charge, so hotbar rockets are only fired while far away.
            if (!canInterrupt) return;

            if (mc.player.isUsingItem()) mc.interactionManager.stopUsingItem(mc.player);

            if (!InvUtils.swap(rocket.slot(), true)) return;
            use(Hand.MAIN_HAND);
            InvUtils.swapBack();
        }

        boostTimer = boostDelay.get();
    }

    // ---- Jab ----

    private void tickJab(ItemStack spear, Vec3d from, Vec3d aim, double minReach, double maxReach) {
        if (jabTimer > 0 || mc.player.isUsingItem()) return;
        if (spear.get(DataComponentTypes.PIERCING_WEAPON) == null) return;
        if (mc.player.isBelowMinimumAttackCharge(spear, 0)) return;

        double dist = from.distanceTo(aim);
        if (dist < minReach - 0.15 || dist > maxReach + 0.15) return;
        if (smart.get() && !visible(from, aim)) return;

        jabTimer = 4;

        // The server jabs along the direction it last saw us looking, so jab right after the rotation is sent.
        Rotations.rotate(aimYaw, aimPitch, 100, this::jab);
    }

    private void jab() {
        if (mc.player == null || mc.interactionManager == null) return;

        ItemStack stack = mc.player.getMainHandStack();
        PiercingWeaponComponent piercing = stack.get(DataComponentTypes.PIERCING_WEAPON);
        if (piercing == null || mc.player.isBelowMinimumAttackCharge(stack, 0)) return;

        mc.interactionManager.attackWithPiercingWeapon(piercing);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    // ---- Aiming ----

    private boolean updateAim(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        if (horizontal < 1.0E-5 && Math.abs(dy) < 1.0E-5) return false;

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

        // Keep yaw continuous so the camera never spins the long way round.
        aimYaw = mc.player.getYaw() + MathHelper.wrapDegrees(yaw - mc.player.getYaw());
        aimPitch = MathHelper.clamp(pitch, -90.0f, 90.0f);
        return true;
    }

    /** Smoothed per-tick movement of the target, from its positions. */
    private void updateTargetVelocity() {
        double vx = target.getX() - target.lastX;
        double vy = target.getY() - target.lastY;
        double vz = target.getZ() - target.lastZ;

        // Ignore teleports and lag spikes.
        if (vx * vx + vy * vy + vz * vz > 36.0) {
            vx = 0;
            vy = 0;
            vz = 0;
        }

        if (!hasTargetVel) {
            tvx = vx;
            tvy = vy;
            tvz = vz;
            hasTargetVel = true;
        } else {
            tvx = tvx * 0.4 + vx * 0.6;
            tvy = tvy * 0.4 + vy * 0.6;
            tvz = tvz * 0.4 + vz * 0.6;
        }
    }

    /**
     * How far ahead of the target to aim. With Intercept, this is where it will be when we get there
     * at our current speed; then a little extra for ping.
     */
    private Vec3d lead(LivingEntity entity, Vec3d from, double mySpeed) {
        if (!smart.get()) return new Vec3d(0, 0, 0);

        double vy = entity.isOnGround() ? 0.0 : tvy;
        double ticks = prediction.get();

        if (intercept.get() && mySpeed > 0.3) {
            Vec3d center = new Vec3d(entity.getX(), entity.getY() + entity.getHeight() * 0.5, entity.getZ());
            double t = 0.0;

            for (int i = 0; i < 5; i++) {
                double total = t + ticks;
                Vec3d future = center.add(tvx * total, vy * total, tvz * total);
                t = Math.min(future.distanceTo(from) / mySpeed, maxLead.get());
            }

            ticks += t;
        }

        return new Vec3d(tvx * ticks, vy * ticks, tvz * ticks);
    }

    /** The point on the target to aim at: its middle, or its head / feet if the middle is behind a block. */
    private Vec3d aimPoint(LivingEntity entity, Vec3d from, double mySpeed) {
        Vec3d lead = lead(entity, from, mySpeed);

        if (smart.get()) {
            for (double fraction : AIM_HEIGHTS) {
                Vec3d point = bodyPoint(entity, fraction, lead);
                if (visible(from, point)) return point;
            }
        }

        return bodyPoint(entity, 0.5, lead);
    }

    private Vec3d bodyPoint(LivingEntity entity, double heightFraction, Vec3d lead) {
        return new Vec3d(entity.getX() + lead.x, entity.getY() + lead.y + entity.getHeight() * heightFraction, entity.getZ() + lead.z);
    }

    private boolean visible(Vec3d from, Vec3d to) {
        return mc.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    // ---- Targets ----

    private boolean isSpear(ItemStack stack) {
        return !stack.isEmpty() && stack.isIn(ItemTags.SPEARS);
    }

    private LivingEntity findTarget() {
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        Vec3d look = mc.player.getRotationVec(1.0f);
        Vec3d eye = mc.player.getEyePos();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || !isValidTarget(living, 0.0)) continue;

            double score = switch (sorting.get()) {
                case Distance -> mc.player.squaredDistanceTo(living);
                case Health -> living.getHealth() + living.getAbsorptionAmount();
                case Angle -> {
                    Vec3d to = new Vec3d(living.getX(), living.getY() + living.getHeight() * 0.5, living.getZ()).subtract(eye);
                    double length = to.length();
                    yield length < 1.0E-4 ? 0.0 : 1.0 - to.dotProduct(look) / length;
                }
            };

            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }

        return best;
    }

    private boolean isValidTarget(LivingEntity entity, double extraRange) {
        if (entity == mc.player || entity == mc.player.getVehicle()) return false;
        if (!entity.isAlive() || entity.isDead() || entity.getHealth() <= 0) return false;
        if (entity instanceof ArmorStandEntity) return false;

        double maxRange = range.get() + extraRange;
        if (mc.player.squaredDistanceTo(entity) > maxRange * maxRange) return false;

        if (entity instanceof PlayerEntity player) {
            if (!players.get()) return false;
            if (player.isCreative() || player.isSpectator()) return false;
            if (!Friends.get().shouldAttack(player)) return false;
            return nakeds.get() || !isNaked(player);
        }

        if (entity instanceof Angerable) return neutrals.get();
        if (entity instanceof Monster) return hostiles.get();
        if (entity instanceof PassiveEntity || entity instanceof WaterCreatureEntity || entity instanceof AmbientEntity) return animals.get();

        return false;
    }

    private boolean isNaked(PlayerEntity player) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (!player.getEquippedStack(slot).isEmpty()) return false;
        }

        return true;
    }

    // ---- Render: smooth camera + visuals ----

    @EventHandler
    private void onRender(Render3DEvent event) {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0.0 : Math.min((now - lastFrame) / 1.0E9, 0.1);
        lastFrame = now;

        // Ease the camera toward the aim every frame instead of snapping once a tick. This is what
        // keeps it from stuttering.
        if (steering && mc.player != null && isActive()) {
            double factor = 1.0 - Math.exp(-turnSpeed.get() * dt);
            float yaw = mc.player.getYaw();
            float pitch = mc.player.getPitch();

            mc.player.setYaw(yaw + (float) (MathHelper.wrapDegrees(aimYaw - yaw) * factor));
            mc.player.setPitch(MathHelper.clamp(pitch + (float) ((aimPitch - pitch) * factor), -90.0f, 90.0f));
        }

        if (!render.get() || target == null || !target.isAlive()) return;

        SettingColor line = color.get();
        sideColor.set(line.r, line.g, line.b, Math.min(line.a, 45));

        double x = MathHelper.lerp(event.tickDelta, target.lastRenderX, target.getX()) - target.getX();
        double y = MathHelper.lerp(event.tickDelta, target.lastRenderY, target.getY()) - target.getY();
        double z = MathHelper.lerp(event.tickDelta, target.lastRenderZ, target.getZ()) - target.getZ();

        Box box = target.getBoundingBox();
        event.renderer.box(x + box.minX, y + box.minY, z + box.minZ, x + box.maxX, y + box.maxY, z + box.maxZ, sideColor, line, ShapeMode.Both, 0);

        Vec3d aim = renderAim;
        if (aim != null) {
            Vec3d c = target.getBoundingBox().getCenter().add(x, y, z);
            event.renderer.line(c.x, c.y, c.z, aim.x, aim.y, aim.z, line);
        }
    }
}
