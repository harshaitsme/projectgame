package io.github.shootgame.component

import com.badlogic.gdx.graphics.g2d.Animation
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable


enum class AnimationModel{
    PLAYER, ENEMY, GRENADE, BULLET,UNDEFINED;

    val atlasKey: String = this.toString().lowercase().replaceFirstChar { it.uppercase() }
}
enum class AnimationType{
    IDLE , WALK ,RUN , GRENADE, DEAD, HURT, RECHARGE, ATTACK, SHOT1 ,SHOT2 ,EXPLOSION, UNDEFINED;

    val atlasKey: String = this.toString().lowercase().replaceFirstChar { it.uppercase() }
}
class AnimationComponent(
    var model: AnimationModel = AnimationModel.UNDEFINED,
    var stateTime: Float = 0f,
    var playMode: Animation.PlayMode = Animation.PlayMode.LOOP
) {
    lateinit var animation: Animation<TextureRegionDrawable>
    var nextAnimation: String = NO_ANIMATION

    fun nextAnimation(model: AnimationModel, type: AnimationType) {
        this.model = model
        nextAnimation = "${model.atlasKey}/${type.atlasKey}"
    }

    companion object {
        const val NO_ANIMATION = ""
    }
}
