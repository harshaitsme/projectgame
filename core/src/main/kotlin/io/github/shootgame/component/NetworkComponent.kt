package io.github.shootgame.component

class NetworkComponent(
    var networkId: Int = -1,
    var isLocal: Boolean = false,
    var playerName: String = "",
    var targetX: Float = 0f,
    var targetY: Float = 0f,
    var targetCos: Float = 0f,
    var targetSin: Float = 1f,
    var attackHoldTimer: Float = 0f,
    var reloadHoldTimer: Float = 0f,
    var throwHoldTimer: Float = 0f,
    var moveHoldTimer: Float = 0f,
    var kills: Int = 0,
    var deaths: Int = 0,
    var weaponIndex: Int = 0,
    var hasInitializedPos: Boolean = false
)
