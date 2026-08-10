package org.dallasmakerspace.routing

import io.ktor.http.*
import io.ktor.resources.*

@Resource("/groups")
class Groups {

  @Resource("batch") class Batch(val parent: Groups = Groups())

  @Resource("{groupslug}")
  class DMSGroup(
      val parent: Groups = Groups(),
      val groupslug: String,
  ) {
    @Resource("add") class Add(val parent: DMSGroup)

    @Resource("remove") class Remove(val parent: DMSGroup)

    @Resource("has-prerequisite-classes") class HasPrerequisiteClasses(val parent: DMSGroup)

    @Resource("is-admin") class IsAdmin(val parent: DMSGroup)

    @Resource("settings")
    class Settings(val parent: DMSGroup) {
      @Resource("history") class History(val parent: Settings)
    }
  }

  companion object {
    /**
     * Generate a group name from a slug (URL Fragment). The slug can have "+" that get translated
     * to spaces.
     */
    fun getNameFromSlug(slug: String) = slug.replace("+", " ").decodeURLPart()
  }
}
