package org.dallasmakerspace.models

import kotlinx.serialization.Serializable

@Serializable
data class Committee(
    val id: Int,
    val name: String,
    val chairGroupName: String?,
    val description: String? = null,
    val color: String? = null,
    val isActive: Boolean = true,
    val teacherGroups: List<String> = emptyList(),
    val groupPrefixes: List<String> = emptyList(),
    val homepageUrl: String? = null,
    /**
     * DMS Learn (Moodle) top-level course categories, matched by idnumber or name. Moodle only
     * returns idnumbers to admins, so list the category name when it differs from the committee's.
     */
    val learnCategories: List<String> = emptyList(),
) {
  val slug: String
    get() = name.lowercase().replace(" ", "-")

  fun matchesLearnCategory(idNumber: String?, name: String?): Boolean =
      learnCategories.any {
        it.equals(idNumber?.trim(), ignoreCase = true) || it.equals(name?.trim(), ignoreCase = true)
      }
}

object Committees {
  @JvmStatic fun getActiveCommittees(): List<Committee> = ALL.filter { it.isActive }

  val ALL =
      listOf(
          Committee(
              id = 1,
              name = "3D Fab",
              chairGroupName = "3D Fabrication Chair",
              description =
                  "Focuses on creation, maintenance, and education of 3D fabrication processes using equipment such as FDM and SLA printers.",
              teacherGroups = listOf("3D Printer Teachers", "3D Vacuum Former Teachers"),
              groupPrefixes = listOf("3D"),
              homepageUrl = "https://source.dallasmakerspace.org/display/3DFAB/3D+Fabrication",
              learnCategories = listOf("3DFAB", "3D Fabrication"),
              isActive = true,
          ),
          Committee(
              id = 2,
              name = "Blacksmith",
              chairGroupName = "Blacksmith Committee Chair",
              description =
                  "Teaches blacksmithing techniques including forging, metalworking, and hand tools while maintaining workshop safety standards and organizing open forges.",
              teacherGroups = listOf("Blacksmith Teachers"),
              groupPrefixes = listOf("Blacksmith"),
              homepageUrl = "https://source.dallasmakerspace.org/display/BSMITH/Blacksmithing",
              isActive = true,
          ),
          Committee(
              id = 3,
              name = "Creative Arts",
              chairGroupName = "Creative Arts Committee Chair",
              teacherGroups = listOf("CA Teachers"),
              groupPrefixes = listOf("CA"),
              homepageUrl = "https://source.dallasmakerspace.org/display/CA/Creative+Arts",
              learnCategories = listOf("CA"),
              isActive = true,
          ),
          Committee(
              id = 4,
              name = "Ceramics",
              chairGroupName = "Fired Arts Committee Chair",
              description =
                  "Offers an open learning environment teaching hand building, wheel throwing, and slip casting techniques for all skill levels.",
              teacherGroups = listOf("Ceramics Teachers"),
              groupPrefixes = listOf("Ceramics"),
              homepageUrl = "https://source.dallasmakerspace.org/display/CER/Ceramics",
              learnCategories = listOf("CER"),
              isActive = true,
          ),
          Committee(
              id = 5,
              name = "Digital Media",
              chairGroupName = "Digital Media Committee Chair",
              description =
                  "Maintains the digital media room and equipment for audio, photography, video, virtual reality, augmented reality, and 3D modeling projects.",
              teacherGroups = listOf("Digital Media Darkroom Teachers", "Digital Media Teachers"),
              groupPrefixes = listOf("Digital Media"),
              homepageUrl = "https://source.dallasmakerspace.org/display/DM/Digital+Media",
              learnCategories = listOf("DM"),
              isActive = true,
          ),
          Committee(
              id = 6,
              name = "Electronics and Robotics",
              chairGroupName = "Electronics and Robotics Committee Chair",
              teacherGroups = listOf("Electronics Teachers"),
              groupPrefixes = listOf("Electronics"),
              homepageUrl = "https://source.dallasmakerspace.org/display/ELEC/Electronics",
              isActive = true,
          ),
          Committee(
              id = 7,
              name = "Glassworks",
              chairGroupName = "Glassworks Committee Chair",
              description =
                  "Provides facilities and instruction for glass arts including glassblowing and other hot glass techniques for member projects.",
              teacherGroups = listOf("Glassworks Teachers"),
              groupPrefixes = listOf("Glassworks"),
              homepageUrl = "https://source.dallasmakerspace.org/display/GLASS/Glassworks",
              isActive = true,
          ),
          Committee(
              id = 8,
              name = "Jewelry",
              chairGroupName = "Jewelry Committee Chair",
              description =
                  "Maintains the jewelry studio offering training and equipment for jewelry making, lapidary work, enameling, and metal casting techniques.",
              teacherGroups = listOf("Jewelry Casting Teachers", "Lapidary Teachers"),
              groupPrefixes = listOf("Jewelry", "Lapidary"),
              homepageUrl = "https://source.dallasmakerspace.org/display/JEWEL/Jewelry+Studio",
              learnCategories = listOf("Jewelry"),
              isActive = true,
          ),
          Committee(
              id = 9,
              name = "Laser",
              chairGroupName = "Laser Committee Chair",
              description =
                  "Trains members in safe use of laser cutters (Epilog, Zing, Thunder Nova), maintains equipment, manages consumables, and promotes laser technology innovation.",
              teacherGroups = listOf("Laser Teachers"),
              groupPrefixes = listOf("Laser"),
              homepageUrl = "https://source.dallasmakerspace.org/display/LASER/Laser",
              learnCategories = listOf("Laser"),
              isActive = true,
          ),
          Committee(
              id = 11,
              name = "Machine Shop",
              chairGroupName = "Machine Shop Committee Chair",
              description =
                  "Maintains machine shop tooling and equipment, trains members on safe operation, and oversees asset usage for precision machining projects.",
              teacherGroups =
                  listOf(
                      "Machine Shop Haas Teachers",
                      "Machine Shop Nomad Teachers",
                      "Machine Shop Shapeoko Teachers",
                      "Machine Shop Teachers",
                  ),
              groupPrefixes = listOf("Machine Shop"),
              homepageUrl = "https://source.dallasmakerspace.org/display/MACH/Machine+Shop",
              learnCategories = listOf("Mach"),
              isActive = true,
          ),
          Committee(
              id = 19,
              name = "Metal Shop",
              chairGroupName = "Metal Shop Committee Chair",
              description =
                  "Provides welding, metalworking, and metal fabrication facilities with equipment maintenance and member access management.",
              teacherGroups = listOf("Metal Shop Teachers"),
              groupPrefixes = listOf("Metal Shop"),
              homepageUrl = "https://source.dallasmakerspace.org/display/METAL/Metal+Shop",
              learnCategories = listOf("METAL"),
              isActive = true,
          ),
          Committee(
              id = 12,
              name = "Science",
              chairGroupName = "Science Committee Chair",
              description =
                  "Educates members in scientific subjects through Special Interest Groups (SIGs) in Biology, Chemistry, Physics, Rockets, and Computing via weekly Science Sunday events.",
              teacherGroups = listOf("Science Teachers"),
              groupPrefixes = listOf("Science"),
              homepageUrl = "https://source.dallasmakerspace.org/display/SCIENCE/Science",
              learnCategories = listOf("SCI"),
              isActive = true,
          ),
          Committee(
              id = 13,
              name = "Woodshop",
              chairGroupName = "Woodshop Committee Chair",
              description =
                  "Maintains a knowledge base for woodworking safety, training, and tool information while supporting wood crafting projects at the makerspace.",
              teacherGroups =
                  listOf("WS Shapeoko Teachers", "Woodshop Multicam Teachers", "Woodshop Teachers"),
              groupPrefixes = listOf("Woodshop", "WS"),
              homepageUrl = "https://source.dallasmakerspace.org/display/WOOD/Woodshop+Committee",
              learnCategories = listOf("WOOD"),
              isActive = true,
          ),
          Committee(
              id = 14,
              name = "VECTOR",
              chairGroupName = "VECTOR Committee Chair",
              description =
                  "Focuses on restoration and education of vintage electromechanical arcade technology (including pinball machines) with hands-on learning and collaborative projects.",
              homepageUrl = "https://source.dallasmakerspace.org/display/VECTOR/Vector",
              isActive = true,
          ),
          Committee(
              id = 15,
              name = "Public Relations",
              chairGroupName = "Public Relations Committee Chair",
              isActive = true,
          ),
          Committee(
              id = 16,
              name = "Printmaking",
              chairGroupName = "Printmaking Committee Chair",
              description =
                  "Teaches various printing methods with beginner-friendly classes organized by difficulty level for all experience levels.",
              homepageUrl = "https://source.dallasmakerspace.org/display/PRINT/Printmaking",
              isActive = true,
          ),
          Committee(
              id = 20,
              name = "MotorSports",
              chairGroupName = "MotorSports Committee Chair",
              description =
                  "Manages motorsports and vehicle projects including racing vehicles, tools, and related equipment for automotive enthusiasts.",
              homepageUrl = "https://source.dallasmakerspace.org/display/RACE/Motorsports",
              isActive = true,
          ),
          Committee(
              id = 21,
              name = "Logistics",
              chairGroupName = "Logistics Committee Chair",
              isActive = true,
          ),
          Committee(
              id = 22,
              name = "Infrastructure",
              chairGroupName = "Infrastructure Committee Chair",
              isActive = true,
          ),
          Committee(
              id = 23,
              name = "Financial",
              chairGroupName = "Financial Committee Chair",
              isActive = true,
          ),
          Committee(
              id = 17,
              name = "Automotive",
              chairGroupName = "Automotive Committee Chair",
              groupPrefixes = listOf("Automotive"),
              learnCategories = listOf("AUTO"),
              isActive = true,
          ),
          Committee(
              id = 18,
              name = "Animatronics",
              chairGroupName = "Animatronics Committee Chair",
              groupPrefixes = listOf("Animatronics"),
              isActive = true,
          ),
      )

  fun findById(id: Int): Committee? = ALL.find { it.id == id }

  fun findByName(name: String): Committee? = ALL.find { it.name.equals(name, ignoreCase = true) }

  fun findBySlug(slug: String): Committee? = ALL.find { it.slug == slug }
}
