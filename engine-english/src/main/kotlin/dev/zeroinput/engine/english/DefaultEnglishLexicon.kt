package dev.zeroinput.engine.english

/**
 * Project-authored offline English seed lexicon.
 *
 * The order is a compact frequency prior: words near the beginning are more
 * useful for a first page than rare words. It is deliberately data-only so
 * the engine remains deterministic and does not need a network dictionary.
 */
internal object DefaultEnglishLexicon {
    val words: List<String> = """
        the of and to in a is that for it as was with be by on not he i this are or his from at
        which but have an had they you were their one all we can her has there been if more when
        will would who so no said what about up its into than them could time only out do just
        know state people get through where much before your good some very them make should then
        now look only come its over think also back after use two how our work first well way even
        new want because these give day most us any those my take here thing see well way many
        such still own life should great another while last might never place under world school
        home house system hand high old year each small found every between need however both
        part end does set three put long right same another tell boy follow came show also around
        form much number off always man read keep children family fact begin good group against
        area turn move thing general help talk where before line means problem same another
        example country point page letter mother father night study book water room write mother
        answer learn change play spell air away animal house picture try us again animal point
        mother world near build self earth father head stand own page country found answer school
        grow study still learn plant cover food sun four thought let keep eye never last door
        between city tree cross since hard start might story saw far sea draw left late run don't
        while press close night real life few stop open seem together next white children begin
        got walk example paper often always music those both mark book letter until mile river car
        feet care second group carry took rain eat plain room friend began idea fish mountain north
        once base hear horse cut sure watch color face wood main enough plain girl usual young
        ready above ever red list though feel talk bird soon body dog family direct pose leave song
        measure product black short numeral class wind question happen complete ship area half rock
        order fire south piece told knew pass farm top whole king size heard best hour better true
        during hundred am remember step early hold west ground interest reach fast five sing listen
        six table travel less morning ten simple several vowel toward war lay against pattern slow
        center love person money serve appear road map science rule govern pull cold notice voice
        fall power town fine certain fly unit lead cry dark machine note wait plan figure star box
        noun field rest correct able pound done beauty drive stood contain front teach week final
        gave green oh quick develop sleep warm free minute strong special mind behind clear tail
        produce fact street inch lot nothing course stay wheel full force blue object decide surface
        deep moon island foot yet busy test record boat common gold possible plane age dry wonder
        laugh thousand ago ran check game shape yes hot miss brought heat snow bed bring sit
        perhaps fill east weight language among
        hello help keyboard input language privacy security offline user zero email message text
        data code function project update service simple information important include example
        business company public local personal account address phone number password network file
        folder document report status plan team meeting request response result value option
        setting settings feature version release build create source license open close save
        send receive search select candidate choose page previous next enter return space backspace
        correct correction typo word words sentence phrase typing type write written reading reader
        english chinese keyboard mobile android application app screen button window theme dark
        light color sound vibration touch hold swipe mode single floating layout
        today tomorrow yesterday monday tuesday wednesday thursday friday saturday sunday january
        february march april may june july august september october november december
        please thank thanks welcome sorry excuse congratulations happy birthday morning afternoon
        evening tonight goodbye hello yes no okay alright sure maybe probably really quite rather
        already almost enough together without within across along among around behind below beside
        beyond during except inside outside toward upon until despite whether although since
        therefore however instead otherwise usually sometimes often rarely always never
        first second third fourth fifth last final true false right wrong easy hard different same
        important possible available ready able unable likely clear free full empty public
        safe secure local online personal recent history favorite common normal special general
        small large big little long short high low old young early late new next previous current
        best better good great bad worse least most more less many few several enough extra only
        single double each every either neither both all none some any another other same own
        myself yourself himself herself itself ourselves themselves anyone someone everyone nobody
        something anything everything nothing somewhere anywhere everywhere nowhere
        ask answer call change check clean clear copy cut delete edit find get give go keep learn
        make move open paste read remove rename reset run select share show start stop submit take
        use view wait want watch send receive
    """.trimIndent().split(Regex("\\s+")).distinct()
}
