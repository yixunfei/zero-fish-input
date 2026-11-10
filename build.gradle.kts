plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

fun systemClipboardViolation(path: String, text: String): String? {
    val prototype = "app/src/androidTest/kotlin/dev/zeroinput/ime/clipboard/SystemClipboardImportPrototypeTest.kt"
    if (path != prototype && Regex("\\b(?:getPrimaryClip|primaryClip)\\b").containsMatchIn(text)) {
        return "Reading system clipboard payloads is restricted to the isolated P1 public-fixture test"
    }
    val capabilities = Regex("\\bClipboardManager\\b|\\bCLIPBOARD_SERVICE\\b|\\b(?:add|remove)PrimaryClipChangedListener\\b|\\b(?:clear|set|has)PrimaryClip\\b|\\b(?:getPrimaryClipDescription|primaryClipDescription)\\b")
    if (!capabilities.containsMatchIn(text)) return null
    val adapter = "app/src/main/kotlin/dev/zeroinput/ime/clipboardguard/AndroidSystemClipboard.kt"
    val fixture = "app/src/androidTest/kotlin/dev/zeroinput/ime/clipboardguard/SystemClipboardPlatformTest.kt"
    if (path != adapter && path != fixture && path != prototype) return "System clipboard capabilities are restricted to the approved adapter and fixtures"
    val writes = Regex("\\bsetPrimaryClip\\s*\\(").findAll(text).count()
    val allowedWrite = if (path == adapter) {
        Regex("setPrimaryClip\\(ClipData\\.newPlainText\\(\"\", \"\"\\)\\)")
    } else if (path == prototype) {
        Regex("setPrimaryClip\\(publicFixture\\(sample\\)\\)|setPrimaryClip\\(ClipData\\.newPlainText\\(\"\", \"\"\\)\\)")
    } else {
        Regex("setPrimaryClip\\(ClipData\\.newPlainText\\(\"\", \"public guard fixture\"\\)\\)")
    }
    if (writes != allowedWrite.findAll(text).count()) return "Only literal empty cleanup or the fixed public test fixture may be written"
    return null
}

fun networkViolation(path: String, text: String): String? {
    // These TLS exception values contain no I/O capability; transports remain restricted in every source set.
    val network = Regex("""(?:java\.net\.(?:URL\b|Socket\b|Datagram|Http|http\b)|java\.nio\.channels\.SocketChannel|javax\.net\.(?!ssl\.SSL(?:Exception|HandshakeException)\b)|HttpURLConnection|HttpsURLConnection|openConnection\s*\(|android\.webkit|WebView|DownloadManager|okhttp|retrofit|ktor\.client|Cronet|Class\.forName\s*\(\s*[\"']java\.net\.)""")
    val provider = "app/src/main/kotlin/dev/zeroinput/ime/ai/OpenAiCompatibleProvider.kt"
    val dictionaries = "app/src/main/kotlin/dev/zeroinput/ime/dictionaries/DictionaryDownloadTransport.kt"
    val remote = "app/src/main/kotlin/dev/zeroinput/ime/ai/AiRemoteHttpsTransport.kt"
    if (network.containsMatchIn(text) && path != provider && path != dictionaries && path != remote) return "Runtime network transport is restricted to approved adapters"
    if (text.contains("android.permission.INTERNET") && path != "app/src/main/AndroidManifest.xml") {
        return "Only the approved app manifest may declare networking"
    }
    return null
}

