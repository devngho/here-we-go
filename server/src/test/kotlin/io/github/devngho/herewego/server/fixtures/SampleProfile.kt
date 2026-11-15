package io.github.devngho.herewego.server.fixtures

import io.github.devngho.herewego.server.model.PlaceCategory
import io.github.devngho.herewego.server.model.UserProfile

object SampleProfile {
    /** 앱 UserProfileRepository 의 초기 프로필과 같은 값. */
    val default = UserProfile(
        interests = setOf(PlaceCategory.CAFE, PlaceCategory.PARK, PlaceCategory.GYM),
        visitCounts = mapOf(
            PlaceCategory.CAFE to 8,
            PlaceCategory.RESTAURANT to 6,
            PlaceCategory.GYM to 5,
            PlaceCategory.PARK to 3,
            PlaceCategory.BOOKSTORE to 2,
        ),
    )
}
