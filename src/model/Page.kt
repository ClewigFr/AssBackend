package com.ass.model

import com.ass.dao.Pages
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import java.util.*

class Page(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<Page>(Pages)

    var name by Pages.name
    var owner by User optionalReferencedOn Pages.owner
}

data class PageJson(
    var id: UUID,
    var name: String
)

fun Page.toPageJson() = PageJson(
    id.value,
    name
)