fun pageAccessViolation(path: String, text: String): String? {
    val adapter = "app/src/main/kotlin/dev/zeroinput/ime/ai/page/PageReferenceService.kt"
    val deviceAdapter = "app/src/main/kotlin/dev/zeroinput/ime/ai/device/DeviceAgentAccessibilityService.kt"
    val fixtures = setOf(
        "KeyboardEditorIntegrationTest.kt", "KeyboardNavigationGestureTest.kt", "PinyinEditingInteractionTest.kt", "RgiEmojiTest.kt",
        "clipboard/ClipboardExternalImportTest.kt", "clipboard/ClipboardSelectionCopyTest.kt", "clipboard/SecurePasteReturnTest.kt",
        "clipboardguard/ClipboardDeviceTestSupport.kt", "clipboardguard/ClipboardGuardOverlayTest.kt",
        "clipboardguard/SystemClipboardPlatformTest.kt",
    ).map { "app/src/androidTest/kotlin/dev/zeroinput/ime/$it" }.toSet()
    val keyFiltering = Regex("""\b(?:onKeyEvent|canRequestFilterKeyEvents|requestFilterKeyEvents|FLAG_REQUEST_FILTER_KEY_EVENTS)\b""")
    if (keyFiltering.containsMatchIn(text)) return "Accessibility key filtering is forbidden"
    val capabilities = Regex("""android\.accessibilityservice\.AccessibilityService|android\.view\.accessibility\.AccessibilityNodeInfo|\brootInActiveWindow\b|\bgetRootInActiveWindow\b""")
    if (Regex("""android\.accessibilityservice\.AccessibilityService\b""").containsMatchIn(text) && path in fixtures) {
        return "Platform fixtures cannot introduce accessibility services"
    }
    if (capabilities.containsMatchIn(text) && path != adapter && path != deviceAdapter &&
        path !in fixtures && path != "app/src/main/AndroidManifest.xml") {
        return "External page reads are restricted to the approved one-shot adapter"
    }
    if (path == adapter && Regex("""\b(?:dispatchGesture|performGlobalAction|performAction|takeScreenshot)\b""").containsMatchIn(text)) {
        return "Page reference service cannot act on or capture the screen"
    }
    return null
}

fun assistantAccessViolation(path: String, text: String): String? {
    val paths = setOf("AiVoiceInteractionService.kt", "AiVoiceSessionService.kt", "AiNativeSession.kt", "AiDisabledRecognitionService.kt")
        .map { "app/src/main/kotlin/dev/zeroinput/ime/ai/$it" }.toSet()
    val capability = Regex("""android\.service\.voice\.(?:VoiceInteractionService|VoiceInteractionSession|VoiceInteractionSessionService)|android\.speech\.RecognitionService""")
    if (capability.containsMatchIn(text) && path !in paths && path != "app/src/main/AndroidManifest.xml") return "Native assistant binding is restricted to exact adapters"
    if (path in paths && Regex("""\b(?:createAlwaysOnHotwordDetector|createHotwordDetector|requestDirectActions|performDirectAction|startVoiceActivity)\s*\(""").containsMatchIn(text))
        return "Native assistant cannot acquire hotword or direct-action authority"
    return null
}

fun virtualScreenViolation(path: String, text: String): String? {
    val adapter = "app/src/main/kotlin/dev/zeroinput/ime/ai/AiVirtualScreen.kt"
    if (Regex("""\bcreateVirtualDisplay\s*\(""").containsMatchIn(text) && path != adapter)
        return "Virtual display creation is restricted to the public-fixture adapter"
    if (path == adapter && Regex("""\b(?:VIRTUAL_DISPLAY_FLAG_(?:PUBLIC|AUTO_MIRROR|SECURE|TRUSTED)|startActivity|setLaunchDisplayId|injectInputEvent|MediaProjection)\b""").containsMatchIn(text))
        return "Virtual preview cannot mirror, launch, inject input or request protected display privileges"
    return null
}

val androidNamespace = "http://schemas.android.com/apk/res/android"

fun readXml(file: File): org.w3c.dom.Document = file.inputStream().use {
    javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(it)
}

