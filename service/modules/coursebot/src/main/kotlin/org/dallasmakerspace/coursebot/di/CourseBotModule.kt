package org.dallasmakerspace.coursebot.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import org.dallasmakerspace.agent.BotAudience
import org.dallasmakerspace.agent.BotDefinition
import org.dallasmakerspace.agent.CreateArtifactTool
import org.dallasmakerspace.agent.ListArtifactsTool
import org.dallasmakerspace.agent.ReadArtifactTool
import org.dallasmakerspace.agent.UpdateArtifactTool
import org.dallasmakerspace.askai.tools.ReadSourcePageTool
import org.dallasmakerspace.askai.tools.ReadTalkTopicTool
import org.dallasmakerspace.askai.tools.SearchOnlineCoursesTool
import org.dallasmakerspace.askai.tools.SearchSourceWikiTool
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.coursebot.tools.CourseBotPrompt
import org.dallasmakerspace.coursebot.tools.ListLearnCoursesTool

/** The course assistant: helps online course editors design courses and quizzes. */
@Module
class CourseBotModule {

  @Provides
  @IntoSet
  @Suppress("LongParameterList")
  fun provideCourseBot(
      createArtifact: CreateArtifactTool,
      updateArtifact: UpdateArtifactTool,
      readArtifact: ReadArtifactTool,
      listArtifacts: ListArtifactsTool,
      listCourses: ListLearnCoursesTool,
      searchCourses: SearchOnlineCoursesTool,
      searchSource: SearchSourceWikiTool,
      readSource: ReadSourcePageTool,
      readTalkTopic: ReadTalkTopicTool,
  ): BotDefinition =
      BotDefinition(
          key = COURSE_BOT_KEY,
          displayName = "Course Assistant",
          systemPrompt = CourseBotPrompt.TEXT,
          tools =
              listOf(
                  createArtifact, updateArtifact, readArtifact, listArtifacts, listCourses,
                  searchCourses, searchSource, readSource, readTalkTopic),
          featureFlag = ConfigRegistry.AGENT_COURSE_BOT_ENABLED,
          audience = BotAudience.AdGroupSuffix(EDITORS_GROUP_SUFFIX),
          startsPrivate = true,
          maxToolCallsPerTurn = MAX_TOOL_CALLS,
          starterPrompts = CourseBotPrompt.STARTER_PROMPTS,
          intro = "Plan classes and write quizzes for DMS Learn.",
          placeholder = "Describe the class or quiz you want to build…",
      )

  companion object {
    const val COURSE_BOT_KEY = "course-assistant"
    const val EDITORS_GROUP_SUFFIX = "Online Course Editors"
    private const val MAX_TOOL_CALLS = 12
  }
}
