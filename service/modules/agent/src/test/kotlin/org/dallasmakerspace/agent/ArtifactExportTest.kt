package org.dallasmakerspace.agent

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ArtifactExportTest {
  private val quiz =
      Quiz(
          "Soldering basics",
          listOf(
              Quiz.QuizQuestion(
                  "multiple_choice", "Which tool melts solder?",
                  options = listOf(Quiz.QuizOption("Iron", true), Quiz.QuizOption("Hammer"), Quiz.QuizOption("A=B {x}"))),
              Quiz.QuizQuestion(
                  "multiple_choice", "Pick the hot ones",
                  options = listOf(Quiz.QuizOption("Iron", true), Quiz.QuizOption("Gun", true), Quiz.QuizOption("Ice"))),
              Quiz.QuizQuestion("true_false", "Flux helps solder flow.", options = listOf(Quiz.QuizOption("True", true), Quiz.QuizOption("False"))),
              Quiz.QuizQuestion("short_answer", "Name a solder metal.", answers = listOf("tin", "lead"), feedback = "Usually tin."),
              Quiz.QuizQuestion("matching", "Match tool to job", pairs = listOf(Quiz.MatchPair("Iron", "Melts"), Quiz.MatchPair("Wick", "Removes")))))

  @Test
  fun `gift covers every question type and escapes special characters`() {
    val gift = ArtifactExport.gift(quiz)

    assertTrue(gift.contains("::Q1:: Which tool melts solder? {\n=Iron\n~Hammer\n~A\\=B \\{x\\}\n}"), gift)
    assertTrue(gift.contains("~%50%Iron\n~%50%Gun\n~%-100%Ice"), gift)
    assertTrue(gift.contains("::Q3:: Flux helps solder flow. {TRUE}"), gift)
    assertTrue(gift.contains("{\n=tin\n=lead####Usually tin.\n}"), gift)
    assertTrue(gift.contains("=Iron -> Melts\n=Wick -> Removes"), gift)
  }

  @Test
  fun `moodle xml has one question per item with the right types and escaped text`() {
    val xml = ArtifactExport.moodleXml(quiz)

    assertEquals(5, Regex("<question type=").findAll(xml).count())
    assertTrue(xml.contains("""<question type="multichoice">"""))
    assertTrue(xml.contains("""<question type="truefalse">"""))
    assertTrue(xml.contains("""<question type="shortanswer">"""))
    assertTrue(xml.contains("""<question type="matching">"""))
    assertTrue(xml.contains("""<answer fraction="100"><text>Iron</text></answer>"""))
    assertTrue(xml.contains("""<answer fraction="-100"><text>Ice</text></answer>"""))
    assertTrue(xml.contains("A=B {x}"))
    assertTrue(Regex("<single>true</single>").containsMatchIn(xml))
  }

  @Test
  fun `render picks formats by kind and rejects the rest`() {
    val json = ArtifactKinds.json.encodeToString(Quiz.serializer(), quiz)

    assertEquals("soldering-basics.gift.txt", ArtifactExport.render("quiz", "Soldering basics", json, "gift")!!.fileName)
    assertEquals("application/xml", ArtifactExport.render("quiz", "Soldering basics", json, "xml")!!.contentType)
    assertNull(ArtifactExport.render("markdown", "Notes", "# x", "gift"))
    assertNull(ArtifactExport.render("quiz", "Q", "{not json", "gift"))
    assertNotNull(ArtifactExport.render("markdown", "Notes", "# x", "md"))
  }

  @Test
  fun `an outline becomes numbered sections with timed activities`() {
    val outline =
        CourseOutline(
            "Intro",
            listOf(CourseOutline.OutlineSection("Basics", listOf(CourseOutline.OutlineActivity("Safety talk", "lecture", "Rules", 15)))))

    assertEquals(
        "# Intro\n\n## 1. Basics\n- **Safety talk** [lecture] (15 min): Rules\n",
        ArtifactExport.outlineMarkdown(outline).replace("\r\n", "\n"))
  }
}
