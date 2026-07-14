package dev.botalive.core.physics;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

/**
 * Zjednodušená, ale věrohodná fyzika hráče (klientská simulace pohybu).
 *
 * <p>Cíl není bit-exact kopie vanilla klienta, ale pohyb, který:
 * (a) server přijme (rychlosti v mezích anti-cheat kontrol „moved too quickly“),
 * (b) vypadá lidsky (setrvačnost, oblouky, doskoky) a
 * (c) je deterministický vůči {@link WorldView} pro plánování cest.</p>
 *
 * <p>Konstanty vychází z vanilla hodnot: chůze ~4.32 m/s, sprint ~5.61 m/s,
 * gravitace 0.08/tick², skok 0.42, útlum rychlosti 0.91 (země) resp. 0.98 (vzduch).</p>
 */
public final class BotPhysics {

    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG_Y = 0.98;
    private static final double GROUND_FRICTION = 0.546;   // 0.91 * 0.6 slip
    private static final double AIR_FRICTION = 0.91;
    private static final double WALK_ACCEL = 0.098;
    private static final double SPRINT_ACCEL = 0.127;
    private static final double SNEAK_ACCEL = 0.029;
    private static final double AIR_ACCEL = 0.02;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double STEP_HEIGHT = 0.6;
    private static final double WATER_DRAG = 0.8;
    private static final double CLIMB_SPEED = 0.12;
    private static final double MAX_FALL_SPEED = -3.92;
    private static final double EPSILON = 1.0E-7;

    private final WorldView world;

    private Vec3 position;
    private Vec3 velocity = Vec3.ZERO;
    private boolean onGround;
    private boolean inWater;
    private boolean onClimbable;
    private boolean horizontalCollision;

    /**
     * @param world pohled na svět pro kolize
     * @param start počáteční pozice (nohy bota)
     */
    public BotPhysics(WorldView world, Vec3 start) {
        this.world = world;
        this.position = start;
    }

    /** @return aktuální pozice nohou bota */
    public Vec3 position() {
        return position;
    }

    /** @return aktuální rychlost (bloky/tick) */
    public Vec3 velocity() {
        return velocity;
    }

    /** @return {@code true} pokud bot stojí na zemi */
    public boolean onGround() {
        return onGround;
    }

    /** @return {@code true} pokud je bot ve vodě */
    public boolean inWater() {
        return inWater;
    }

    /** @return {@code true} pokud tick skončil vodorovnou kolizí (naražení do zdi) */
    public boolean horizontalCollision() {
        return horizontalCollision;
    }

    /**
     * Tvrdé nastavení pozice (teleport od serveru). Vynuluje rychlost.
     *
     * @param pos nová pozice
     */
    public void teleport(Vec3 pos) {
        this.position = pos;
        this.velocity = Vec3.ZERO;
    }

    /**
     * Přičte impulz k rychlosti (odhození výbuchem).
     *
     * @param impulse změna rychlosti
     */
    public void addImpulse(Vec3 impulse) {
        this.velocity = velocity.add(impulse);
    }

    /**
     * Nastaví absolutní rychlost (SetEntityMotion – knockback od serveru).
     *
     * @param newVelocity nová rychlost (bloky/tick)
     */
    public void setVelocity(Vec3 newVelocity) {
        this.velocity = newVelocity;
    }