fun manifestBoundaryViolations(document: org.w3c.dom.Document, release: Boolean): List<String> {
    val errors = mutableListOf<String>()
    val application = document.getElementsByTagName("application").item(0) as? org.w3c.dom.Element
        ?: return listOf("Missing application")
    fun attribute(element: org.w3c.dom.Element, name: String) = element.getAttributeNS(androidNamespace, name)
    if (attribute(application, "allowBackup") != "false") errors += "Backup must be disabled"
    if (release && attribute(application, "debuggable") !in setOf("", "false")) errors += "Release must not be debuggable"
    if (attribute(application, "fullBackupContent") != "@xml/backup_rules" ||
        attribute(application, "dataExtractionRules") != "@xml/data_extraction_rules") errors += "Unexpected backup rules"
    val approved = mapOf(
        "dev.zeroinput.ime.settings.MainActivity" to ("activity" to ""),
        "dev.zeroinput.ime.ai.AiAssistantLauncherActivity" to ("activity" to ""),
        "dev.zeroinput.ime.clipboard.ClipboardImportActivity" to ("activity" to ""),
        "dev.zeroinput.ime.ZeroInputService" to ("service" to "android.permission.BIND_INPUT_METHOD"),
        "dev.zeroinput.ime.ai.page.PageReferenceService" to ("service" to "android.permission.BIND_ACCESSIBILITY_SERVICE"),
        "dev.zeroinput.ime.ai.device.DeviceAgentAccessibilityService" to ("service" to "android.permission.BIND_ACCESSIBILITY_SERVICE"),
        "dev.zeroinput.ime.ai.device.DeviceStage6NotificationListenerService" to ("service" to "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
        "dev.zeroinput.ime.ai.AiVoiceInteractionService" to ("service" to "android.permission.BIND_VOICE_INTERACTION"),
        "dev.zeroinput.ime.ai.AiVoiceSessionService" to ("service" to "android.permission.BIND_VOICE_INTERACTION"),
    )
    val found = mutableSetOf<String>()
    for (tag in listOf("activity", "activity-alias", "service", "receiver", "provider")) {
        val components = application.getElementsByTagName(tag)
        for (index in 0 until components.length) {
            val component = components.item(index) as org.w3c.dom.Element
            val rawName = attribute(component, "name")
            val packageName = document.documentElement.getAttribute("package").ifEmpty { "dev.zeroinput.ime" }
            val name = when {
                rawName.startsWith(".") -> packageName + rawName
                "." !in rawName -> "$packageName.$rawName"
                else -> rawName
            }
            val exported = attribute(component, "exported")
            val implicit = exported.isEmpty() && (component.getElementsByTagName("intent-filter").length > 0 || tag == "provider")
            if (exported != "true" && !implicit) continue
            val expected = approved[name]
            if (implicit || expected == null || expected.first != tag || expected.second != attribute(component, "permission")) {
                errors += "Unexpected exported component: $name"
            } else if (!found.add(name)) errors += "Duplicate exported component: $name"
        }
    }
    if (found != approved.keys) errors += "Missing approved exported components"
    if (!validStandaloneAiEntry(document)) errors += "Invalid standalone AI entry boundary"
    if (!validAiTaskComponents(document)) errors += "Invalid task/native assistant component boundary"
    return errors
}

fun validAiTaskComponents(document: org.w3c.dom.Document): Boolean {
    fun attribute(node: org.w3c.dom.Element, name: String) = node.getAttributeNS(androidNamespace, name)
    fun component(tag: String, name: String): org.w3c.dom.Element? {
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
            .singleOrNull { attribute(it, "name") in setOf(".ai.$name", "dev.zeroinput.ime.ai.$name") }
    }
    val foreground = component("service", "AiTaskForegroundService") ?: return false
    if (attribute(foreground, "exported") != "false" || attribute(foreground, "foregroundServiceType") != "shortService" ||
        attribute(foreground, "stopWithTask") != "true" || foreground.getElementsByTagName("intent-filter").length != 0) return false
    val stop = component("receiver", "AiTaskStopReceiver") ?: return false
    if (attribute(stop, "exported") != "false" || stop.getElementsByTagName("intent-filter").length != 0) return false
    val voice = component("service", "AiVoiceInteractionService") ?: return false
    val filters = voice.getElementsByTagName("intent-filter")
    if (filters.length != 1) return false
    val filter = filters.item(0) as org.w3c.dom.Element
    val actions = filter.getElementsByTagName("action")
    if (actions.length != 1 || attribute(actions.item(0) as org.w3c.dom.Element, "name") != "android.service.voice.VoiceInteractionService" ||
        filter.getElementsByTagName("data").length != 0 || filter.getElementsByTagName("category").length != 0) return false
    val metadata = voice.getElementsByTagName("meta-data")
    if (metadata.length != 1) return false
    val meta = metadata.item(0) as org.w3c.dom.Element
    if (attribute(meta, "name") != "android.voice_interaction" || attribute(meta, "resource") != "@xml/ai_voice_interaction") return false
    val session = component("service", "AiVoiceSessionService") ?: return false
    if (session.getElementsByTagName("intent-filter").length != 0) return false
    val recognition = component("service", "AiDisabledRecognitionService") ?: return false
    return attribute(recognition, "exported") == "false" && attribute(recognition, "permission") == "android.permission.BIND_VOICE_INTERACTION"
}

fun validAiVoiceConfig(document: org.w3c.dom.Document): Boolean {
    val root = document.documentElement
    val actual = (0 until root.attributes.length).map { root.attributes.item(it) }
        .filter { it.namespaceURI == androidNamespace }.associate { it.localName to it.nodeValue }
    return root.tagName == "voice-interaction-service" && actual == mapOf(
        "sessionService" to "dev.zeroinput.ime.ai.AiVoiceSessionService",
        "recognitionService" to "dev.zeroinput.ime.ai.AiDisabledRecognitionService",
        "supportsAssist" to "true", "supportsLaunchVoiceAssistFromKeyguard" to "false", "supportsLocalInteraction" to "false")
}

fun validStandaloneAiEntry(document: org.w3c.dom.Document): Boolean {
    fun attribute(element: org.w3c.dom.Element, name: String) = element.getAttributeNS(androidNamespace, name)
    val activities = document.getElementsByTagName("activity")
    fun activity(suffix: String): org.w3c.dom.Element? = (0 until activities.length)
        .map { activities.item(it) as org.w3c.dom.Element }.singleOrNull { attribute(it, "name").endsWith(".ai.$suffix") }
    val gateway = activity("AiAssistantLauncherActivity") ?: return false
    val screen = activity("AiAssistantActivity") ?: return false
    if (attribute(screen, "exported") != "false" || attribute(screen, "excludeFromRecents") != "true" ||
        screen.getElementsByTagName("intent-filter").length != 0 ||
        attribute(gateway, "excludeFromRecents") != "true") return false
    val filters = gateway.getElementsByTagName("intent-filter")
    if (filters.length != 1) return false
    val filter = filters.item(0) as org.w3c.dom.Element
    fun singleName(tag: String, expected: String): Boolean {
        val children = filter.getElementsByTagName(tag)
        return children.length == 1 && attribute(children.item(0) as org.w3c.dom.Element, "name") == expected
    }
    return attribute(filter, "priority") == "-1" && singleName("action", "android.intent.action.MAIN") &&
        singleName("category", "android.intent.category.LAUNCHER") && filter.getElementsByTagName("data").length == 0
}

fun validBackupRules(document: org.w3c.dom.Document, extraction: Boolean): Boolean {
    val root = document.documentElement
    val domains = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
    fun excluded(element: org.w3c.dom.Element): Boolean {
        val exclusions = element.getElementsByTagName("exclude")
        val actual = mutableSetOf<String>()
        for (index in 0 until exclusions.length) {
            val item = exclusions.item(index) as org.w3c.dom.Element
            if (item.getAttribute("path") != ".") return false
            actual += item.getAttribute("domain")
        }
        return actual == domains && exclusions.length == domains.size && element.getElementsByTagName("include").length == 0
    }
    if (!extraction) return root.tagName == "full-backup-content" && excluded(root)
    if (root.tagName != "data-extraction-rules") return false
    return listOf("cloud-backup", "device-transfer").all { tag ->
        val nodes = root.getElementsByTagName(tag)
        nodes.length == 1 && excluded(nodes.item(0) as org.w3c.dom.Element)
    }
}

fun validPageReferenceConfig(document: org.w3c.dom.Document, modern: Boolean): Boolean {
    val root = document.documentElement
    val expected = mutableMapOf(
        "description" to "@string/ai_page_access_description",
        "accessibilityEventTypes" to "typeWindowStateChanged|typeWindowsChanged",
        "accessibilityFeedbackType" to "feedbackGeneric",
        "accessibilityFlags" to "flagRetrieveInteractiveWindows",
        "canRetrieveWindowContent" to "true",
        "notificationTimeout" to "0",
    )
    if (modern) expected["isAccessibilityTool"] = "false"
    val actual = mutableMapOf<String, String>()
    for (index in 0 until root.attributes.length) {
        val attribute = root.attributes.item(index)
        if (attribute.namespaceURI == "http://schemas.android.com/apk/res/android") {
            actual[attribute.localName] = attribute.nodeValue
        }
    }
    return root.tagName == "accessibility-service" && actual == expected
}

fun validDeviceAgentConfig(document: org.w3c.dom.Document, modern: Boolean): Boolean {
    val root = document.documentElement
    val expected = mutableMapOf(
        "description" to "@string/ai_device_agent_service_description",
        "accessibilityEventTypes" to "typeWindowStateChanged|typeWindowsChanged|typeViewFocused|typeWindowContentChanged|typeViewScrolled|typeViewTextChanged",
        "accessibilityFeedbackType" to "feedbackGeneric",
        "accessibilityFlags" to "flagRetrieveInteractiveWindows|flagReportViewIds",
        "canRetrieveWindowContent" to "true",
        "canPerformGestures" to "true",
        "notificationTimeout" to "0",
    )
    if (modern) {
        expected["isAccessibilityTool"] = "false"
        expected["canTakeScreenshot"] = "true"
    }
    val actual = mutableMapOf<String, String>()
    for (index in 0 until root.attributes.length) {
        val attribute = root.attributes.item(index)
        if (attribute.namespaceURI == androidNamespace) actual[attribute.localName] = attribute.nodeValue
    }
    return root.tagName == "accessibility-service" && actual == expected
}

fun approvedPrivacyResource(path: String): Boolean {
    val name = path.substringAfterLast('/')
    return path == "app/src/main/res/xml/$name" ||
        name in setOf("page_reference_service.xml", "device_agent_service.xml") &&
        path in setOf("app/src/main/res/xml-v30/$name", "app/src/main/res/xml-v31/$name")
}

fun testManifestAndBackupBoundaries(root: File) {
    val source = File(root, "app/src/main/AndroidManifest.xml")
    check(manifestBoundaryViolations(readXml(source), true).isEmpty())
    for ((suffix, key, value) in listOf(Triple("AiTaskForegroundService", "foregroundServiceType", "dataSync"),
        Triple("AiTaskForegroundService", "stopWithTask", "false"), Triple("AiDisabledRecognitionService", "exported", "true"))) {
        val mutated = readXml(source)
        val services = mutated.getElementsByTagName("service")
        val service = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(androidNamespace, "name").endsWith(".$suffix") }
        service.setAttributeNS(androidNamespace, "android:$key", value)
        check(manifestBoundaryViolations(mutated, false).isNotEmpty())
    }
    val voiceConfig = File(root, "app/src/main/res/xml/ai_voice_interaction.xml")
    check(validAiVoiceConfig(readXml(voiceConfig)))
    for (key in listOf("supportsLaunchVoiceAssistFromKeyguard", "supportsLocalInteraction", "usesAssistScreenshots")) {
        val mutated = readXml(voiceConfig); mutated.documentElement.setAttributeNS(androidNamespace, "android:$key", "true")
        check(!validAiVoiceConfig(mutated))
    }
    check(!approvedPrivacyResource("app/src/main/res/xml-v37/ai_voice_interaction.xml"))
    for (mutation in listOf("action", "data", "priority", "exported", "recents")) {
        val document = readXml(source)
        val activities = document.getElementsByTagName("activity")
        val screen = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(androidNamespace, "name").endsWith(
                if (mutation in setOf("action", "data", "priority")) ".ai.AiAssistantLauncherActivity" else ".ai.AiAssistantActivity") }
        when (mutation) {
            "action" -> (screen.getElementsByTagName("action").item(0) as org.w3c.dom.Element)
                .setAttributeNS(androidNamespace, "android:name", "android.intent.action.SEND")
            "data" -> screen.getElementsByTagName("intent-filter").item(0).appendChild(document.createElement("data"))
            "priority" -> (screen.getElementsByTagName("intent-filter").item(0) as org.w3c.dom.Element)
                .setAttributeNS(androidNamespace, "android:priority", "0")
            "exported" -> screen.setAttributeNS(androidNamespace, "android:exported", "true")
            else -> screen.setAttributeNS(androidNamespace, "android:excludeFromRecents", "false")
        }
        check(manifestBoundaryViolations(document, false).isNotEmpty())
    }
    for ((attribute, value) in listOf("allowBackup" to "true", "debuggable" to "true", "fullBackupContent" to "@xml/other")) {
        val document = readXml(source)
        (document.getElementsByTagName("application").item(0) as org.w3c.dom.Element)
            .setAttributeNS(androidNamespace, "android:$attribute", value)
        check(manifestBoundaryViolations(document, true).isNotEmpty())
    }
    for (tag in listOf("activity", "activity-alias", "service", "provider", "receiver")) {
        val document = readXml(source)
        val component = document.createElement(tag)
        component.setAttributeNS(androidNamespace, "android:name", "unapproved.Component")
        component.setAttributeNS(androidNamespace, "android:exported", "true")
        document.getElementsByTagName("application").item(0).appendChild(component)
        check(manifestBoundaryViolations(document, false).isNotEmpty())
        component.removeAttributeNS(androidNamespace, "exported")
        component.appendChild(document.createElement("intent-filter"))
        check(manifestBoundaryViolations(document, false).isNotEmpty())
    }
    val serviceDocument = readXml(source)
    val services = serviceDocument.getElementsByTagName("service")
    for (index in 0 until services.length) {
        val service = services.item(index) as org.w3c.dom.Element
        if (service.getAttributeNS(androidNamespace, "exported") == "true") {
            service.setAttributeNS(androidNamespace, "android:permission", "unapproved.PERMISSION")
        }
    }
    check(manifestBoundaryViolations(serviceDocument, false).isNotEmpty())
    val config = readXml(File(root, "app/src/main/res/xml/page_reference_service.xml"))
    check(validPageReferenceConfig(config, false))
    config.documentElement.setAttributeNS(androidNamespace, "android:canRequestFilterKeyEvents", "true")
    check(!validPageReferenceConfig(config, false))
    val deviceConfig = readXml(File(root, "app/src/main/res/xml/device_agent_service.xml"))
    check(validDeviceAgentConfig(deviceConfig, false))
    deviceConfig.documentElement.setAttributeNS(androidNamespace, "android:canRequestFilterKeyEvents", "true")
    check(!validDeviceAgentConfig(deviceConfig, false))
    val modernDeviceConfig = readXml(File(root, "app/src/main/res/xml-v31/device_agent_service.xml"))
    check(validDeviceAgentConfig(modernDeviceConfig, true))
    for (path in listOf("app/src/main/res/xml-v33/page_reference_service.xml",
        "app/src/debug/res/xml/page_reference_service.xml", "app/src/main/res/xml-v31/backup_rules.xml")) {
        check(!approvedPrivacyResource(path))
    }
    for (extraction in listOf(false, true)) {
        val name = if (extraction) "data_extraction_rules" else "backup_rules"
        val document = readXml(File(root, "app/src/main/res/xml/$name.xml"))
        check(validBackupRules(document, extraction))
        val exclude = document.getElementsByTagName("exclude").item(0)
        exclude.parentNode.removeChild(exclude)
        check(!validBackupRules(document, extraction))
    }
}

