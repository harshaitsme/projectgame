package io.github.shootgame.component

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.ComponentListener

class PlayerComponent {
    var playerName: String = "Player"
    var score: Int = 0
    var kills: Int = 0
    var deaths: Int = 0

    companion object {
        class PlayerComponentListener : ComponentListener<PlayerComponent> {
            override fun onComponentAdded(entity: Entity, component: PlayerComponent) {
            }

            override fun onComponentRemoved(entity: Entity, component: PlayerComponent) {
            }
        }
    }
}
