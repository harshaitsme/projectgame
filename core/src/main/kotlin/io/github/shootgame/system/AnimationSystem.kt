package io.github.shootgame.system

import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import  com.badlogic.gdx.graphics.g2d.Animation;
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.AnimationComponent
import io.github.shootgame.component.AnimationComponent.Companion.NO_ANIMATION
import io.github.shootgame.component.ImageComponent
import ktx.app.gdxError
import ktx.collections.map
import ktx.log.logger
import kotlin.collections.getOrPut

@AllOf([AnimationComponent::class, ImageComponent::class])
class AnimationSystem(
    private val textureAtlas: TextureAtlas,
    private val animationComps: ComponentMapper<AnimationComponent>,
    private val imageComps: ComponentMapper<ImageComponent>
): IteratingSystem() {


    private val cachedAnimations = mutableMapOf<String, Animation<TextureRegionDrawable>>()
    override fun onTickEntity(entity: Entity) {
        val aniCmp = animationComps[entity]

        if (aniCmp.nextAnimation == NO_ANIMATION) {
            aniCmp.stateTime += deltaTime
        } else {
            aniCmp.animation = animation(aniCmp.nextAnimation)
            aniCmp.nextAnimation = NO_ANIMATION
            aniCmp.stateTime = 0f
        }

        aniCmp.animation.playMode = aniCmp.playMode
        imageComps[entity].image.drawable = aniCmp.animation.getKeyFrame(aniCmp.stateTime)

    }

    private fun animation(aniKeyPath: String) : Animation<TextureRegionDrawable>{
        return cachedAnimations.getOrPut(aniKeyPath){
            log.debug { "New animation is created for '$aniKeyPath'" }

            val regions = textureAtlas.findRegions(aniKeyPath)
            if(regions.isEmpty){
                gdxError("There are no texture regions for $aniKeyPath")
            }

            Animation(DEFAULT_FRAME_DURATION,regions.map{ TextureRegionDrawable(it)})
        }
    }

    companion object{
        private val log = logger<AnimationSystem>()
        private const val DEFAULT_FRAME_DURATION = 1/8f
    }

}
