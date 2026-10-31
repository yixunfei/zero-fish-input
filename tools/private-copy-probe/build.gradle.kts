plugins { id("com.android.application") version "8.10.1" apply false }

tasks.register("verifyProbePrivacy") {
    dependsOn(":probe:processPlainDebugMainManifest", ":probe:processPlainReleaseMainManifest",
        ":probe:processOcrDebugMainManifest", ":probe:processOcrReleaseMainManifest",
        ":fixture:processDebugMainManifest", ":fixture:processReleaseMainManifest")
    doLast {
        val androidNs = "http://schemas.android.com/apk/res/android"
        subprojects.forEach { project ->
            val manifests = project.layout.buildDirectory.dir("intermediates/merged_manifest").get().asFile
                    .walkTopDown().filter { it.name == "AndroidManifest.xml" }.toList()
            check(manifests.size == if (project.name == "probe") 4 else 2) { "Missing merged manifests" }
            manifests.forEach { file ->
                val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    isExpandEntityReferences = false
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                val document = factory.newDocumentBuilder().parse(file)
                val permissions = document.getElementsByTagName("uses-permission")
                val actual = (0 until permissions.length).map {
                    (permissions.item(it) as org.w3c.dom.Element).getAttributeNS(androidNs, "name")
                }.toSet()
                val expected = if (project.name == "probe") setOf("android.permission.FOREGROUND_SERVICE",
                    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION") else emptySet()
                check(actual == expected) { "Unexpected research permission" }
                listOf("uses-permission-sdk-23", "provider", "receiver").forEach {
                    check(document.getElementsByTagName(it).length == 0) { "Unexpected exported data path" }
                }
                val app = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
                check(app.getAttributeNS(androidNs, "allowBackup") == "false")
                check(app.getAttributeNS(androidNs, "usesCleartextTraffic") == "false")
                if (file.path.contains("Release", true)) check(app.getAttributeNS(androidNs, "debuggable") != "true")
                val services = document.getElementsByTagName("service")
                check(services.length == if (project.name == "probe") 2 else 0)
                for (index in 0 until services.length) {
                    val service = services.item(index) as org.w3c.dom.Element
                    when (service.getAttributeNS(androidNs, "name").substringAfterLast('.')) {
                        "CaptureService" -> {
                            check(service.getAttributeNS(androidNs, "exported") == "false")
                            check(service.getAttributeNS(androidNs, "foregroundServiceType") == "mediaProjection")
                        }
                        "CaptureTile" -> check(service.getAttributeNS(androidNs, "permission") == "android.permission.BIND_QUICK_SETTINGS_TILE")
                        else -> error("Unexpected research service")
                    }
                }
                val activities = document.getElementsByTagName("activity")
                val exports = mutableSetOf<String>()
                val names = mutableSetOf<String>()
                for (index in 0 until activities.length) {
                    val activity = activities.item(index) as org.w3c.dom.Element
                    names += activity.getAttributeNS(androidNs, "name").substringAfterLast('.')
                    if (activity.getAttributeNS(androidNs, "exported") == "true")
                        exports += activity.getAttributeNS(androidNs, "name").substringAfterLast('.')
                }
                check(names == if (project.name == "probe") setOf("EntryActivity", "ConsentActivity", "ReviewActivity", "DropActivity")
                    else setOf("FixtureActivity")) { "Unexpected research activity" }
                check(exports == setOf(if (project.name == "probe") "EntryActivity" else "FixtureActivity"))
            }
        }
    }
}
