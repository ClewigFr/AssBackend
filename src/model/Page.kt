package com.ass.model

import com.ass.dao.Pages
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

class Page(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<Page>(Pages)

    var name by Pages.name
    var owner by User optionalReferencedOn Pages.owner
}
