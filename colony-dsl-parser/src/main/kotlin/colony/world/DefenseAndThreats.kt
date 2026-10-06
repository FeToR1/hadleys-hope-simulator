package colony.world

import kotlinx.serialization.Serializable
import kotlin.math.*

/**
 * 1D Gradient Noise / Perlin generator for smooth flight trajectory deflections.
 */
class TrajectoryNoise(private val seed: Long) {
    private fun hash(i: Int): Double {
        var h = (i * 374761393L + seed * 668265263L)
        h = (h xor (h shr 13)) * 1274126177L
        return ((h xor (h shr 16)) and 0x7FFFFFFF).toDouble() / 0x7FFFFFFF
    }

    fun sample(t: Double): Double {
        val i0 = floor(t).toInt()
        val i1 = i0 + 1
        val frac = t - i0
        // Smoothstep interpolation
        val s = frac * frac * (3.0 - 2.0 * frac)
        val v0 = (hash(i0) - 0.5) * 2.0
        val v1 = (hash(i1) - 0.5) * 2.0
        return v0 * (1.0 - s) + v1 * s
    }
}

enum class FlightPhase { OUTBOUND, RETURNING }

/**
 * Flying Crocodile threat entity. Spawns from the surrounding forest (W, N, S),
 * flies across settlement along base vector with noise deflection toward the sea (E),
 * turns around above the sea, flies back into the forest along a different trajectory,
 * bombards structures below, and returns to Object Pool upon reaching the forest.
 */
class FlyingCrocodile(
    val id: String
) {
    var active: Boolean = false
    var position: Point = Point(0.0, 0.0)
    var health: Double = 100.0
    var startPoint: Point = Point(0.0, 0.0)
    var turnPoint: Point = Point(0.0, 0.0)
    var exitPoint: Point = Point(0.0, 0.0)
    var phase: FlightPhase = FlightPhase.OUTBOUND
    var speed: Double = 20.0
    var progress: Double = 0.0
    var phaseProgress: Double = 0.0
    var outboundDistance: Double = 1.0
    var returnDistance: Double = 1.0
    var noiseSeed: Long = 0L
    var isDeflected: Boolean = false
    var deflectionVector: Point = Point(0.0, 0.0)
    var attackCooldown: Double = 0.0
    private var outboundNoise = TrajectoryNoise(0L)
    private var returnNoise = TrajectoryNoise(0L)

    val isReturning: Boolean get() = phase == FlightPhase.RETURNING

    fun spawn(
        start: Point,
        turn: Point,
        exit: Point,
        speed: Double = 20.0,
        health: Double = 120.0,
        seed: Long = 0L
    ) {
        this.active = true
        this.startPoint = start
        this.turnPoint = turn
        this.exitPoint = exit
        this.position = start
        this.phase = FlightPhase.OUTBOUND
        this.speed = speed
        this.health = health
        this.progress = 0.0
        this.phaseProgress = 0.0
        this.outboundDistance = max(1.0, start.distanceTo(turn))
        this.returnDistance = max(1.0, turn.distanceTo(exit))
        this.noiseSeed = seed
        this.outboundNoise = TrajectoryNoise(seed)
        this.returnNoise = TrajectoryNoise(seed + 982451653L)
        this.isDeflected = false
        this.deflectionVector = Point(0.0, 0.0)
        this.attackCooldown = 0.0
    }

    fun spawn(
        start: Point,
        target: Point,
        speed: Double = 20.0,
        health: Double = 120.0,
        seed: Long = 0L
    ) {
        val returnExit = Point(start.x, target.y)
        spawn(start, target, returnExit, speed, health, seed)
    }

    fun despanw() {
        active = false
        health = 0.0
    }

    /**
     * Advance position along trajectory with Perlin noise deflection.
     * Reaches sea at turnPoint, turns around, and returns to forest at exitPoint.
     */
    fun update(dt: Double) {
        if (!active) return
        attackCooldown = max(0.0, attackCooldown - dt)

        if (health <= 0.0) {
            despanw()
            return
        }

        if (phase == FlightPhase.OUTBOUND) {
            val step = (speed * dt) / outboundDistance
            phaseProgress += step
            progress = min(0.5, phaseProgress * 0.5)

            if (phaseProgress >= 1.0) {
                // Reached the sea -> turn around towards forest exit
                phase = FlightPhase.RETURNING
                phaseProgress = 0.0
                isDeflected = false
                deflectionVector = Point(0.0, 0.0)
            } else {
                updatePosition(startPoint, turnPoint, phaseProgress, outboundNoise)
            }
        }

        if (phase == FlightPhase.RETURNING) {
            val step = (speed * dt) / returnDistance
            phaseProgress += step
            progress = min(1.0, 0.5 + phaseProgress * 0.5)

            if (phaseProgress >= 1.0) {
                despanw()
                return
            }
            updatePosition(turnPoint, exitPoint, phaseProgress, returnNoise)
        }
    }

    private fun updatePosition(from: Point, to: Point, t: Double, noiseGen: TrajectoryNoise) {
        val baseX = from.x + (to.x - from.x) * t
        val baseY = from.y + (to.y - from.y) * t

        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = max(1e-6, hypot(dx, dy))
        val perpX = -dy / len
        val perpY = dx / len

        val noiseVal = noiseGen.sample(t * 6.0)
        val amplitude = if (isDeflected) 85.0 else 45.0
        val offset = noiseVal * amplitude

        val deflectedX = if (isDeflected) deflectionVector.x * (t * 50.0) else 0.0
        val deflectedY = if (isDeflected) deflectionVector.y * (t * 50.0) else 0.0

        position = Point(baseX + perpX * offset + deflectedX, baseY + perpY * offset + deflectedY)
    }

    fun applyDamage(amount: Double) {
        health = max(0.0, health - amount)
        if (health <= 0.0) {
            despanw()
        }
    }

    fun deflect(vector: Point) {
        isDeflected = true
        deflectionVector = vector
    }
}