tasks.register("testPrivacyBoundary") {
    group = "verification"
    description = "Checks that clipboard payload reads stay in the isolated public prototype and rejects arbitrary writes."
    doLast {
        val adapter = "app/src/main/kotlin/dev/zeroinput/ime/clipboardguard/AndroidSystemClipboard.kt"
        val fixture = "app/src/androidTest/kotlin/dev/zeroinput/ime/clipboardguard/SystemClipboardPlatformTest.kt"
        val business = "app/src/main/kotlin/dev/zeroinput/ime/ZeroInputService.kt"
        val native = "app/src/main/kotlin/dev/zeroinput/ime/ai/AiNativeSession.kt"
        check(assistantAccessViolation(native, "android.service.voice.VoiceInteractionSession") == null)
        check(assistantAccessViolation(business, "android.service.voice.VoiceInteractionSession") != null)
        check(assistantAccessViolation("other/$native", "android.service.voice.VoiceInteractionSession") != null)
        check(assistantAccessViolation(native, "requestDirectActions(value)") != null)
        val virtual = "app/src/main/kotlin/dev/zeroinput/ime/ai/AiVirtualScreen.kt"
        check(virtualScreenViolation(virtual, "createVirtualDisplay(value)") == null)
        check(virtualScreenViolation(business, "createVirtualDisplay(value)") != null)
        check(virtualScreenViolation("other/$virtual", "createVirtualDisplay(value)") != null)
        for (forbidden in listOf("VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR", "VIRTUAL_DISPLAY_FLAG_PUBLIC", "startActivity", "injectInputEvent"))
            check(virtualScreenViolation(virtual, forbidden) != null)
        val prototype = "app/src/androidTest/kotlin/dev/zeroinput/ime/clipboard/SystemClipboardImportPrototypeTest.kt"
        val reads = listOf("manager.getPrimaryClip()", "manager.primaryClip", "manager . primaryClip",
            "with(manager) { primaryClip }", "manager\n    . primaryClip")
        for (path in listOf(adapter, fixture, business)) for (read in reads) {
            check(systemClipboardViolation(path, read) != null)
        }
        for (read in reads) {
            check(systemClipboardViolation(prototype, read) == null)
            for (path in listOf("other/$prototype", prototype.replace("androidTest", "main"),
                prototype.replace("PrototypeTest", "OtherTest"),
                "app/src/main/kotlin/dev/zeroinput/ime/ai/Probe.kt")) {
                check(systemClipboardViolation(path, read) != null)
            }
        }
        check(systemClipboardViolation(prototype, "manager.setPrimaryClip(publicFixture(sample))") == null)
        check(systemClipboardViolation(prototype, "manager.setPrimaryClip(value)") != null)
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
            val dictionaries = "app/src/main/kotlin/dev/zeroinput/ime/dictionaries/DictionaryDownloadTransport.kt"
            check(networkViolation(dictionaries, source) == null)
            check(networkViolation("other/$dictionaries", source) != null)
            val remote = "app/src/main/kotlin/dev/zeroinput/ime/ai/AiRemoteHttpsTransport.kt"
            check(networkViolation(remote, source) == null)
            check(networkViolation("other/$remote", source) != null)
            check(networkViolation("app/src/main/kotlin/dev/zeroinput/ime/ai/AiMcpClient.kt", source) != null)
        }
        check(networkViolation(business, "java.net.URI") == null)
        for (path in listOf(business, "app/src/test/kotlin/TransportTest.kt")) {
            check(networkViolation(path, "javax.net.ssl.SSLHandshakeException") == null)
            check(networkViolation(path, "javax.net.ssl.SSLException") == null)
            check(networkViolation(path, "javax.net.ssl.SSLSocketFactory") != null)
            check(networkViolation(path, "javax.net.ssl.SSLContext") != null)
            check(networkViolation(path, "java.net.URL") != null)
        }
        check(networkViolation(business, "android.permission.INTERNET") != null)
        val page = "app/src/main/kotlin/dev/zeroinput/ime/ai/page/PageReferenceService.kt"
        for (capability in listOf("android.accessibilityservice.AccessibilityService", "rootInActiveWindow",
            "android.view.accessibility.AccessibilityNodeInfo")) {
            check(pageAccessViolation(business, capability) != null)
            check(pageAccessViolation("other/$page", capability) != null)
            check(pageAccessViolation(page, capability) == null)
        }
        for (action in listOf("performAction(1)", "dispatchGesture(value)", "takeScreenshot(0)", "performGlobalAction(1)",
            "service::performAction", "service::dispatchGesture", "service::takeScreenshot", "service::performGlobalAction")) {
            check(pageAccessViolation(page, action) != null)
        }
        for (path in listOf(page, "app/src/debug/kotlin/Reader.kt", "app/src/androidTest/kotlin/Reader.kt")) {
            for (key in listOf("onKeyEvent", "canRequestFilterKeyEvents", "requestFilterKeyEvents", "FLAG_REQUEST_FILTER_KEY_EVENTS")) {
                check(pageAccessViolation(path, key) != null)
            }
        }
        check(pageAccessViolation("app/src/debug/kotlin/Reader.kt", "rootInActiveWindow") != null)
        testManifestAndBackupBoundaries(rootProject.projectDir)
    }
}

