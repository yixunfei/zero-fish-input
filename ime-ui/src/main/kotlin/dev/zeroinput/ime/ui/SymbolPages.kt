package dev.zeroinput.ime.ui

/** Curated, visible Unicode symbols; no controls, private-use glyphs or dynamic data. */
internal object SymbolPages {
    data class Group(val label: Int, val symbols: List<String>)
    data class Page(val group: Int, val number: Int, val count: Int, val symbols: List<String>)
    val groups = listOf(
        Group(R.string.symbol_group_english, "1234567890@#¥_&-+()/*\"':;!?[]{}<>|\\~^%=€£$¢©®°…‰•§¶¤¬¦¡¿«»‹›†‡‖‗‛‟‥‧′″‴‵‶‷※⁂⁎⁑⁕".map(Char::toString)),
        Group(R.string.symbol_group_chinese, "，。！？；：、·—…（）【】《》〈〉「」『』〔〕［］｛｝“”‘’﹃﹄﹁﹂﹏﹋﹌～々〃〆〇〒〓〝〞﹐﹑﹒﹔﹕﹖﹗﹙﹚﹛﹜﹝﹞".map(Char::toString)),
        Group(R.string.symbol_group_math, "±×÷−∓≠≈≡≤≥∞√∛∜∑∏∐∫∬∭∮∯∰∂∆∇∈∉∋∌⊂⊃⊆⊇⊄⊅⊈⊉∪∩∅∧∨¬∀∃∄∴∵∝∟∠∡∢⊥∥∦∶∷∽≅≃≄≆≇≉≊≋≍≎≏≐≑≒≓≔≕≖≗≙≚≜≝≟≪≫≮≯≰≱≲≳⊕⊖⊗⊘⊙⊚⊛⊜⊝⊞⊟⊠⊡⊢⊣⊤⊨⊩⊪⊫⋀⋁⋂⋃⋄⋅⋆⋈⋮⋯⋰⋱".map(Char::toString)),
        Group(R.string.symbol_group_greek, "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩαβγδεζηθικλμνξοπρστυφχψωϐϑϕϖϱςϰϵϜϝϞϟϠϡ".map(Char::toString)),
        Group(R.string.symbol_group_arrows, "←↑→↓↔↕↖↗↘↙↚↛↜↝↞↟↠↡↢↣↤↥↦↧↩↪↫↬↭↮↯↰↱↲↳↴↵↶↷↸↹↺↻↼↽↾↿⇀⇁⇂⇃⇄⇅⇆⇇⇈⇉⇊⇋⇌⇍⇎⇏⇐⇑⇒⇓⇔⇕⇖⇗⇘⇙⇚⇛⇜⇝⇠⇡⇢⇣⇤⇥⇦⇧⇨⇩⇪➔➜➝➞➟➠➡➢➣➤➥➦➧➨➩➪➫➬➭➮➯➱➲➳➵➸➺➻➼➽➾".map(Char::toString)),
        Group(R.string.symbol_group_icons, "✓✔✕✖✗✘☀☁☂☃☄★☆☇☈☉☊☋☌☍☎☏☐☑☒☓☖☗☘☙☚☛☜☝☞☟☠☢☣☤☥☦☧☨☩☪☫☬☭☮☯☸☹☺☻☼☽☾☿♀♁♂♃♄♅♆♇♈♉♊♋♌♍♎♏♐♑♒♓♔♕♖♗♘♙♚♛♜♝♞♟♠♡♢♣♤♥♦♧♨♩♪♫♬♭♮♯⚀⚁⚂⚃⚄⚅⚐⚑⚒⚓⚔⚕⚖⚗⚙⚛⚜⚠⚡⚪⚫✁✂✃✄✆✈✉✌✍✎✏✐✑✒✙✚✛✜✝✞✟✠✡✢✣✤✥✦✧✩✪✫✬✭✮✯✰✱✲✳✴✵✶✷✸✹✺✻✼✽✾✿❀❁❂❃❄❅❆❇❈❉❊❋❖❝❞❡❢❣❤❥❦❧".map(Char::toString)),
        Group(R.string.symbol_group_shapes, "■□▢▣▤▥▦▧▨▩▪▫▬▭▮▯▰▱▲△▴▵▶▷▸▹►▻▼▽▾▿◀◁◂◃◄◅◆◇◈◉◊○◌◍◎●◐◑◒◓◔◕◖◗◘◙◚◛◜◝◞◟◠◡◢◣◤◥◦◧◨◩◪◫◬◭◮◯◰◱◲◳◴◵◶◷◸◹◺◻◼◽◾".map(Char::toString)),
        Group(R.string.symbol_group_units, "℃℉Ωμ㎡㎥㎝㎜㎞㎏㎎㏄㏑㏒㎟㎠㎢㎣㎤㎦㎍㎕㎖㎗㎘㎚㎛㎨㎩㎪㎫㎬㎭㎮㎯㎰㎱㎲㎳㎴㎵㎶㎷㎸㎹㎺㎻㎼㎽㎾㎿㍱㍲㍳㍴㍵㍶￥＄￠￡￢￣￤₠₡₢₣₤₥₦₧₨₩₪₫€₭₮₯₰₱₲₳₴₵₶₷₸₹₺₻₼₽₾₿".map(Char::toString)),
        Group(R.string.symbol_group_numbers, "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱ₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞⅐⅑⅒ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫⅬⅭⅮⅯⅰⅱⅲⅳⅴⅵⅶⅷⅸⅹⅺⅻ①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳⓪⓵⓶⓷⓸⓹⓺⓻⓼⓽⓾❶❷❸❹❺❻❼❽❾❿".map(Char::toString)),
        Group(R.string.symbol_group_phonetic, "āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹêɡɑəɛɪɔʊʌæɒɜɚʃʒθðŋɲʈɖɟɢɣɥɦɨɫɬɯɰɳɴɵɶɸɹɻɽɾʀʁʂʉʋʍʎʐʑʔʕʙʛʜʝʟʡʢㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦㄧㄨㄩ".map(Char::toString)),
        Group(R.string.symbol_group_latin, "ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿĀĂĄĆĈĊČĎĐĒĔĖĘĚĜĞĠĢĤĦĨĪĬĮİĲĴĶĹĻĽŁŃŅŇŊŌŎŐŒŔŖŘŚŜŞŠŢŤŦŨŪŬŮŰŲŴŶŸŹŻŽ".map(Char::toString)),
        Group(R.string.symbol_group_box, "─━│┃┄┅┆┇┈┉┊┋┌┍┎┏┐┑┒┓└┕┖┗┘┙┚┛├┝┞┟┠┡┢┣┤┥┦┧┨┩┪┫┬┭┮┯┰┱┲┳┴┵┶┷┸┹┺┻┼┽┾┿╀╁╂╃╄╅╆╇╈╉╊╋═║╒╓╔╕╖╗╘╙╚╛╜╝╞╟╠╡╢╣╤╥╦╧╨╩╪╫╬╭╮╯╰╱╲╳▀▁▂▃▄▅▆▇█▉▊▋▌▍▎▏▐░▒▓".map(Char::toString)),
    )
    val entries = groups.flatMapIndexed { index, group ->
        val chunks = group.symbols.chunked(27)
        chunks.mapIndexed { page, symbols -> Page(index, page + 1, chunks.size, symbols) }
    }
    val pages = entries.map(Page::symbols)
    fun firstPage(group: Int) = entries.indexOfFirst { it.group == group }
}
