package io.github.shootgame.system

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Animation
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.World as PhWorld
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Scaling
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import io.github.shootgame.Main
import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.component.*
import io.github.shootgame.component.PhysicComponent.Companion.physicCmpFromImage
import io.github.shootgame.screen.GameConfig
import io.github.shootgame.screen.GameMode
import ktx.box2d.box
import kotlin.math.max

enum class MatchRoundState {
    STARTING,       // Countdown before round begins (3... 2... 1... FIGHT!)
    ACTIVE,         // Active combat in progress
    ROUND_OVER,     // Round finished, winner announced, intermission
    MATCH_OVER      // All rounds complete, final match champion ceremony
}

data class MatchCombatant(
    val id: Int,
    var name: String,
    val isLocal: Boolean,
    var slot: Int = 0,
    var roundsWon: Int = 0,
    var kills: Int = 0,
    var deaths: Int = 0,
    var damageDealt: Float = 0f,
    var isAlive: Boolean = true,
    var entity: Entity? = null
)

class MatchRoundSystem(
    private val phWorld: PhWorld,
    private val atlas: TextureAtlas,
    private val audioService: AudioService,
    private val config: GameConfig,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val remotePlayerCmps: ComponentMapper<RemotePlayerComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val shieldCmps: ComponentMapper<ShieldComponent>,
    private val deadCmps: ComponentMapper<DeadComponent>,
    private val weaponCmps: ComponentMapper<WeaponComponent>,
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val animationCmps: ComponentMapper<AnimationComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val netCmps: ComponentMapper<NetworkComponent>
) : IntervalSystem() {

    private val networkSystem: NetworkSystem by lazy { world.system<NetworkSystem>() }

    val isMatchRoundMode: Boolean
        get() = config.mode == GameMode.DUO || config.mode == GameMode.SQUAD

    var totalRounds: Int = max(1, config.totalRounds)
        private set

    var currentRound: Int = 1
        private set

    var state: MatchRoundState = MatchRoundState.STARTING
        private set

    var stateTimer: Float = 3.0f
        private set

    var roundDuration: Float = 0f
        private set

    var roundWinner: MatchCombatant? = null
        private set

    var matchWinner: MatchCombatant? = null
        private set

    var isOvertimeRound: Boolean = false
        private set

    private var countdownLastSecond: Int = -1

    // Distinct arena indoor floor spawn locations (x, y)
    val spawnLocations = listOf(
        12.47f to 5.2f, // Slot 0 (Local Player)
        6.5f to 5.2f,   // Slot 1 (Opponent 1)
        15.5f to 5.2f,  // Slot 2 (Opponent 2)
        22.5f to 6.2f   // Slot 3 (Opponent 3)
    )

    private val combatantsList = mutableListOf<MatchCombatant>()
    val combatants: List<MatchCombatant>
        get() = combatantsList

    val aliveCombatantsCount: Int
        get() = combatantsList.count { it.isAlive }

    val totalCombatantsCount: Int
        get() = combatantsList.size

    private var initializedCombatants = false
    private var botInitDelay = 0.6f

    override fun onTick() {
        if (!isMatchRoundMode) return

        // 1. Ensure all match combatants are initialized
        if (!initializedCombatants) {
            botInitDelay -= deltaTime
            if (botInitDelay <= 0f) {
                initializeCombatants()
            }
            return
        }

        // 2. Sync combatant entity references and stats
        updateCombatantEntities()

        // 3. State machine handling
        when (state) {
            MatchRoundState.STARTING -> {
                stateTimer -= deltaTime
                val currentSecond = max(1, stateTimer.toInt() + 1)
                if (currentSecond != countdownLastSecond && stateTimer > 0f) {
                    countdownLastSecond = currentSecond
                    audioService.play(SoundType.CLICK, volumeModifier = 0.6f, basePitch = 1.2f)
                }

                // Freeze player physics/movement during countdown
                freezeAllCombatants()

                if (stateTimer <= 0f) {
                    state = MatchRoundState.ACTIVE
                    roundDuration = 0f
                    audioService.play(SoundType.RELOAD, volumeModifier = 0.9f)
                    networkSystem.postFeed("ROUND $currentRound - FIGHT!", duration = 3.0f)
                    unfreezeAllCombatants()
                }
            }

            MatchRoundState.ACTIVE -> {
                roundDuration += deltaTime

                // Check alive status of each combatant
                combatantsList.forEach { c ->
                    val e = c.entity
                    if (e != null) {
                        val hp = healthCmps.getOrNull(e)
                        val dead = deadCmps.getOrNull(e)
                        val alive = hp != null && hp.currentHealth > 0f && !hp.isDead && dead == null
                        if (c.isAlive && !alive) {
                            c.isAlive = false
                            c.deaths++
                            networkSystem.postFeed("${c.name.uppercase()} ELIMINATED!", duration = 3.0f)
                        }
                    }
                }

                val living = combatantsList.filter { it.isAlive }

                // Check for round winner
                if (living.size <= 1 && combatantsList.size >= 2) {
                    val winner = living.firstOrNull()
                    roundWinner = winner
                    if (winner != null) {
                        winner.roundsWon++
                        winner.kills++
                        audioService.play(SoundType.RELOAD, volumeModifier = 1.0f)
                        networkSystem.postFeed("★ ROUND $currentRound WON BY ${winner.name}! ★", duration = 4.0f)
                    } else {
                        networkSystem.postFeed("★ ROUND $currentRound - DRAW! ★", duration = 4.0f)
                    }

                    state = MatchRoundState.ROUND_OVER
                    stateTimer = 3.8f
                } else if (roundDuration >= 90.0f) {
                    // Timeout (90s round cap): highest remaining health wins
                    val winner = living.maxByOrNull { c ->
                        c.entity?.let { healthCmps.getOrNull(it)?.currentHealth } ?: 0f
                    }
                    roundWinner = winner
                    if (winner != null) {
                        winner.roundsWon++
                        winner.kills++
                        audioService.play(SoundType.RELOAD, volumeModifier = 1.0f)
                        networkSystem.postFeed("★ TIME'S UP! ROUND $currentRound WON BY ${winner.name}! ★", duration = 4.0f)
                    }
                    state = MatchRoundState.ROUND_OVER
                    stateTimer = 3.8f
                }
            }

            MatchRoundState.ROUND_OVER -> {
                stateTimer -= deltaTime
                freezeAllCombatants()

                if (stateTimer <= 0f) {
                    // Check if match has finished
                    val isDecisive = checkMatchFinished()
                    if (isDecisive) {
                        finalizeMatchWinner()
                        state = MatchRoundState.MATCH_OVER
                        audioService.play(SoundType.EXPLOSION, volumeModifier = 0.5f)
                    } else {
                        // Advance to next round
                        currentRound++
                        resetAllCombatantsForNewRound()
                        state = MatchRoundState.STARTING
                        stateTimer = 3.0f
                        countdownLastSecond = -1
                    }
                }
            }

            MatchRoundState.MATCH_OVER -> {
                freezeAllCombatants()
            }
        }
    }

    private fun checkMatchFinished(): Boolean {
        // If we haven't reached the scheduled round count, continue
        if (currentRound < totalRounds) {
            return false
        }

        // Check if top two combatants are tied in round wins
        val sorted = combatantsList.sortedByDescending { it.roundsWon }
        if (sorted.size >= 2 && sorted[0].roundsWon == sorted[1].roundsWon && !isOvertimeRound) {
            // Exciting sudden-death Overtime Tiebreaker round!
            isOvertimeRound = true
            totalRounds = currentRound + 1
            networkSystem.postFeed("★ TIE! OVERTIME ROUND $totalRounds ★", duration = 4.0f)
            return false
        }

        return true
    }

    private fun finalizeMatchWinner() {
        val champion = combatantsList.maxWithOrNull(
            compareByDescending<MatchCombatant> { it.roundsWon }
                .thenByDescending { it.kills }
                .thenByDescending { it.damageDealt }
                .thenBy { it.deaths }
        )
        matchWinner = champion
        val champName = champion?.name?.uppercase() ?: "NO ONE"
        networkSystem.postFeed("🏆 MATCH OVER! CHAMPION: $champName! 🏆", duration = 8.0f)
    }

    private fun initializeCombatants() {
        combatantsList.clear()

        // 1. Local Player (Slot 0)
        var localEntity: Entity? = null
        world.family(allOf = arrayOf(PlayerComponent::class), noneOf = arrayOf(RemotePlayerComponent::class)).forEach { p ->
            localEntity = p
        }

        val localName = config.playerName.ifEmpty { "PLAYER" }
        val localCombatant = MatchCombatant(
            id = 0,
            name = localName,
            isLocal = true,
            slot = 0,
            entity = localEntity
        )
        combatantsList.add(localCombatant)

        // 2. Add connected remote human players
        var nextSlot = 1
        world.family(allOf = arrayOf(RemotePlayerComponent::class, NetworkComponent::class)).forEach { remote ->
            val net = netCmps[remote]
            val rName = net.playerName.ifEmpty { "Player_${net.networkId}" }
            val remoteCombatant = MatchCombatant(
                id = net.networkId,
                name = rName,
                isLocal = false,
                slot = nextSlot++,
                entity = remote
            )
            combatantsList.add(remoteCombatant)
        }

        // 3. Fill remaining slots with AI Bots
        val requiredCount = if (config.mode == GameMode.SQUAD) 4 else 2
        val botNames = listOf("CYBER_BOT", "BOT_VIPER", "BOT_GHOST", "BOT_TITAN")

        while (combatantsList.size < requiredCount && nextSlot < 4) {
            val (sx, sy) = spawnLocations[nextSlot]
            val bName = botNames[nextSlot % botNames.size]
            val botEntity = spawnArenaBot(bName, sx, sy, nextSlot)
            val botCombatant = MatchCombatant(
                id = -(nextSlot + 100),
                name = bName,
                isLocal = false,
                slot = nextSlot,
                entity = botEntity
            )
            combatantsList.add(botCombatant)
            nextSlot++
        }

        // Teleport everyone to their proper spawn locations for Round 1
        resetAllCombatantsForNewRound()
        initializedCombatants = true
    }

    private fun spawnArenaBot(name: String, x: Float, y: Float, slot: Int): Entity {
        val botColors = listOf(
            Color(1.0f, 0.40f, 0.40f, 1f), // Crimson
            Color(0.40f, 0.85f, 1.0f, 1f), // Tactical Cyan
            Color(0.85f, 0.45f, 1.0f, 1f), // Cyber Violet
            Color(1.0f, 0.78f, 0.20f, 1f)  // Neon Amber
        )
        val botColor = botColors[slot % botColors.size]

        return world.entity { botEntity ->
            add<PlayerComponent> {
                playerName = name
            }
            add<EnemyComponent> {
                scoreValue = 600
            }
            add<AiComponent> {
                detectionRadius = 30f
                attackRange = 16f
                shootCooldown = 0.85f
                jumpCooldown = 2.0f
            }
            add<MoveComponent> {
                speed = 3.6f
                baseSpeed = 3.6f
            }
            add<AttackComponent> {
                ammo = 999
                maxAmmo = 999
                fireRate = 0.8f
            }
            add<WeaponComponent>()
            add<HealthComponent> {
                maxHealth = 100f
                currentHealth = 100f
            }
            add<ShieldComponent> {
                maxShield = 100f
                currentShield = 100f
            }
            val imgCmp = add<ImageComponent> {
                image = Image(atlas.findRegion("Player/Idle", 0)).apply {
                    setSize(0.75f, 1.25f)
                    setPosition(x - 0.375f, y - 0.625f)
                    color = botColor
                    setScaling(Scaling.fill)
                }
            }
            add<AnimationComponent> {
                nextAnimation(AnimationModel.PLAYER, AnimationType.IDLE)
            }
            add<StatusEffectComponent>()
            add<OwnerComponent> {
                owner = botEntity
            }

            physicCmpFromImage(phWorld, imgCmp.image, BodyDef.BodyType.DynamicBody) { _, width, height ->
                box(width = width * 0.25f, height = height * 0.85f) {
                    isSensor = false
                    friction = 0f
                }
            }
        }
    }

    private fun updateCombatantEntities() {
        combatantsList.forEach { c ->
            if (c.entity == null) {
                if (c.isLocal) {
                    world.family(allOf = arrayOf(PlayerComponent::class), noneOf = arrayOf(RemotePlayerComponent::class)).forEach { p ->
                        c.entity = p
                    }
                } else if (c.id > 0) {
                    world.family(allOf = arrayOf(RemotePlayerComponent::class, NetworkComponent::class)).forEach { r ->
                        if (netCmps[r].networkId == c.id) {
                            c.entity = r
                        }
                    }
                }
            }
        }
    }

    private fun freezeAllCombatants() {
        combatantsList.forEach { c ->
            val e = c.entity ?: return@forEach
            val move = moveCmps.getOrNull(e)
            if (move != null) {
                move.cos = 0f
                move.sin = 0f
                move.doJump = false
            }
            val attack = attackCmps.getOrNull(e)
            if (attack != null) {
                attack.isAttacking = false
                attack.isThrowing = false
            }
            val body = physicCmps.getOrNull(e)?.body
            body?.setLinearVelocity(0f, 0f)
        }
    }

    private fun unfreezeAllCombatants() {
        // Nothing special needed, next player/AI tick will drive them
    }

    fun resetAllCombatantsForNewRound() {
        combatantsList.forEach { c ->
            val e = c.entity ?: return@forEach
            c.isAlive = true

            // Revive health and shield
            val hp = healthCmps.getOrNull(e)
            if (hp != null) {
                hp.currentHealth = hp.maxHealth
                hp.isDead = false
                hp.invulnerableTime = 2.0f // 2s spawn barrier
            }
            val shield = shieldCmps.getOrNull(e)
            if (shield != null) {
                shield.currentShield = shield.maxShield
            }

            // Remove DeadComponent
            if (e in deadCmps) {
                world.configureEntity(e) {
                    deadCmps.remove(e)
                }
            }

            // Refill ammo
            val attack = attackCmps.getOrNull(e)
            if (attack != null) {
                attack.ammo = attack.maxAmmo
                attack.isReloading = false
                attack.isAttacking = false
                attack.stateTime = 0f
            }
            weaponCmps.getOrNull(e)?.refillAllAmmo()

            // Reset animation
            val anim = animationCmps.getOrNull(e)
            if (anim != null) {
                anim.playMode = Animation.PlayMode.LOOP
                anim.nextAnimation(AnimationModel.PLAYER, AnimationType.IDLE)
            }

            // Teleport to assigned starting slot spawn position
            val (sx, sy) = spawnLocations[c.slot % spawnLocations.size]
            val physic = physicCmps.getOrNull(e)
            if (physic != null) {
                physic.body.setTransform(sx, sy, 0f)
                physic.body.setLinearVelocity(0f, 0f)
                physic.body.isAwake = true
            }

            val img = imageCmps.getOrNull(e)?.image
            if (img != null) {
                img.setPosition(sx - img.width * 0.5f, sy - img.height * 0.5f)
            }
        }

        // Force local client to broadcast its revived position & health
        networkSystem.forceImmediateSync()
    }

    fun restartMatch() {
        currentRound = 1
        totalRounds = max(1, config.totalRounds)
        isOvertimeRound = false
        roundWinner = null
        matchWinner = null
        combatantsList.forEach { c ->
            c.roundsWon = 0
            c.kills = 0
            c.deaths = 0
            c.damageDealt = 0f
            c.isAlive = true
        }
        resetAllCombatantsForNewRound()
        state = MatchRoundState.STARTING
        stateTimer = 3.0f
        countdownLastSecond = -1
        networkSystem.postFeed("MATCH RESTARTED! ROUND 1", duration = 3.0f)
    }
}
