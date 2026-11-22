package dev.zeroinput.ime.textstego

import android.content.res.AssetManager
import dev.zeroinput.ime.core.stego.VerseTestBody
import dev.zeroinput.ime.core.stego.VerseTestSurface

/** Frozen inputs of the standalone public-fixture workbench. */
internal class VerseTestAssets(assets: AssetManager) {
    val surface: VerseTestSurface
    val body: VerseTestBody
    val digest: ByteArray

    init {
        val opening = readVerseAsset(assets, "opening.bin", VerseTestSurface.Form.FIVE.assetBytes)
        val map = readVerseAsset(assets, "gb-map.bin", 50_364)
        val dictionary = readVerseAsset(assets, "gb18030.zdict", 15_021)
        surface = VerseTestSurface(opening)
        body = VerseTestBody(map, dictionary)
        digest = profileFingerprint("v1", sha256(opening), sha256(map), sha256(dictionary))
    }
}
