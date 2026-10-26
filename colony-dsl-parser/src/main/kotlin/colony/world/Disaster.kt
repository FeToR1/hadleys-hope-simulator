package colony.world

/** Manual interventions are queued by the controller and committed with the next world step. */
sealed interface Disaster {
    data class ReactorExplosion(val radius: Double, val damage: Double) : Disaster {
        init {
            require(radius.isFinite() && radius in 0.0..300.0) { "Радиус должен быть от 0 до 300 м" }
            require(damage.isFinite() && damage in 0.0..200.0) { "Урон должен быть от 0 до 200 HP" }
        }
    }
    data class Crocodiles(val count: Int) : Disaster {
        init { require(count in 1..32) { "Количество крокодилов должно быть от 1 до 32" } }
    }
    data class Monsters(val count: Int, val duration: Double) : Disaster {
        init {
            require(count in 1..100) { "Количество монстров должно быть от 1 до 100" }
            require(duration.isFinite() && duration in 10.0..600.0) { "Длительность атаки должна быть от 10 до 600 с" }
        }
    }
}
