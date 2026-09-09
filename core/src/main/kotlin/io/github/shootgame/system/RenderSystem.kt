package io.github.shootgame.system

import com.artemis.annotations.All
import com.badlogic.gdx.scenes.scene2d.Stage
import com.github.quillraven.fleks.*
import io.github.shootgame.component.ImageComponent


// pov : this kt class responsible for rendering
@AllOf([ImageComponent::class])
class RenderSystem(
    private val stage: Stage
) : IteratingSystem(){

    override fun onTick() {
        super.onTick()

        with(stage){
            viewport.apply()
            act(deltaTime)
            draw()
        }
    }

    override fun onTickEntity(entity: Entity) {

    }

    override fun onDispose() {
        super.onDispose()
//        TODO:implement dispose sys render convenient way
    }


}
