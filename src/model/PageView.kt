package com.ass.model

import com.ass.dao.PagesViews
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import java.util.*


class PageView(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PageView>(PagesViews)

    var page by Page referencedOn PagesViews.page
    var sessionId by PagesViews.sessionId
    var timestamp by PagesViews.timestamp
}

data class PageViewsJson(
    var page: UUID,
    var sessionId: UUID,
    var timestamp: Long
)

fun PageView.toPageViewsJson() = PageViewsJson(
    page.id.value,
    sessionId,
    timestamp
)

data class PostPageViews(val pageViews: List<PageViewsJson>)
