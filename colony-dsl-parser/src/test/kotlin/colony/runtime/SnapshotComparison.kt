package colony.runtime

/** OS process identifiers change between runs; all model data remain in the comparison. */
fun TickSnapshot.withoutPids(): TickSnapshot = copy(entities = entities.map { it.copy(pid = null) })
