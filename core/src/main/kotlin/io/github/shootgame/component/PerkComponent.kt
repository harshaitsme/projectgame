package io.github.shootgame.component

enum class PerkType(val title: String, val tag: String, val description: String) {
    SWIFT_BOOTS("Cyber Sprinters", "SPD", "+25% movement speed"),
    RAPID_FIRE("Overclocked Trigger", "ROF", "+30% weapon fire rate"),
    DOUBLE_JUMP("Air Thrusters", "2X JUMP", "Enables mid-air double jump"),
    VAMPIRISM("Nano-Siphon", "VAMP", "Restores 15 HP on enemy elimination")
}

class PerkComponent(
    val activePerks: MutableSet<PerkType> = mutableSetOf()
) {
    fun hasPerk(perk: PerkType): Boolean = perk in activePerks

    fun addPerk(perk: PerkType): Boolean = activePerks.add(perk)

    fun removePerk(perk: PerkType): Boolean = activePerks.remove(perk)
}