/**
 * Object Pool for Flying Crocodiles to prevent frequent memory allocations.
 */
class CrocodilePool(maxCapacity: Int = 128) {
    private val pool = ArrayList<FlyingCrocodile>(maxCapacity)

    init {
        for (i in 1..maxCapacity) {
            pool.add(FlyingCrocodile("crocodile-$i"))
        }
    }

    fun obtain(): FlyingCrocodile? {
        val crocodile = pool.firstOrNull { !it.active }
        return crocodile
    }

    fun activeCrocodiles(): List<FlyingCrocodile> = pool.filter { it.active }

    fun allCrocodiles(): List<FlyingCrocodile> = pool
}

/**
 * Air Defense System (ADS) static unit.
 * Placed along settlement perimeter and on selected house roofs.
 */
class AirDefenseUnit(
    val id: String,
    val position: Point,
    val isRoofMounted: Boolean = false,
    val attachedHouseId: String? = null,
    var range: Double = 95.0,
    var damagePerShot: Double = 40.0,
    var shotCooldown: Double = 1.5
) {
    var health: Double = 100.0
    var broken: Boolean = false
    var cooldownTimer: Double = 0.0
    var shotsFired: Long = 0
    var crocodilesDeflected: Long = 0
    var ammoRemaining: Double = 0.0

    val isOperational: Boolean get() = !broken && health > 0.0

    fun updateCooldown(dt: Double) {
        if (cooldownTimer > 0.0) {
            cooldownTimer = max(0.0, cooldownTimer - dt)
        }
    }

    fun breakDown(reason: String = "fog_corrosion") {
        if (!broken) {
            broken = true
            health = 0.0
        }
    }

    fun repair() {
        broken = false
        health = 100.0
        cooldownTimer = 0.0
    }

    /**
     * Attempts to engage a target crocodile in range.
     * Damages crocodile and deflects its trajectory.
     */
    fun tryEngage(crocodile: FlyingCrocodile, powered: Boolean = true): Boolean {
        if (!isOperational || !powered || ammoRemaining < 1.0 || cooldownTimer > 0.0 || !crocodile.active) return false
        val dist = position.distanceTo(crocodile.position)
        if (dist > range) return false

        // Shoot: deal damage and deflect trajectory
        crocodile.applyDamage(damagePerShot)
        val pushDirX = crocodile.position.x - position.x
        val pushDirY = crocodile.position.y - position.y
        val pushLen = max(1e-6, hypot(pushDirX, pushDirY))
        crocodile.deflect(Point(pushDirX / pushLen, pushDirY / pushLen))

        shotsFired++
        crocodilesDeflected++
        ammoRemaining -= 1.0
        cooldownTimer = shotCooldown
        return true
    }
}

/** Powered, finite-ammunition wall turret that engages ground threats. */
class GroundDefenseUnit(
    val id: String,
    val position: Point,
    var range: Double = 120.0,
    var damagePerShot: Double = 40.0,
    var shotCooldown: Double = 2.0,
    var ammoRemaining: Double = 0.0,
) {
    var health: Double = 100.0
    var cooldownTimer: Double = 0.0
    var shotsFired: Long = 0
    val isOperational: Boolean get() = health > 0.0

    fun updateCooldown(dt: Double) { cooldownTimer = max(0.0, cooldownTimer - dt) }

    fun repair() {
        health = 100.0
        cooldownTimer = 0.0
    }

    fun tryEngage(target: Point, powered: Boolean): Boolean {
        if (!isOperational || !powered || ammoRemaining < 1.0 || cooldownTimer > 0.0 || position.distanceTo(target) > range) return false
        ammoRemaining -= 1.0
        shotsFired++
        cooldownTimer = shotCooldown
        return true
    }
}
