pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "LiteBrowser"
include(":app")
include(":terminal-emulator")
include(":terminal-view")
