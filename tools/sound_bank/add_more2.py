#!/usr/bin/env python3
"""Вторая волна звуков: предметы дома, улица и город, природа, тело, бой, ремесло, обряды, музыка.

Ничего не дублирует: ключи, уже бывшие в базе, пропускаются.
"""
import json
import pathlib

REPO = pathlib.Path(__file__).resolve().parents[1].parent
SPEC = REPO / "tools" / "sound_bank" / "sounds.json"

NEW = [
    # камин, свечи, предметы
    ("poker_fire", "an iron poker stirring coals in a fireplace", "кочерга в камине"),
    ("embers_sizzle", "embers hissing and settling in a fireplace", "угли шипят"),
    ("wind_chime", "glass wind chimes tinkling in a breeze", "стеклянные подвески звенят"),
    ("soap_wash", "hands washed with soap in a basin of water", "моют руки с мылом"),
    ("basin_pour", "water poured from a jug into a wash basin", "льют воду из кувшина"),
    ("towel_snap", "a towel snapped open", "встряхивают полотенце"),
    ("sheets_rustle", "bedsheets rustling as someone turns over", "шуршат простыни"),
    ("mattress_straw", "a straw mattress rustling under a body", "соломенный тюфяк шуршит"),
    ("sit_down_chair", "a chair creaking as someone sits down", "садятся на стул — скрип"),
    ("stand_up_chair", "a chair scraping back as someone stands", "встают — стул скрипнул"),
    ("stumble_furniture", "someone stumbling into a table with a thud", "наткнулись на стол"),
    ("box_open", "a wooden box lid opened with a creak", "открывают деревянную шкатулку"),
    ("box_close", "a wooden box lid closed with a clap", "захлопывают шкатулку"),
    ("chest_lid", "a heavy chest lid falling shut", "крышка сундука падает"),
    ("trunk_lock", "a trunk lock snapping shut", "замок сундука щёлкает"),
    ("razor_shave", "a straight razor scraping stubble", "бреются опасной бритвой"),
    ("comb_hair", "a comb pulled through hair", "расчёсывают волосы"),
    ("boot_pull_on", "a boot pulled on with a grunt of leather", "натягивают сапог"),
    ("cloak_fasten", "a cloak fastened at the shoulder", "застёгивают плащ"),
    ("parasol_open", "a parasol snapped open", "раскрывают зонтик от солнца"),
    ("fan_flutter", "a paper fan fluttering shut", "веер складывается"),
    ("necklace_clink", "a necklace of beads rattled", "звенят бусы"),
    ("bracelet_clink", "a bracelet clinking on a wrist", "звенят браслеты"),
    ("glass_toast", "glasses clinking together in a toast", "чокаются бокалами"),
    ("wine_pour", "wine poured into a glass", "наливают вино"),
    ("bottle_set", "a bottle set down on a table", "ставят бутылку на стол"),
    ("decanter_stopper", "a glass decanter stopper lifted with a pop", "пробка графина"),
    ("fork_plate", "a fork scraping a plate", "вилка скребёт по тарелке"),
    ("napkin_shake", "a napkin shaken out", "встряхивают салфетку"),
    ("bread_break", "a crusty loaf broken in half", "ломают хлеб"),
    ("coffee_grind", "coffee beans ground in a hand mill", "мелют кофе"),
    ("kettle_set_down", "a kettle set down on a stove with a clang", "ставят чайник на плиту"),
    ("water_spill", "a bucket of water spilled across a floor", "разливают воду"),
    ("laundry_beat", "wet laundry slapped against a washboard", "стирают на доске"),
    ("clothesline_wind", "laundry flapping on a line in the wind", "бельё хлопает на ветру"),
    ("gate_latch", "a garden gate latch clicking open", "щеколда калитки"),
    ("gate_swing", "a wooden gate swinging on its hinges", "калитка качается"),
    ("fence_creak", "a wooden fence creaking in the wind", "забор скрипит"),
    ("shutter_latch", "an iron shutter latch lifted", "поднимают засов ставни"),
    ("door_handle_turn", "a door handle turned and released", "поворачивают ручку двери"),
    ("knock_window", "a knock on a window pane", "стучат в окно"),
    ("knock_desk", "knuckles rapping on a wooden desk", "стучат по столу костяшками"),
    ("heel_stone", "heels clicking on a stone pavement", "каблуки по камню"),
    ("shoes_squeak", "wet shoes squeaking on a polished floor", "обувь скрипит по паркету"),
    # улица, город, заведения
    ("factory_whistle", "a factory steam whistle signalling the end of a shift", "гудок завода"),
    ("factory_hum", "machinery humming behind a factory wall", "гул машин за стеной"),
    ("printing_press", "a printing press stamping and rolling", "типографский станок"),
    ("street_sweeper", "a broom scraping along a street", "метут улицу"),
    ("water_cart", "a water cart rolling with sloshing", "бочка с водой катится"),
    ("omnibus_pass", "a horse-drawn omnibus rattling past", "омнибус проезжает"),
    ("carriage_cobbles", "carriage wheels rattling over cobblestones", "колёса по булыжнику"),
    ("cab_door_slam", "a cab door slammed shut", "дверца экипажа хлопнула"),
    ("ferry_horn", "a ferry horn sounding over water", "гудок парома"),
    ("anchor_drop", "an anchor chain running out with a splash", "якорная цепь с плеском"),
    ("ship_horn", "a ship's deep horn sounding in fog", "гудок парохода в тумане"),
    ("harbour_crowd", "dockworkers shouting over water", "крики в порту"),
    ("market_trader", "a trader calling out prices", "торговец выкрикивает цены"),
    ("beggar_voice", "a beggar whining for alms", "нищий просит подаяние"),
    ("street_children", "children shouting in a street", "дети кричат на улице"),
    ("church_crowd", "a congregation rising with shuffling and coughs", "прихожане поднимаются"),
    ("school_bell", "a school handbell ringing", "звонок в школе"),
    ("class_room", "a classroom murmuring with papers and voices", "шум класса"),
    ("courtroom_murmur", "a courtroom murmuring before a gavel bang", "гул в зале суда"),
    ("gavel", "a gavel striking a wooden desk", "удар молотка судьи"),
    ("ball_dance", "dance music and feet turning in a ballroom", "бал: музыка и шаги"),
    ("waiter_steps", "a waiter's quick steps with rattling dishes", "официант спешит с посудой"),
    ("restaurant_murmur", "a busy restaurant murmuring with cutlery", "шум ресторана"),
    ("tavern_noise", "a tavern full of voices and clinking mugs", "шум трактира"),
    ("mug_set_down", "a heavy beer mug set down on wood", "кружка пива на стол"),
    ("cards_shuffle", "a deck of cards being shuffled", "тасуют карты"),
    ("dice_roll", "dice rolling and clicking on a table", "костяшки катятся"),
    ("billiard_balls", "billiard balls clicking together", "шары на бильярде"),
    # природа
    ("brook_flow", "a stream running over stones", "ручей по камням"),
    ("waterfall", "a waterfall roaring", "шум водопада"),
    ("river_flow", "a wide river flowing past", "река течёт"),
    ("mud_squelch", "boots squelching through deep mud", "чавканье в грязи"),
    ("tent_flap_wind", "a canvas tent flap flapping in the wind", "полог палатки хлопает"),
    ("rain_canvas", "rain drumming on canvas", "дождь по брезенту"),
    ("storm_approach", "a storm building with distant rolling thunder", "буря приближается"),
    ("branch_break", "a dry branch snapping underfoot", "ветка хрустнула"),
    ("thaw_drip", "melting icicles dripping steadily", "капель с сосулек"),
    ("frogs_pond", "frogs croaking in a pond", "лягушки в пруду"),
    ("wasp_buzz", "a wasp buzzing close to the ear", "оса жужжит"),
    ("mosquitoes", "mosquitoes whining near an ear", "комары пищат"),
    ("birds_morning", "birds singing at dawn", "птицы на рассвете"),
    ("magpie", "a magpie chattering", "стрекочет сорока"),
    ("squirrel", "a squirrel scampering up bark", "белка карабкается по коре"),
    ("fox_scream", "a fox screaming at night", "крик лисицы"),
    ("dog_howl", "a dog howling far away", "собака воет вдалеке"),
    ("horse_stamp", "a horse stamping in a stall", "лошадь бьёт копытом"),
    ("cattle_low", "a cow lowing low and long", "корова мычит"),
    ("goat_bleat", "a goat bleating", "блеет козa"),
    ("pig_squeal", "a pig squealing", "визжит свинья"),
    ("leaves_whirl", "leaves swirling on a gust of wind", "листья кружатся"),
    # тело, эмоции
    ("heartbeat_fast", "a heartbeat quickening", "стук сердца частит"),
    ("stomach_rumble", "a stomach rumbling", "урчит в животе"),
    ("hiccup", "a single hiccup", "икота"),
    ("spit", "a man spitting on the ground", "плюют на землю"),
    ("whisper_close", "a whisper right next to the ear", "шёпот над самым ухом"),
    ("cry_baby", "a baby crying", "плачет младенец"),
    ("scream_pain", "a scream of pain", "крик от боли"),
    ("groan_sleep", "a groan in sleep", "стон во сне"),
    # бой, оружие
    ("battle_cry", "a battle cry from many throats", "боевой клич"),
    ("rifle_bolt", "a rifle bolt worked and a cartridge ejected", "затвор винтовки"),
    ("bayonet_charge", "boots charging with a shout", "атака в штыки"),
    ("armor_clank", "armor plates clanking as a man walks", "лязг доспехов"),
    ("shield_block", "a shield taking a heavy blow", "удар в щит"),
    ("arrow_hit", "an arrow thudding into wood", "стрела втыкается в дерево"),
    ("cannon_shot", "a cannon firing with a deep boom", "пушечный выстрел"),
    ("musket_cock", "a musket hammer cocked", "взвод мушкета"),
    ("powder_flash", "powder flashing in a pan with a hiss", "вспышка пороха на полке"),
    ("flare_whistle", "a signal rocket whistling up", "сигнальная ракета"),
    # ремесло и работа
    ("loom", "a hand loom clacking", "стучит ткацкий станок"),
    ("pottery_wheel", "a pottery wheel turning with wet clay", "гончарный круг"),
    ("basket_weave", "reeds being woven into a basket", "плетут корзину"),
    ("leather_stitch", "an awl and thread pulled through leather", "шорная игла с дратвой"),
    ("glass_blow", "a glassblower shaping a vessel", "стеклодув формует"),
    ("mill_stones", "millstones grinding grain", "жернова мелют"),
    ("pump_handle", "a hand pump worked with a gush of water", "качают насос"),
    ("axe_chop_wood", "an axe splitting a log", "колют дрова"),
    ("saw_branch", "a saw cutting through a branch", "пилят ветку"),
    ("rope_coil", "a rope dropped and coiled on the floor", "бросают и сматывают верёвку"),
    ("sack_drop", "a heavy sack dropped on the floor", "мешок падает на пол"),
    ("crate_drag", "a wooden crate dragged over stone", "тащат ящик по камню"),
    ("nails_pour", "a handful of nails poured on a bench", "насыпают гвозди"),
    # письмо, канцелярия
    ("pen_scratch", "a pen scratching quickly across paper", "быстро пишут пером"),
    ("folder_close", "a folder closed with a slap", "папка захлопывается"),
    ("map_unroll", "a map unrolled on a table", "разворачивают карту"),
    ("ledger_open", "a heavy ledger opened and pages turned", "раскрывают счётную книгу"),
    ("ink_splash", "a pen shaken with ink spattering", "брызги чернил"),
    # обряды, музыка
    ("church_hymn", "a congregation singing a hymn", "поют псалом"),
    ("wedding_bells", "wedding bells ringing joyfully", "свадебный звон"),
    ("funeral_organ", "a funeral organ chord held long", "траурный аккорд органа"),
    ("train_depart", "a train pulling away with steam and wheels", "поезд трогается"),
    ("train_brake", "train brakes squealing to a stop", "поезд тормозит"),
    ("tram_stop", "a tram stopping with a bell and brakes", "трамвай останавливается"),
    ("bicycle_chain", "a bicycle chain clicking as it rolls", "цепь велосипеда"),
    ("car_door_slam", "a car door slammed shut", "дверца машины хлопнула"),
    ("corset_creak", "a corset lacing pulled tight", "затягивают корсет"),
    ("ring_tap", "a ring tapped on a table", "стучат кольцом по столу"),
    ("spectacles_case", "spectacles folded into a case", "складывают очки в футляр"),
    ("snuffbox", "a snuff box snapped open", "табакерка открывается"),
    ("pipe_lit", "a pipe lit with a drawing puff", "раскуривают трубку"),
    ("cigar_cut", "a cigar cut and lit", "обрезают и зажигают сигару"),
    ("smoke_exhale", "a long exhale of smoke", "выдох дыма"),
    ("spittoon", "a spit into a brass spittoon", "плевок в плевательницу"),
    ("harmonica", "a harmonica played in short notes", "губная гармоника"),
    ("balalaika", "a balalaika strummed", "балалайка"),
    ("brass_band_far", "a brass band playing far away", "духовой оркестр вдали"),
    ("audience_laugh", "an audience laughing together", "смех публики"),
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
