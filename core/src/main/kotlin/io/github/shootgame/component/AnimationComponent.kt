package io.github.shootgame.component

import com.badlogic.gdx.graphics.g2d.Animation
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable

enum class AnimationType{
    IDLE , WALK ,RUN , GRENADE, DEAD, HURT, RECHARGE, ATTACK, SHOOT1 ,SHOOT2;

    val atlasKey: String = this.toString().lowercase().replaceFirstChar { it.uppercase() }
}
class AnimationComponent(
    var atlasKey: String = "",
    var stateTime: Float = 0f,
    var playMode: Animation.PlayMode = Animation.PlayMode.LOOP
) {
    lateinit var animation: Animation<TextureRegionDrawable>
    var nextAnimation: String = NO_ANIMATION

    fun nextAnimation(atlasKey: String, type: AnimationType) {
        this.atlasKey = atlasKey
        nextAnimation = if (atlasKey.isBlank()) type.atlasKey else "$atlasKey/${type.atlasKey}"
    }

    companion object {
        const val NO_ANIMATION = ""
    }
}