fun Project.scanPrivacySources(violations: MutableList<String>) {
    val sourceRoots = subprojects.map { it.file("src") }

    sourceRoots.filter(File::exists).forEach { root ->
        root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .forEach { file ->
                val text = file.readText()
                val path = file.relativeTo(rootProject.projectDir).invariantSeparatorsPath
                systemClipboardViolation(path, text)?.let { violations += "$path: $it" }
                networkViolation(path, text)?.let { violations += "$path: $it" }
                pageAccessViolation(path, text)?.let { violations += "$path: $it" }
                assistantAccessViolation(path, text)?.let { violations += "$path: $it" }
                virtualScreenViolation(path, text)?.let { violations += "$path: $it" }
            }
    }
}

fun Project.checkMergedPrivacyManifests(violations: MutableList<String>) {
    val androidNamespace = "http://schemas.android.com/apk/res/android"
    listOf("debug", "release").forEach { variant ->
        val manifestRoot = project(":app").layout.buildDirectory.dir("intermediates/merged_manifest/$variant").get().asFile
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
            violations += manifestBoundaryViolations(document, variant == "release")
            val packageName = document.documentElement.getAttribute("package")
            val services = document.getElementsByTagName("service")
            val pageName = "dev.zeroinput.ime.ai.page.PageReferenceService"
            val deviceName = "dev.zeroinput.ime.ai.device.DeviceAgentAccessibilityService"
            val notificationName = "dev.zeroinput.ime.ai.device.DeviceStage6NotificationListenerService"
            var pageServices = 0
            var deviceServices = 0
            var notificationServices = 0
            for (index in 0 until services.length) {
                val service = services.item(index) as org.w3c.dom.Element
                val name = service.getAttributeNS(androidNamespace, "name")
                val permission = service.getAttributeNS(androidNamespace, "permission")
                if (permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" && name != pageName && name != deviceName) {
                    violations += "Unexpected accessibility service"
                }
                if (name == pageName) {
                    pageServices++
                    if (permission != "android.permission.BIND_ACCESSIBILITY_SERVICE" ||
                        service.getAttributeNS(androidNamespace, "exported") != "true") {
                        violations += "Page service must be system-bound and explicitly exported"
                    }
                }
                if (name == deviceName) {
                    deviceServices++
                    if (permission != "android.permission.BIND_ACCESSIBILITY_SERVICE" ||
                        service.getAttributeNS(androidNamespace, "exported") != "true") {
                        violations += "Device agent service must be system-bound and explicitly exported"
                    }
                }
                if (name == notificationName) {
                    notificationServices++
                    if (permission != "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" ||
                        service.getAttributeNS(androidNamespace, "exported") != "true") {
                        violations += "Notification access must be explicitly system-bound"
                    }
                }
            }
            if (pageServices != 1) violations += "Missing or duplicate page reference service"
            if (deviceServices != 1) violations += "Missing or duplicate device agent service"
            if (notificationServices != 1) violations += "Missing or duplicate notification listener service"
            val allowedPermissions = setOf(
                "android.permission.USE_BIOMETRIC",
                "android.permission.USE_FINGERPRINT",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.INTERNET",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.WRITE_SETTINGS",
                "android.permission.READ_SMS",
                "android.permission.SEND_SMS",
                "android.permission.READ_CALL_LOG",
                "android.permission.CALL_PHONE",
                "android.permission.READ_CONTACTS",
                "android.permission.WRITE_CONTACTS",
                "android.permission.READ_CALENDAR",
                "android.permission.WRITE_CALENDAR",
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
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
}

fun Project.checkPrivacyResources(violations: MutableList<String>) {
    if (!validAiVoiceConfig(readXml(rootProject.file("app/src/main/res/xml/ai_voice_interaction.xml")))) violations += "Unexpected assistant metadata"
    for (variant in listOf("xml", "xml-v31")) {
        if (!validPageReferenceConfig(readXml(rootProject.file("app/src/main/res/$variant/page_reference_service.xml")),
                modern = variant == "xml-v31")) violations += "Unexpected page reference capability"
        if (!validDeviceAgentConfig(readXml(rootProject.file("app/src/main/res/$variant/device_agent_service.xml")),
                modern = variant == "xml-v31")) violations += "Unexpected device agent capability"
    }
    rootProject.file("app/src").walkTopDown().filter { it.isFile && it.name in
        setOf("page_reference_service.xml", "device_agent_service.xml", "ai_voice_interaction.xml", "backup_rules.xml", "data_extraction_rules.xml") }.forEach { file ->
        val path = file.relativeTo(rootProject.projectDir).invariantSeparatorsPath
        val page = file.name == "page_reference_service.xml"
        val device = file.name == "device_agent_service.xml"
        if (!approvedPrivacyResource(path)) violations += "Unapproved capability/backup resource override: $path"
        else if (!page && !device && file.name != "ai_voice_interaction.xml" && !validBackupRules(readXml(file), file.name == "data_extraction_rules.xml")) {
            violations += "Incomplete backup exclusions: $path"
        }
    }
}

tasks.register("privacyCheck") {
    group = "verification"
    description = "Rejects unexpected permissions, clipboard payload reads and capabilities outside the approved opt-in guard."
    dependsOn("testPrivacyBoundary", ":app:processDebugMainManifest", ":app:processReleaseMainManifest")

    doLast {
        val violations = mutableListOf<String>()
        scanPrivacySources(violations)
        checkMergedPrivacyManifests(violations)
        checkPrivacyResources(violations)
        check(violations.isEmpty()) { violations.joinToString("\n") }
    }
}
