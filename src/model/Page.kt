package com.ass.model

import com.ass.dao.Pages
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.util.*

class Page(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<Page>(Pages)

    var name by Pages.name
}
