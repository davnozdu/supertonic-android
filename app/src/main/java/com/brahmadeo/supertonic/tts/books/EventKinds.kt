package com.brahmadeo.supertonic.tts.books

/** «Звуки событий»: one clip per kind of action, reused everywhere it occurs in the book. A door in any chapter is the
 * same sound, so the generator makes it once and a 500 000-character book needs a few dozen clips, not thousands. The
 * list is closed and the English description of every kind lives here: a model that names only the kind still gets a
 * sound, and the same kind never splits into two clips because of a differently worded sentence. */
object EventKinds {
    /** [key]: the short slug the model returns; [hint]: what it means, for the prompt; [sound]: the default English
     * description of the sound (used when the answer has none of its own). */
    data class Kind(val key: String, val hint: String, val sound: String)

    val ALL: List<Kind> = listOf(
        Kind("door_open", "дверь открывается", "a door opening with a soft creak"),
        Kind("door_close", "дверь закрывается", "a door closing with a soft thud"),
        Kind("door_slam", "дверь хлопнула", "a heavy door slams shut with a sharp bang"),
        Kind("door_knock", "стук в дверь", "three sharp knocks on a wooden door"),
        Kind("door_creak", "скрип двери, половиц", "a long creak of an old door hinge"),
        Kind("door_lock", "ключ в замке, засов", "a key turning in a lock with two clicks"),
        Kind("gate", "калитка, ворота", "a wooden gate swinging open and closing again"),
        Kind("window", "окно, рама, ставни", "a window frame creaking open"),
        Kind("curtain", "штора, занавеска", "curtain rings sliding along a rod"),
        Kind("paper", "бумаги, документы, газета", "dry paper rustling and sheets being shuffled"),
        Kind("letter", "письмо, конверт", "an envelope being torn open and paper unfolded"),
        Kind("book_page", "страницы книги", "pages of a book turning slowly"),
        Kind("pen", "перо, ручка пишет", "a pen scratching quickly on paper"),
        Kind("water_pour", "наливают воду, чай", "water poured into a glass until it is full"),
        Kind("water_splash", "всплеск воды", "a single splash of water with drops falling back"),
        Kind("water_running", "вода течёт, кран", "water running steadily from a tap"),
        Kind("water_sea", "волны, берег, река", "gentle waves lapping against a shore"),
        Kind("kettle", "чайник, самовар", "a kettle beginning to whistle over a flame"),
        Kind("cup", "чашка, стакан", "a cup set down on a saucer with a light clink"),
        Kind("dishes", "посуда", "plates and cutlery clattering softly"),
        Kind("cutlery", "вилка, нож, ложка", "silver cutlery being laid on a table"),
        Kind("bottle", "бутылка, графин", "a bottle set on a table with a dull clink"),
        Kind("glass_break", "разбилось стекло", "glass shattering on the floor with falling shards"),
        Kind("drop", "что-то падает, удар предмета", "a heavy object dropping and hitting the floor"),
        Kind("footsteps_wood", "шаги по дереву, половицы", "measured footsteps on creaking wooden floorboards"),
        Kind("footsteps_stone", "шаги по камню", "footsteps echoing on stone slabs"),
        Kind("footsteps_gravel", "шаги по гравию, земле", "boots crunching on gravel for a few seconds"),
        Kind("footsteps_snow", "шаги по снегу", "footsteps squeaking through dry snow"),
        Kind("footsteps_stairs", "шаги по лестнице", "footsteps going up a wooden staircase"),
        Kind("horse_hooves", "лошадь, копыта", "hooves clipping on cobblestones and slowing to a stop"),
        Kind("cart", "телега, экипаж, сани", "wooden wheels rumbling over cobblestones"),
        Kind("train", "поезд, паровоз, вагон", "a steam locomotive approaching and braking with a hiss"),
        Kind("car", "автомобиль, машина", "a car pulling up, its engine idling, then stopping"),
        Kind("crowd", "толпа, много людей", "a murmur of many voices and shuffling feet"),
        Kind("laughter", "смех", "a short burst of laughter close by"),
        Kind("scream", "крик, вопль", "a frightened scream somewhere in the distance"),
        Kind("cry", "плач", "someone crying quietly"),
        Kind("cough", "кашель", "a dry heavy cough"),
        Kind("sigh", "вздох", "a long tired sigh"),
        Kind("bell", "звонок, колокольчик", "a small hand bell ringing twice"),
        Kind("church_bell", "колокол", "a deep church bell striking slowly"),
        Kind("clock", "часы", "a clock ticking and then chiming the hour"),
        Kind("whistle", "свисток, гудок", "a sharp whistle followed by a long low horn"),
        Kind("fire", "огонь, камин, печь", "a fire crackling and shifting in a stove"),
        Kind("match", "спичка", "a match striking and flaring up"),
        Kind("lamp", "лампа, свеча, фонарь", "a glass lamp chimney being lifted and set down"),
        Kind("chair", "стул, кресло", "a chair scraping on the floor as someone sits down"),
        Kind("table", "стол", "a heavy table being pushed an inch across the floor"),
        Kind("furniture", "мебель, шкаф, ящик", "a wooden drawer pulled open and pushed shut"),
        Kind("coin", "монеты, деньги", "coins clinking and being counted on a table"),
        Kind("bag", "сумка, чемодан", "a leather bag unbuckled and set on the floor"),
        Kind("cloth", "одежда, ткань", "cloth rustling as a coat is taken off"),
        Kind("weapon", "оружие, сабля, нож", "steel scraping softly as a blade leaves its sheath"),
        Kind("gunshot", "выстрел", "a single gunshot cracking in the open air"),
        Kind("fight", "драка, борьба", "a short scuffle, a blow and a body falling"),
        Kind("thunder", "гром", "a distant thunder roll, low and long"),
        Kind("rain", "дождь", "rain drumming on a windowsill"),
        Kind("wind", "ветер", "a gust of wind rattling a window frame"),
        Kind("dog", "собака лает", "a dog barking twice somewhere close"),
        Kind("cat", "кошка", "a cat meowing briefly"),
        Kind("bird", "птицы, воробьи", "birds fluttering up from a bush"),
        Kind("animal", "скот, животное", "an animal shifting and lowing in a stall"),
        Kind("machine", "механизм, станок", "a machine starting up with a metallic whine"),
        Kind("engine", "двигатель, мотор", "an engine turning over and catching"),
        Kind("tool", "инструмент, молоток", "hammer blows on metal ringing out"),
        Kind("keys", "ключи", "a bunch of keys jingling"),
        Kind("child", "ребёнок", "a child's voice calling out"),
        Kind("other", "другое (в поле sound опиши сам)", "a short distinctive sound effect")
    )

    /** The slug the model returns: lowercase letters, digits and underscores. */
    val KEY = Regex("^[a-z][a-z0-9_]{1,30}$")

    private val byKey: Map<String, Kind> = ALL.associateBy { it.key }

    fun known(key: String?): Boolean = key != null && key in byKey

    /** The default sound of [key], or null for an unknown kind. */
    fun sound(key: String?): String? = key?.let { byKey[it]?.sound }

    /** The whole list for the prompt: «door_open — дверь открывается; …» (one line, the model copies a key exactly). */
    fun promptList(): String = ALL.joinToString("; ") { "${it.key} — ${it.hint}" }
}
