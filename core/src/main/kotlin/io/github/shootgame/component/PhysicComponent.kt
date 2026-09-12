package io.github.shootgame.component

import com.badlogic.gdx.physics.box2d.Body
import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.World
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.github.quillraven.fleks.EntityCreateCfg
import ktx.box2d.BodyDefinition
import ktx.box2d.body

class PhysicComponent {
    lateinit var body: Body

    companion object{
        fun EntityCreateCfg.physicCmpFromImage(
            world: World,
            image: Image,
            bodyType: BodyDef.BodyType,
            fixtureAction: BodyDefinition.(PhysicComponent, Float, Float) -> Unit
        ): PhysicComponent{
            val x =image.x
            val y = image.y
            val w = image.width
            val h = image.height

            return add{
                body = world.body(bodyType)
                {
                    position.set(x+w*0.5f,y+h*0.5f)
                    fixedRotation = true
                    allowSleep = false
                    fixtureAction(this@add,w,h)
                }
            }
        }
    }
}
