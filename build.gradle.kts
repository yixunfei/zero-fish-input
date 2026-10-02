plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

fun systemClipboardViolation(path: String, text: String): String? {
    if (Regex("\\bgetPrimaryClip\\b|\\.primaryClip\\b").containsMatchIn(text)) {
        return "Reading system clipboard payloads is forbidden everywhere"
    }
    val capabilities = Regex("\\bClipboardManager\\b|\\bCLIPBOARD_SERVICE\\b|\\b(?:add|remove)PrimaryClipChangedListener\\b|\\b(?:clear|set|has)PrimaryClip\\b|\\b(?:getPrimaryClipDescription|primaryClipDescription)\\b")
    if (!capabilities.containsMatchIn(text)) return null
    val adapter = "app/src/main/kotlin/dev/zeroinput/ime/clipboardguard/AndroidSystemClipboard.kt"
    val fixture = "app/src/androidTest/kotlin/dev/zeroinput/ime/clipboardguard/SystemClipboardPlatformTest.kt"
    if (path != adapter && path != fixture) return "System clipboard capabilities are restricted to the approved adapter and fixture"
    val writes = Regex("\\bsetPrimaryClip\\s*\\(").findAll(text).count()
    val allowedWrite = if (path == adapter) {
        Regex("setPrimaryClip\\(ClipData\\.newPlainText\\(\"\", \"\"\\)\\)")
    } else {
        Regex("setPrimaryClip\\(ClipData\\.newPlainText\\(\"\", \"public guard fixture\"\\)\\)")
    }
    if (writes != allowedWrite.findAll(text).count()) return "Only literal empty cleanup or the fixed public test fixture may be written"
    return null
}

fun networkViolation(path: String, text: String): String? {
    val network = Regex("""(?:java\.net\.(?:URL\b|Socket\b|Datagram|Http|http\b)|java\.nio\.channels\.SocketChannel|javax\.net\.|HttpURLConnection|HttpsURLConnection|openConnection\s*\(|android\.webkit|WebView|DownloadManager|okhttp|retrofit|ktor\.client|Cronet|Class\.forName\s*\(\s*[\"']java\.net\.)""")
    val provider = "app/src/main/kotlin/dev/zeroinput/ime/ai/OpenAiCompatibleProvider.kt"
    if (network.containsMatchIn(text) && path != provider) return "Runtime network transport is restricted to the AI provider"
    if (text.contains("android.permission.INTERNET") && path != "app/src/main/AndroidManifest.xml") {
        return "Only the approved app manifest may declare AI networking"
    }
    return null
}

tasks.register("testPrivacyBoundary") {
    group = "verification"
    description = "Checks that clipboard source exceptions cannot allow payload reads or arbitrary writes."
    doLast {
        val adapter = "app/src/main/kotlin/dev/zeroinput/ime/clipboardguard/AndroidSystemClipboard.kt"
        val fixture = "app/src/androidTest/kotlin/dev/zeroinput/ime/clipboardguard/SystemClipboardPlatformTest.kt"
        val business = "app/src/main/kotlin/dev/zeroinput/ime/ZeroInputService.kt"
        val reads = listOf("manager.getPrimaryClip()", "manager.primaryClip")
        for (path in listOf(adapter, fixture, business)) for (read in reads) {
            check(systemClipboardViolation(path, read) != null)
        }
        for (capability in listOf("ClipboardManager", "CLIPBOARD_SERVICE", "manager.primaryClipDescription",
            "manager.hasPrimaryClip()", "manager.addPrimaryClipChangedListener(listener)", "manager.clearPrimaryClip()")) {
            check(systemClipboardViolation(business, capability) != null)
        }
        val empty = "manager.setPrimaryClip(ClipData.newPlainText(\"\", \"\"))"
        val publicFixture = "manager.setPrimaryClip(ClipData.newPlainText(\"\", \"public guard fixture\"))"
        check(systemClipboardViolation(adapter, empty) == null)
        check(systemClipboardViolation(fixture, publicFixture) == null)
        check(systemClipboardViolation(adapter, publicFixture) != null)
        check(systemClipboardViolation(business, empty) != null)
        check(systemClipboardViolation("other/$adapter", empty) != null)
        check(systemClipboardViolation(adapter, "$empty\nmanager.setPrimaryClip(value)") != null)
        val provider = "app/src/main/kotlin/dev/zeroinput/ime/ai/OpenAiCompatibleProvider.kt"
        for (source in listOf("java.net.URL", "javax.net.ssl.HttpsURLConnection", "url.openConnection()",
            "java.net.Socket", "okhttp3.OkHttpClient", "android.webkit.WebView")) {
            check(networkViolation(business, source) != null)
            check(networkViolation("other/$provider", source) != null)
            check(networkViolation(provider, source) == null)
        }
        check(networkViolation(business, "java.net.URI") == null)
        check(networkViolation(business, "android.permission.INTERNET") != null)
    }
}

tasks.register("privacyCheck") {
    group = "verification"
    description = "Rejects unexpected permissions, clipboard payload reads and capabilities outside the approved opt-in guard."
    dependsOn("testPrivacyBoundary", ":app:processDebugMainManifest", ":app:processReleaseMainManifest")

    doLast {
        val sourceRoots = subprojects.map { it.file("src") }
        val violations = mutableListOf<String>()

        sourceRoots.filter(File::exists).forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
                .forEach { file ->
                    val text = file.readText()
                    val path = file.relativeTo(rootProject.projectDir).invariantSeparatorsPath
                    systemClipboardViolation(path, text)?.let { violations += "$path: $it" }
                    networkViolation(path, text)?.let { violations += "$path: $it" }
                }
        }

        val androidNamespace = "http://schemas.android.com/apk/res/android"
        listOf("debug", "release").forEach { variant ->
            val manifestRoot = rootProject.file("app/build/intermediates/merged_manifest/$variant")
            val manifests = manifestRoot.walkTopDown()
                .filter { it.isFile && it.name == "AndroidManifest.xml" }
                .toList()
            if (manifests.isEmpty()) {
                violations += "Missing merged $variant manifest; packaged permissions were not verified"
            }
            manifests.forEach { manifest ->
                val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    isExpandEntityReferences = false
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                val document = manifest.inputStream().use { factory.newDocumentBuilder().parse(it) }
                val packageName = document.documentElement.getAttribute("package")
                val allowedPermissions = setOf(
                    "android.permission.USE_BIOMETRIC",
                    "android.permission.USE_FINGERPRINT",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.SYSTEM_ALERT_WINDOW",
                    "android.permission.INTERNET",
                    "$packageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                )
                val permissionNames = buildSet {
                    val elements = document.getElementsByTagName("*")
                    for (index in 0 until elements.length) {
                        val element = elements.item(index) as? org.w3c.dom.Element ?: continue
                        if (!element.tagName.startsWith("uses-permission")) continue
                        element.getAttributeNS(androidNamespace, "name")
                            .takeIf(String::isNotBlank)
                            ?.let(::add)
                    }
                }
                val unexpected = permissionNames - allowedPermissions
                if (unexpected.isNotEmpty()) {
                    violations += "${manifest.relativeTo(rootProject.projectDir)}: " +
                        "Unexpected packaged permissions: ${unexpected.sorted().joinToString()}"
                }
            }
        }
        check(violations.isEmpty()) { violations.joinToString("\n") }
    }
}
