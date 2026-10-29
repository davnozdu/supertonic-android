#!/usr/bin/env python3
"""Дополняет базу звуков: бытовые, окна/форточки/ставни, инструменты, бой, эмоции, животные, музыка.

Каждый новый звук — 8 с, английское описание для генератора, русская подсказка для интерфейса.
Ключи проверяются на совпадение с уже существующими.
"""
import json
import pathlib

REPO = pathlib.Path(__file__).resolve().parents[1].parent  # supertonic-android/
SPEC = REPO / "tools" / "sound_bank" / "sounds.json"

NEW = [
    # окна, форточки, ставни, двери, дом
    ("window_vent", "a small ventilation pane opening with a wooden squeak", "форточка открывается"),
    ("vent_close", "a ventilation pane snapping shut", "форточка захлопнулась"),
    ("shutters_bang", "wooden shutters banging against the wall in the wind", "ставни бьются о стену"),
    ("shutters_close", "wooden shutters pulled closed and latched", "ставни закрывают"),
    ("blind_pull", "a roller blind pulled down with a rattle", "штора-роллета с треском"),
    ("hinge_squeal", "a stiff iron hinge squealing as a door is pushed", "петли скрипят пронзительно"),
    ("door_latch_click", "a door latch clicking shut", "щёлкнула дверная защёлка"),
    ("key_turn", "a key turning in a lock with two heavy clicks", "ключ поворачивается в замке"),
    ("bolt_slide", "an iron door bolt sliding and dropping into place", "задвижка засова"),
    ("chain_rattle_door", "a door chain rattling against wood", "цепочка на двери звякает"),
    ("attic_stairs", "a narrow wooden staircase creaking under careful steps", "скрип лестницы на чердак"),
    ("pipe_knock_hot", "water pipes knocking and ticking in a heating system", "трубы стучат и щёлкают"),
    ("radiator_hiss", "a radiator hissing and ticking as it heats", "радиатор шипит"),
    ("faucet_open", "a faucet opened, water rushing into a basin", "открыли кран"),
    ("drain_gurgle", "a sink drain gurgling away", "вода уходит в слив с бульканьем"),
    ("washing_scrub", "a stiff brush scrubbing a floor with water", "трут пол щёткой"),
    ("mop_squeeze", "a wet mop squeezed and slapped on the floor", "отжимают швабру"),
    ("cupboard_door", "a kitchen cupboard door opening and closing", "дверца шкафчика"),
    ("oven_door", "an iron oven door opened with a scrape and a clang", "дверца печи с лязгом"),
    ("stove_lit", "a wood stove catching fire, crackling and drawing air", "растапливают печь"),
    ("pot_lid", "a heavy pot lid lifted and set down with a clatter", "крышка кастрюли"),
    ("bottle_uncork", "a cork pulled out of a bottle with a soft pop", "вынимают пробку из бутылки"),
    ("cork_pop", "a cork popping out of a bottle", "пробка хлопнула"),
    ("ice_glass", "ice cubes dropped into a glass with a clink", "лёд в стакане звякнул"),
    ("sugar_stir", "a spoon stirring sugar in a cup", "размешивают сахар в чашке"),
    ("tea_sip", "a sip of hot tea with a small slurp", "прихлёбывают чай"),
    ("bread_dough", "dough kneaded on a wooden table", "месят тесто"),
    ("flour_pour", "flour poured into a bowl", "насыпают муку"),
    ("egg_crack", "an egg cracked against the rim of a bowl", "разбивают яйцо"),
    ("dishes_stack", "plates stacked on each other with a clatter", "ставят тарелки стопкой"),
    ("towel_wring", "a wet towel wrung out with a soft splash", "отжимают мокрое полотенце"),
    ("blanket_fold", "a wool blanket shaken out and folded", "складывают шерстяное одеяло"),
    ("drawer_stuck", "a stuck wooden drawer yanked open with a scrape", "заевший ящик дёргают"),
    ("furniture_drag", "a heavy piece of furniture dragged across a wooden floor", "двигают тяжёлую мебель"),
    ("chair_fold", "a folding chair snapped open", "складной стул раскрывают"),
    ("rug_beat", "a dust rug beaten with a stick", "выбивают ковёр"),
    ("clock_wind", "a mechanical clock being wound up with a ratchet", "заводят часы"),
    ("lamp_oil", "an oil lamp glass chimney set on with a glassy clink", "ставят стекло лампы"),
    ("candle_lit", "a candle wick catching light with a soft flare", "зажигают свечу"),
    ("inkwell_dip", "a pen dipped into an inkwell", "обмакивают перо в чернильницу"),
    ("umbrella_open", "an umbrella opened with a snap", "раскрывают зонт"),
    ("cane_tap", "a walking cane tapping on stone", "стук трости по камню"),
    ("glove_pull", "leather gloves being pulled on", "натягивают перчатки"),
    ("belt_buckle", "a leather belt buckled with a click", "застёгивают ремень"),
    ("buttons_fasten", "buttons fastened through a shirt", "застёгивают пуговицы"),
    ("silk_rustle", "silk skirts rustling with movement", "шелест шёлковых юбок"),
    ("coat_off", "a heavy coat taken off and shaken out", "снимают тяжёлое пальто"),
    ("hat_set", "a hat set down on a table", "ставят шляпу на стол"),
    ("mirror_wipe", "a cloth wiped over a mirror", "протирают зеркало"),
    ("razor_strop", "a razor stropped on a leather belt", "правят бритву на ремне"),
    ("knitting", "knitting needles clicking together", "стучат спицы"),
    ("shoe_polish", "a brush polishing a leather shoe", "чистят обувь щёткой"),
    ("boiler_rumble", "a boiler rumbling deep in the basement", "гудит котельная в подвале"),
    ("steam_valve", "a steam valve releasing pressure with a sharp hiss", "паровой клапан шипит"),
    # улица, транспорт
    ("tram_bell", "an old tram bell ringing twice", "звонок трамвая"),
    ("tram_pass", "a tram passing on rails with a steel squeal", "трамвай проезжает"),
    ("car_pass_gravel", "a car passing on gravel, tires crunching", "машина по гравию"),
    ("cabbie_shout", "a cab driver shouting at his horse and clicking his tongue", "извозчик кричит на лошадь"),
    ("street_organ", "a street organ grinding a short barrel tune", "шарманка на улице"),
    ("newsboy", "a newsboy shouting headlines on a corner", "газетчик выкрикивает"),
    ("station_announce", "a station bell followed by a muffled loudspeaker announcement", "объявление на вокзале"),
    ("bicycle_pass", "a bicycle rolling past with a ticking freewheel", "велосипед проезжает"),
    # природа
    ("hail_storm", "hail drumming on a tin roof", "град бьёт по железу"),
    ("sleet_rain", "sleet hissing on a windowsill", "мокрый снег шипит на подоконнике"),
    ("ice_crack", "ice cracking under a boot", "лёд трещит под ногой"),
    ("puddle_step", "a boot splashing through a puddle", "шаг в лужу"),
    ("crickets_night", "crickets chirping on a summer night", "сверчки ночью"),
    ("cicadas", "cicadas buzzing in the summer heat", "цикады"),
    ("crows", "crows cawing in the distance", "каркают вороны"),
    ("pigeons", "pigeons flapping up from a square", "взлетают голуби"),
    ("owl_call", "an owl calling twice in the dark", "крик совы"),
    ("geese", "geese honking and flapping", "гогочут гуси"),
    ("cow_bell", "a cowbell clanking as cattle move", "бренчит колокольчик коровы"),
    ("sheep_flock", "sheep bleating in a flock", "блеют овцы"),
    ("tree_wind", "wind moving through leaves overhead", "ветер в листве"),
    ("leaves_step", "dry leaves rustled by footsteps", "шаги по сухим листьям"),
    ("fire_flare", "a fire flaring up and spitting sparks", "огонь вспыхивает"),
    ("coal_drop", "coal poured into a bucket with a heavy rattle", "угли падают в ведро"),
    ("log_drop", "a log dropped onto a woodpile", "полено падает"),
    ("bucket_swing", "an empty tin bucket swinging on its handle", "ведро качается"),
    # инструменты, работа
    ("wood_plane", "a carpenter's plane pushing a shaving off a board", "рубанок по доске"),
    ("chisel_mallet", "a chisel struck by a wooden mallet", "стамеска под киянкой"),
    ("metal_file", "a metal file scraping a piece of steel", "напильник по металлу"),
    ("sandpaper", "sandpaper rubbed on wood", "шкурка по дереву"),
    ("grinder", "a grinding wheel sparking against metal", "точильный круг"),
    ("bellows", "blacksmith's bellows blowing air into a forge", "мехи раздувают горн"),
    ("forge_hammer", "a hammer striking hot iron on an anvil with a ringing blow", "удар по раскалённому железу"),
    ("drill_hand", "a hand drill turning with a ratchet", "ручная дрель"),
    ("scythe", "a scythe cutting grass in long strokes", "коса косит траву"),
    ("rake_gravel", "a rake dragging over gravel", "грабли по гравию"),
    ("wheelbarrow", "a wheelbarrow rolling over rough ground", "тачка по неровной земле"),
    ("ladder_step", "a wooden ladder creaking under a step", "скрип лестницы-стремянки"),
    ("winch", "a winch ratchet cranking and a rope tightening", "лебёдка с трещоткой"),
    ("pulley_squeal", "a pulley squealing as a weight rises", "блок скрипит под грузом"),
    # бой, оружие
    ("blade_clash", "two steel blades clashing", "лязг скрещённых клинков"),
    ("blade_slice", "a blade slicing through cloth and air", "клинок рассекает воздух"),
    ("bowstring", "a bowstring released with a deep twang", "спуск тетивы"),
    ("arrow_whiz", "an arrow passing close by with a whiz", "стрела пролетает"),
    ("gun_cock", "a revolver hammer cocked with two clicks", "взвод курка"),
    ("shell_drop", "brass shell casings dropping on a stone floor", "гильзы падают на камень"),
    ("spear_thrust", "a spear thrust into wood with a split and thud", "удар копьём"),
    ("dagger_draw", "a dagger pulled from a leather sheath", "кинжал из ножен"),
    ("club_blow", "a club striking a wooden door with a heavy thud", "удар дубиной по двери"),
    ("chain_whip", "a chain whipping through the air and cracking", "цепь рассекает воздух"),
    # эмоции и голос
    ("cursing", "a man cursing angrily under his breath", "ругань сквозь зубы"),
    ("laugh_hysterical", "hysterical laughter rising and breaking", "истерический смех"),
    ("sob_muffled", "muffled sobbing into hands", "сдавленное всхлипывание"),
    ("scream_child", "a child screaming in fear", "детский крик"),
    ("sigh_tired", "a long tired sigh", "усталый вздох"),
    ("squeal_fear", "a frightened squeal", "визг от страха"),
    ("moan_relief", "a low moan of relief", "стон облегчения"),
    # животные, дети, мелочи
    ("cat_hiss", "a cat hissing", "кошка шипит"),
    ("mouse_squeak", "a mouse squeaking", "мышь пищит"),
    ("horse_drink", "a horse drinking from a trough", "лошадь пьёт из колоды"),
    ("ball_bounce", "a ball bouncing on a wooden floor", "мяч скачет по полу"),
    ("jump_rope", "a skipping rope whipping the floor", "скакалка бьёт по полу"),
    ("piano_keys", "a few piano keys pressed", "несколько нот рояля"),
    ("guitar_strum", "a guitar strummed once", "гитарный аккорд"),
    ("violin_tune", "a violin being tuned with short bow strokes", "настройка скрипки"),
    ("accordion", "an accordion breathing a short chord", "аккордеон"),
    ("flute_note", "a flute playing a short phrase", "флейта"),
    ("cymbals", "cymbals crashing once", "тарелки"),
    ("tambourine", "a tambourine shaken", "бубен"),
    ("funeral_bell", "a single slow funeral bell tolling", "погребальный звон"),
    ("prayer_murmur", "many voices murmuring a low prayer", "молитва вполголоса"),
    ("telegraph_key", "a telegraph key tapping out a message", "телеграфный ключ"),
]


def main() -> int:
    spec = json.loads(SPEC.read_text())
    have = {s["key"] for s in spec["sounds"]}
    added = 0
    for key, prompt, hint in NEW:
        if key in have:
            print("есть:", key)
            continue
        spec["sounds"].append({"key": key, "prompt": prompt, "hint": hint,
                               "seed": abs(hash(key)) % 90000 + 100, "seconds": 8})
        have.add(key)
        added += 1
    SPEC.write_text(json.dumps(spec, ensure_ascii=False, indent=2) + "\n")
    print(f"добавлено {added}, всего {len(spec['sounds'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