    /**
     * Provede jeden fyzikální tick.
     *
     * @param input pohybový záměr bota pro tento tick
     */
    public void step(MoveInput input) {
        updateMediums();

        double vx = velocity.x();
        double vy = velocity.y();
        double vz = velocity.z();

        // --- Akcelerace ze vstupu -------------------------------------------------
        Vec3 dir = input.direction();
        if (dir.horizontalLength() > EPSILON) {
            double accel = !onGround ? AIR_ACCEL
                    : input.sneak() ? SNEAK_ACCEL
                    : input.sprint() ? SPRINT_ACCEL
                    : WALK_ACCEL;
            vx += dir.x() * accel;
            vz += dir.z() * accel;
        }

        // --- Vertikála: skok / plavání / šplhání / gravitace ----------------------
        if (inWater) {
            vy = input.jump() ? Math.min(vy + 0.04, 0.12) : (vy - 0.02) * WATER_DRAG;
            vx *= WATER_DRAG;
            vz *= WATER_DRAG;
        } else if (onClimbable) {
            vy = input.jump() ? CLIMB_SPEED : (dir.horizontalLength() > EPSILON ? CLIMB_SPEED : -CLIMB_SPEED * 0.5);
            vx *= 0.7;
            vz *= 0.7;
        } else {
            if (input.jump() && onGround) {
                vy = JUMP_VELOCITY;
                if (input.sprint()) {
                    // sprint-jump boost ve směru pohybu (vanilla chování)
                    Vec3 boost = dir.mul(0.2);
                    vx += boost.x();
                    vz += boost.z();
                }
            }
            vy = (vy - GRAVITY) * AIR_DRAG_Y;
            vy = Math.max(vy, MAX_FALL_SPEED);
        }

        // --- Kolize a posun -------------------------------------------------------
        Vec3 attempted = new Vec3(vx, vy, vz);
        Vec3 moved = collide(attempted, input.sneak());

        position = position.add(moved);
        horizontalCollision = Math.abs(moved.x() - attempted.x()) > EPSILON
                || Math.abs(moved.z() - attempted.z()) > EPSILON;
        boolean verticalCollision = Math.abs(moved.y() - attempted.y()) > EPSILON;
        onGround = verticalCollision && attempted.y() < 0;

        // --- Tření ----------------------------------------------------------------
        double friction = onGround ? GROUND_FRICTION : AIR_FRICTION;
        velocity = new Vec3(moved.x() * friction, moved.y(), moved.z() * friction);
        if (verticalCollision) {
            velocity = new Vec3(velocity.x(), 0, velocity.z());
        }
    }

    /** Zjistí, v jakém prostředí se bot nachází (voda, žebřík). */
    private void updateMediums() {
        BlockPos feet = position.toBlockPos();
        BlockTraits feetTraits = world.traitsAt(feet);
        BlockTraits headTraits = world.traitsAt(feet.up());
        inWater = feetTraits.liquid() || headTraits.liquid();
        onClimbable = feetTraits.climbable();
    }

    /**
     * Axis-by-axis kolizní řešení s automatickým výstupem na schod (step-up).
     *
     * @param motion zamýšlený posun
     * @param sneak  při plížení bot nespadne z hrany
     * @return skutečný možný posun
     */
    private Vec3 collide(Vec3 motion, boolean sneak) {
        AABB box = AABB.playerAt(position);

        double dy = clipAxis(box, motion.y(), Axis.Y);
        box = box.move(0, dy, 0);

        double dx = clipAxis(box, motion.x(), Axis.X);
        box = box.move(dx, 0, 0);

        double dz = clipAxis(box, motion.z(), Axis.Z);

        boolean collidedHorizontally = dx != motion.x() || dz != motion.z();

        // Step-up: pokud jsme narazili do zdi a jsme na zemi, zkusit posun o schod výš.
        if (collidedHorizontally && (onGround || motion.y() < 0)) {
            AABB stepBox = AABB.playerAt(position);
            double stepUp = clipAxis(stepBox, STEP_HEIGHT, Axis.Y);
            stepBox = stepBox.move(0, stepUp, 0);
            double sdx = clipAxis(stepBox, motion.x(), Axis.X);
            stepBox = stepBox.move(sdx, 0, 0);
            double sdz = clipAxis(stepBox, motion.z(), Axis.Z);
            stepBox = stepBox.move(0, 0, sdz);
            double stepDown = clipAxis(stepBox, -stepUp, Axis.Y);

            if (sdx * sdx + sdz * sdz > dx * dx + dz * dz) {
                return new Vec3(sdx, stepUp + stepDown, sdz);
            }
        }
        return new Vec3(dx, dy, dz);
    }

    private enum Axis { X, Y, Z }

    /**
     * Ořízne posun po jedné ose o kolize s pevnými bloky.
     *
     * @param box    hitbox před posunem
     * @param motion zamýšlený posun po ose
     * @param axis   osa
     * @return maximální možný posun
     */
    private double clipAxis(AABB box, double motion, Axis axis) {
        if (Math.abs(motion) < EPSILON) {
            return 0;
        }
        AABB swept = switch (axis) {
            case X -> new AABB(
                    Math.min(box.minX(), box.minX() + motion), box.minY(), box.minZ(),
                    Math.max(box.maxX(), box.maxX() + motion), box.maxY(), box.maxZ());
            case Y -> new AABB(
                    box.minX(), Math.min(box.minY(), box.minY() + motion), box.minZ(),
                    box.maxX(), Math.max(box.maxY(), box.maxY() + motion), box.maxZ());
            case Z -> new AABB(
                    box.minX(), box.minY(), Math.min(box.minZ(), box.minZ() + motion),
                    box.maxX(), box.maxY(), Math.max(box.maxZ(), box.maxZ() + motion));
        };

        double result = motion;
        int minX = (int) Math.floor(swept.minX());
        int maxX = (int) Math.floor(swept.maxX());
        int minY = (int) Math.floor(swept.minY());
        int maxY = (int) Math.floor(swept.maxY());
        int minZ = (int) Math.floor(swept.minZ());
        int maxZ = (int) Math.floor(swept.maxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockTraits traits = world.traitsAt(new BlockPos(x, y, z));
                    if (!traits.solid() && traits != BlockTraits.UNKNOWN) {
                        continue; // neznámé chunky bereme jako zeď – bot nevkročí do nenačtena
                    }
                    AABB block = new AABB(x, y, z, x + 1, y + 1, z + 1);
                    result = clipAgainst(box, block, result, axis);
                }
            }
        }
        return result;
    }

    /** Ořízne posun {@code motion} tak, aby {@code box} nepronikl do {@code block}. */
    private static double clipAgainst(AABB box, AABB block, double motion, Axis axis) {
        // Kolidují ostatní dvě osy?
        boolean overlap = switch (axis) {
            case X -> box.minY() < block.maxY() && box.maxY() > block.minY()
                    && box.minZ() < block.maxZ() && box.maxZ() > block.minZ();
            case Y -> box.minX() < block.maxX() && box.maxX() > block.minX()
                    && box.minZ() < block.maxZ() && box.maxZ() > block.minZ();
            case Z -> box.minX() < block.maxX() && box.maxX() > block.minX()
                    && box.minY() < block.maxY() && box.maxY() > block.minY();
        };
        if (!overlap) {
            return motion;
        }
        double boxMin = switch (axis) {
            case X -> box.minX();
            case Y -> box.minY();
            case Z -> box.minZ();
        };
        double boxMax = switch (axis) {
            case X -> box.maxX();
            case Y -> box.maxY();
            case Z -> box.maxZ();
        };
        double blockMin = switch (axis) {
            case X -> block.minX();
            case Y -> block.minY();
            case Z -> block.minZ();
        };
        double blockMax = switch (axis) {
            case X -> block.maxX();
            case Y -> block.maxY();
            case Z -> block.maxZ();
        };
        // Blok omezuje pohyb jen tehdy, leží-li ve směru pohybu (ne za zády,
        // ne v překryvu – z penetrace se bot smí volně vysunout).
        if (motion > 0 && blockMin >= boxMax) {
            return Math.min(motion, Math.max(0, blockMin - boxMax - EPSILON));
        }
        if (motion < 0 && blockMax <= boxMin) {
            return Math.max(motion, Math.min(0, blockMax - boxMin + EPSILON));
        }
        return motion;
    }
}
