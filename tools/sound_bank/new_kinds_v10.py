#!/usr/bin/env python3
"""Новые виды базы звуков (волна v10): то, чего в базе нет совсем.

У каждого вида три РАЗНЫХ промпта — тот же смысл действия, но другой предмет, материал, расстояние или манера,
чтобы три варианта клипа звучали по-разному (раньше варианты отличались только seed).

Правила промптов: английский; одно короткое событие на 8 с; без речи, пения и разборчивых голосов; без ровного
гула, шипения и электрического шума как фона (это не подложка, а событие).

Формат: (ключ, русская подсказка, [промпт 1, промпт 2, промпт 3]).
"""

NEW = [
    # --- кухня и бытовая техника ---
    ("fridge_open", "открывают холодильник", [
        "a refrigerator door pulled open with a rubber seal unsticking, bottles rattling in the door",
        "an old fridge door opening with a soft suction pop and glass jars clinking",
        "a fridge door opened and closed again with a dull thud of the seal"]),
    ("microwave_beep", "микроволновка пищит", [
        "a microwave oven door shutting, buttons beeping and the turntable starting",
        "a microwave finishing with three loud beeps and the door clicking open",
        "short electronic beeps of a microwave keypad followed by a click"]),
    ("toaster_pop", "тостер выбрасывает хлеб", [
        "a toaster lever pushed down with a metallic click",
        "toast popping up from a toaster with a spring twang",
        "a toaster popping up and two slices rattling in the slots"]),
    ("blender", "блендер", [
        "a kitchen blender whirring loudly as it crushes ice",
        "a blender starting, pulsing twice, then stopping",
        "a small hand blender buzzing in a bowl of soup"]),
    ("coffee_machine", "кофемашина", [
        "an espresso machine grinding beans, then hissing as coffee drips into a cup",
        "a capsule coffee maker pumping and sputtering into a mug",
        "a drip coffee maker gurgling as the last water runs through"]),
    ("can_open", "открывают банку газировки", [
        "a soda can pull tab cracked open with a sharp hiss of fizz",
        "a beer can opened, foam fizzing over the rim",
        "a tin can opened with a hand can opener, metal crunching round the lid"]),
    ("beer_bottle_open", "открывают пиво", [
        "a beer bottle cap pried off with an opener and a quick pssht",
        "a bottle cap flicked off against a table edge, the cap skittering away",
        "a swing-top bottle opened with a wire click and a soft pop"]),
    ("dishwasher", "посудомойка", [
        "a dishwasher door pulled down and the racks rolled out with clattering plates",
        "a dishwasher splashing water inside, muffled behind its door",
        "a dishwasher door slammed shut and the latch clicking"]),
    ("vacuum_cleaner", "пылесос", [
        "a vacuum cleaner switched on and pushed across a carpet",
        "an old upright vacuum cleaner roaring, then switched off and winding down",
        "a vacuum cleaner sucking up crumbs from a wooden floor with rattling bits"]),
    ("washing_machine", "стиральная машина", [
        "a washing machine drum turning with clothes sloshing in water",
        "a washing machine spinning fast and rattling on a tiled floor",
        "a washing machine door clicking open and wet laundry pulled out"]),
    ("hair_dryer", "фен", [
        "a hair dryer blowing hot air, switched between speeds",
        "a small hair dryer switched on and off twice",
        "a hair dryer running while a brush pulls through hair"]),
    ("plastic_bag", "пакет шуршит", [
        "a plastic shopping bag rustled and tied in a knot",
        "groceries pulled out of a crinkling plastic bag",
        "a thin plastic bag crumpled and stuffed into a pocket"]),
    ("paper_bag", "бумажный пакет", [
        "a brown paper bag opened and rustled",
        "a paper grocery bag set down on a counter, cans shifting inside",
        "a paper bag crumpled into a ball"]),
    ("cardboard_box", "картонная коробка", [
        "a cardboard box flaps opened and packing paper rustling",
        "a cardboard box dragged across a floor",
        "an empty cardboard box flattened and folded with tearing creases"]),
    ("tape_rip", "отрывают скотч", [
        "packing tape pulled off a roll with a loud screech and torn",
        "a tape dispenser run along a box seam",
        "sticky tape peeled slowly off a parcel"]),
    ("stapler", "степлер", [
        "a desk stapler pressed twice with sharp clacks",
        "a heavy stapler punching through a thick stack of paper",
        "a stapler jammed, clicking, then a staple pulled out"]),
    ("light_switch", "щёлкают выключателем", [
        "an old wall light switch flicked on with a solid click",
        "a pull cord light switch tugged with a click",
        "a row of light switches flicked off one by one"]),
    ("doorbell_electric", "электрический звонок в дверь", [
        "an electric doorbell buzzing twice in an apartment",
        "a ding-dong doorbell chime in a hallway",
        "an old electric door bell ringing long and insistently"]),
    ("intercom_buzz", "домофон", [
        "an apartment intercom buzzing and the door lock releasing with a clack",
        "an entry phone beeping and the heavy entrance door clicking open",
        "an intercom handset lifted off its cradle with a click"]),
    ("smoke_alarm", "пожарная сигнализация", [
        "a smoke detector beeping loudly in a kitchen",
        "a smoke alarm chirping once, low battery warning",
        "a fire alarm bell ringing in a building corridor"]),
    ("alarm_digital", "электронный будильник", [
        "a digital alarm clock beeping repeatedly on a bedside table",
        "a phone alarm ringing a bright repeating tone, then silenced",
        "a cheap alarm clock buzzing harshly, then slapped off"]),
    ("vinyl_record", "пластинка", [
        "a needle dropped onto a vinyl record with soft crackle",
        "the run-out groove of a vinyl record clicking round and round",
        "a record player arm lifted and the turntable stopping"]),
    ("slippers_shuffle", "шаркают тапочки", [
        "slippers shuffling slowly across a wooden floor",
        "soft house slippers flapping down a corridor",
        "an old man shuffling in felt slippers on linoleum"]),
    ("bare_feet", "босые ноги по полу", [
        "bare feet padding across a wooden floor",
        "bare feet slapping on a wet tiled floor",
        "bare feet running quickly down a hallway"]),
    ("boots_off", "снимают сапоги", [
        "heavy boots pulled off and dropped on the floor one after the other",
        "rubber boots tugged off with a squelch and set down",
        "a boot unlaced and kicked off against a wall"]),
    ("pillow", "взбивают подушку", [
        "a feather pillow punched and fluffed",
        "a head dropping onto a soft pillow with a rustle of the case",
        "a pillow thrown onto a bed with a soft thump"]),
    ("bed_jump", "бросаются на кровать", [
        "someone flopping down onto a creaking bed",
        "a child jumping on bedsprings, squeaking",
        "a body rolling over on an old spring mattress"]),
    ("curtain_rings", "кольца занавески", [
        "curtain rings sliding along a metal rod",
        "a shower curtain pulled open on plastic hooks",
        "heavy velvet curtains drawn on wooden rings"]),

    # --- город и транспорт ---
    ("siren_police", "сирена полиции", [
        "a police siren approaching fast and passing by in the street",
        "a police car siren wailing in the distance across the city",
        "a short police siren whoop as a patrol car pulls someone over"]),
    ("siren_ambulance", "сирена скорой", [
        "an ambulance siren passing through traffic",
        "an ambulance siren fading away down a long avenue",
        "an ambulance stopping, its siren cut off mid-wail"]),
    ("fire_truck", "пожарная машина", [
        "a fire engine horn blaring and siren wailing as it races past",
        "a fire truck air horn blasting twice at an intersection",
        "a fire engine braking hard with hissing air brakes"]),
    ("car_alarm", "автосигнализация", [
        "a car alarm going off in a parking lot, then stopping",
        "a car alarm chirping twice as it is locked",
        "a car alarm cycling through loud tones down the street"]),
    ("car_skid", "визг тормозов", [
        "car tyres screeching in a hard emergency stop",
        "a car skidding around a corner with squealing tyres",
        "tyres skidding on wet asphalt and stopping just in time"]),
    ("car_crash", "авария машин", [
        "a car crash, crunching metal and breaking glass",
        "a car hitting a pole, metal buckling and glass tinkling down",
        "a low-speed fender bender, a dull thump and a cracking plastic bumper"]),
    ("truck_pass", "проезжает грузовик", [
        "a heavy truck rumbling past close by on a road",
        "a lorry downshifting with a roaring engine",
        "a truck with rattling empty trailer bouncing over a pothole"]),
    ("truck_reverse", "грузовик сдаёт назад", [
        "a truck reversing with a steady warning beep",
        "a garbage truck reversing, beeping, and its hydraulics whining",
        "a delivery van backing up, beeping, then stopping with a hiss"]),
    ("bus_door", "двери автобуса", [
        "bus doors opening with a hydraulic hiss",
        "a bus pulling away from a stop after its doors fold shut",
        "a trolleybus door closing with a pneumatic thump"]),
    ("subway_train", "метро", [
        "a subway train rushing into a station and braking",
        "subway doors chiming and sliding shut, then the train departing",
        "a metro train rattling through a tunnel"]),
    ("motorbike_start", "мотоцикл заводится", [
        "a motorbike kick-started, sputtering, then revving",
        "a sport motorcycle starting and revving hard twice",
        "a scooter engine starting with a high buzzy putter"]),
    ("helicopter", "вертолёт", [
        "a helicopter approaching and flying low overhead",
        "a helicopter hovering above a building, rotors thumping",
        "a distant helicopter passing over a city"]),
    ("plane_takeoff", "самолёт взлетает", [
        "a passenger jet roaring down a runway and taking off",
        "a small propeller plane taking off from a field",
        "a jet airliner climbing overhead and fading away"]),
    ("suitcase_wheels", "чемодан на колёсиках", [
        "a rolling suitcase pulled along a pavement, wheels clattering",
        "a suitcase rolled across a smooth airport floor",
        "a suitcase bumping down a few steps on its wheels"]),
    ("seatbelt", "пристёгивают ремень", [
        "a car seatbelt pulled out and clicked into the buckle",
        "an airplane seatbelt buckle clicked shut",
        "a seatbelt unbuckled and sliding back into its reel"]),
    ("shopping_cart", "магазинная тележка", [
        "a shopping cart rolling with a squeaky wheel",
        "shopping carts nested together with a metal crash",
        "a shopping cart rattling across a bumpy parking lot"]),
    ("barcode_scanner", "касса сканирует", [
        "a supermarket checkout scanner beeping item after item",
        "a till drawer opening after the last beep at the checkout",
        "a card payment terminal beeping and printing a receipt"]),
    ("turnstile", "турникет", [
        "a metro turnstile clacking as someone pushes through",
        "a metal rotating turnstile clicking round",
        "a ticket gate beeping and its paddles swinging open"]),
    ("traffic_crossing", "светофор пищит", [
        "a pedestrian crossing signal ticking fast for the green light",
        "a crossing signal beeping slowly while traffic waits",
        "a crossing signal clicking, then cars starting to move"]),
    ("skateboard", "скейтборд", [
        "a skateboard rolling on asphalt and clacking over a crack",
        "a skateboard ollie and a hard landing",
        "a skateboard rolling past and fading away"]),
    ("bicycle_ride", "едут на велосипеде", [
        "a bicycle rolling by with a ticking freewheel",
        "a bicycle braking with squeaky brakes",
        "an old bicycle rattling over cobblestones"]),
    ("taxi_door", "дверь такси", [
        "a taxi door slammed and the car pulling off",
        "a car rear door opened, someone getting in, door shut",
        "a minivan sliding door rolling shut with a clunk"]),
    ("garage_door", "ворота гаража", [
        "an automatic garage door rolling up with a motor rumble",
        "a metal roller shutter pulled down with a rattle and a bang",
        "an old wooden garage door swung open on rusty hinges"]),

    # --- работа и техника ---
    ("chainsaw", "бензопила", [
        "a chainsaw pull-started and revving",
        "a chainsaw cutting through a log",
        "a chainsaw idling, then revving into wood in the distance"]),
    ("lawn_mower", "газонокосилка", [
        "a petrol lawn mower pull-started and mowing grass",
        "a lawn mower passing close by and moving away",
        "a hand push reel mower whirring over grass"]),
    ("drill_power", "дрель", [
        "an electric drill boring into a wall",
        "a cordless screwdriver driving a screw in short bursts",
        "a hammer drill pounding into concrete"]),
    ("jackhammer", "отбойный молоток", [
        "a jackhammer breaking up concrete on a street",
        "a distant jackhammer rattling in short bursts",
        "a jackhammer stopping and chunks of concrete falling"]),
    ("welding", "сварка", [
        "an arc welder crackling and sizzling on steel",
        "a welding torch hissing and sputtering",
        "sparks from an angle grinder cutting metal"]),
    ("construction", "стройка", [
        "a construction site with hammering, a crane beeping and metal clanging",
        "steel pipes dropped onto a pile at a building site",
        "scaffolding poles clanking as they are put together"]),
    ("hammer_metal", "молотком по металлу", [
        "a hammer striking a metal sheet",
        "a hammer tapping a steel pipe",
        "a sledgehammer hitting an iron plate"]),
    ("crowbar", "отжимают ломом", [
        "a crowbar prying open a wooden crate with creaking nails",
        "a crowbar forcing a door frame until it cracks",
        "a crowbar dropped on a concrete floor with a clang"]),
    ("hose_water", "шланг", [
        "a garden hose spraying water onto plants",
        "a hose nozzle switched to a jet hitting a wall",
        "water from a hose filling a metal bucket"]),
    ("sprinkler", "поливалка", [
        "a garden sprinkler ticking as it rotates",
        "a lawn sprinkler hissing over grass",
        "an oscillating sprinkler swishing back and forth"]),
    ("photocopier", "копировальный аппарат", [
        "a photocopier scanning and printing pages",
        "a printer feeding paper and printing a page",
        "a fax machine screeching and printing a page"]),
    ("shredder", "шредер", [
        "a paper shredder chewing through sheets",
        "a shredder jamming and reversing",
        "a shredder grinding a credit card"]),
    ("office_chair", "офисное кресло", [
        "an office chair rolling across a floor on casters",
        "someone dropping into a creaky swivel chair",
        "an office chair spinning and the gas lift hissing"]),
    ("laptop_close", "закрывают ноутбук", [
        "a laptop lid shut with a soft click",
        "a laptop opening and a startup chime",
        "a laptop set down on a wooden desk"]),
    ("mouse_scroll", "щелчки мыши", [
        "a computer mouse clicked rapidly",
        "a mouse wheel scrolled with fine clicks",
        "a double click and a drag across a mousepad"]),
    ("phone_screen", "телефон: тап и уведомление", [
        "a smartphone notification ping on a table",
        "a smartphone camera shutter click",
        "a phone vibrating twice on a wooden table and picked up"]),
    ("headphones", "наушники", [
        "headphones taken off and put down on a desk",
        "earbuds case snapping shut",
        "a headphone cable unplugged with a click"]),

    # --- больница ---
    ("heart_monitor", "кардиомонитор", [
        "a hospital heart monitor beeping steadily",
        "a heart monitor beeping faster and faster",
        "a heart monitor flatlining with a continuous tone"]),
    ("hospital_bed", "больничная кровать", [
        "a hospital bed rolled down a corridor",
        "a hospital bed rails clicked up into place",
        "a hospital trolley rattling with instruments"]),
    ("pill_bottle", "баночка таблеток", [
        "pills rattling in a plastic bottle",
        "a pill bottle cap unscrewed and pills poured into a hand",
        "a blister pack of tablets popped open"]),
    ("wheelchair", "инвалидная коляска", [
        "a wheelchair rolling on a linoleum floor",
        "a wheelchair brakes clicked on",
        "a wheelchair bumping over a doorway threshold"]),
    ("crutches", "костыли", [
        "someone walking on crutches across a hard floor",
        "crutches leaned against a wall and sliding down with a clatter",
        "crutches tapping down a few stairs"]),
    ("inhaler", "ингалятор", [
        "an asthma inhaler shaken and puffed",
        "a medical spray bottle pumped twice",
        "a nebuliser mask hissing"]),

    # --- спорт и игры ---
    ("ball_kick", "бьют по мячу", [
        "a football kicked hard",
        "a football hitting the goal post with a clang",
        "a ball bouncing off a wall in a schoolyard"]),
    ("basketball", "баскетбольный мяч", [
        "a basketball dribbled on a gym floor with sneakers squeaking",
        "a basketball swishing through a net",
        "a basketball bouncing on outdoor asphalt"]),
    ("tennis", "теннис", [
        "a tennis ball hit back and forth",
        "a tennis serve smacking the ball",
        "a tennis ball bouncing and rolling away"]),
    ("referee_whistle", "свисток судьи", [
        "a referee whistle blown sharply three times",
        "a long whistle blast ending a game",
        "a coach whistle blowing in a gym"]),
    ("boxing_punch", "удар по груше", [
        "punches thudding into a heavy boxing bag",
        "boxing gloves hitting focus mitts",
        "a speed bag rattling rapidly"]),
    ("weights_drop", "штанга", [
        "a barbell dropped onto a gym floor",
        "dumbbells clanking together on a rack",
        "weight plates sliding onto a bar"]),
    ("chess_move", "шахматы", [
        "a wooden chess piece placed on a board",
        "a chess clock pressed",
        "chess pieces poured back into a wooden box"]),
    ("dominoes", "домино", [
        "domino tiles slapped onto a table",
        "dominoes shuffled face down on a table",
        "a row of dominoes falling one after another"]),
    ("toy_wind_up", "заводная игрушка", [
        "a wind-up toy being wound and buzzing as it walks",
        "a toy car with a pull-back motor zipping across the floor",
        "a tin toy drum monkey clattering"]),
    ("swing_creak", "качели", [
        "a playground swing creaking back and forth",
        "rusty swing chains squeaking slowly",
        "a wooden swing rope creaking on a tree branch"]),
    ("dive_splash", "ныряют в воду", [
        "someone diving into a pool with a deep splash",
        "a child cannonballing into a lake",
        "a person jumping off a pier into the sea"]),
    ("swimming", "плывут", [
        "a swimmer doing crawl strokes in a pool",
        "someone swimming slowly in a quiet lake",
        "a dog paddling through water"]),
    ("fishing_reel", "катушка удочки", [
        "a fishing rod cast with the reel whizzing",
        "a fishing reel cranked in steadily",
        "a fish splashing as it is pulled out of the water"]),
    ("skis", "лыжи", [
        "cross-country skis gliding on snow",
        "downhill skis carving on hard snow",
        "skis clattering as they are set down"]),
    ("ice_skates", "коньки", [
        "ice skates carving on an ice rink",
        "a skater stopping hard with a spray of ice",
        "ice skates scraping slowly on a frozen pond"]),
    ("sled", "санки", [
        "a wooden sled sliding down a snowy hill",
        "sled runners scraping on icy snow",
        "a sled pulled over snow by a rope"]),
    ("snowball", "снежок", [
        "a snowball thrown and hitting a wall",
        "a snowball packed between gloved hands",
        "a snowball splattering on a coat"]),
    ("snow_shovel", "чистят снег лопатой", [
        "a shovel scraping snow off a pavement",
        "a snow shovel lifting and throwing snow",
        "a plastic shovel scraping ice"]),

    # --- праздник ---
    ("fireworks", "фейерверк", [
        "fireworks bursting in the sky",
        "a firework rocket whistling up and exploding",
        "distant fireworks crackling over a town"]),
    ("firecracker", "петарда", [
        "a string of firecrackers popping",
        "a single firecracker bang in a courtyard",
        "firecrackers going off in the distance"]),
    ("balloon_pop", "лопается шарик", [
        "a balloon popping",
        "a balloon slowly deflating with a squeal",
        "a balloon rubbed and squeaking"]),
    ("party_popper", "хлопушка", [
        "a party popper going off with paper confetti",
        "a champagne bottle opened at a party",
        "confetti and streamers falling"]),

    # --- тело ---
    ("knuckles_crack", "хрустят костяшки", [
        "knuckles cracked one by one with sharp little pops in a quiet room",
        "a man cracking his neck by turning his head, two dull pops",
        "interlocked fingers stretched forward and cracking all at once"]),
    ("finger_snap", "щелчок пальцами", [
        "a single crisp finger snap in a quiet room",
        "fingers snapping twice in quick succession close to the ear",
        "one loud finger snap echoing in an empty hall"]),
    ("nails_tap", "постукивают ногтями", [
        "fingernails tapping impatiently on a wooden table in a steady rhythm",
        "long fingernails drumming on a glass tabletop",
        "fingertips slowly tapping on a closed book"]),
    ("pen_click", "щёлкают ручкой", [
        "a ballpoint pen clicked nervously again and again",
        "a pen cap snapped on and pulled off several times",
        "a pen tapped against the edge of a notebook"]),
    ("apple_bite", "кусают яблоко", [
        "a crisp apple bitten with a loud crunch and chewed",
        "a fresh carrot snapped in half and crunched",
        "a dry cracker bitten and crunched slowly"]),
    ("slurp", "прихлёбывают", [
        "hot soup slurped noisily from a spoon",
        "hot tea sipped and slurped from a cup with a satisfied breath",
        "noodles slurped quickly from a bowl"]),
    ("burp", "отрыжка", [
        "a short stifled burp",
        "a long loud burp after a fizzy drink",
        "a small polite burp behind a hand"]),
    ("nose_blow", "сморкаются", [
        "a man blowing his nose loudly into a cloth handkerchief",
        "a long nose blow into a paper tissue",
        "a sniff followed by a short nose blow"]),
    ("panting", "запыхавшись", [
        "a man panting hard after running, slowly catching his breath",
        "a woman out of breath after climbing many stairs",
        "short fast panting of someone hiding and trying to stay quiet"]),
    ("teeth_grind", "скрежет зубов", [
        "teeth grinding hard in anger",
        "teeth grinding slowly in sleep",
        "a jaw clenched with a creaking grind of teeth"]),
    ("body_scratch", "чешутся", [
        "fingernails scratching dry skin on an arm",
        "a rough stubbly beard scratched slowly",
        "a scalp scratched through thick hair"]),
    ("clothes_dress", "шуршит платье", [
        "a long silk evening dress rustling as a woman walks",
        "stiff petticoats rustling under a wide skirt",
        "a taffeta skirt swishing as someone turns quickly"]),
    ("leather_creak", "скрипит кожа", [
        "a stiff leather jacket creaking as arms move",
        "an old leather armchair creaking as someone settles into it",
        "a leather belt pulled tight and creaking through the buckle"]),

    # --- природа, животные ---
    ("dog_whine", "собака скулит", [
        "a dog whining at a closed door, scratching at the wood",
        "a small puppy whimpering in a box",
        "a large dog whining softly and sighing"]),
    ("dog_small_bark", "лает маленькая собака", [
        "a small terrier yapping excitedly",
        "a little dog barking behind an apartment door",
        "a tiny dog growling and then yapping twice"]),
    ("cat_purr", "кошка мурлычет", [
        "a cat purring loudly close to the ear",
        "a cat purring while kneading a soft blanket",
        "a small kitten purring quietly"]),
    ("cat_meow", "кошка мяукает", [
        "a cat meowing loudly at a door to be let in",
        "a tiny kitten mewing",
        "an old cat yowling at night in a yard"]),
    ("wings_flap", "хлопают крылья", [
        "a large bird flapping its wings hard and taking off from a branch",
        "a flock of pigeons taking off at once in a flurry of wings",
        "a single small bird fluttering past"]),
    ("eagle_cry", "крик хищной птицы", [
        "a hawk crying high above an open field",
        "an eagle screeching over a mountain valley",
        "a falcon calling twice from a cliff"]),
    ("parrot", "попугай", [
        "a parrot squawking loudly in a cage",
        "a parrot whistling a short trill",
        "a small budgie chirping and chattering"]),
    ("bear_growl", "рычит медведь", [
        "a bear growling low and close",
        "a bear roaring in a pine forest",
        "a bear snorting and huffing while sniffing around"]),
    ("snake_hiss", "змея шипит", [
        "a snake hissing sharply",
        "a rattlesnake shaking its rattle",
        "a snake sliding through dry leaves"]),
    ("donkey", "осёл", [
        "a donkey braying loudly in a farmyard",
        "a donkey braying far away across fields",
        "a mule snorting and then braying"]),
    ("lamb_bleat", "ягнёнок", [
        "a young lamb bleating for its mother",
        "a baby goat crying in a pen",
        "a lamb bleating far off on a hillside"]),
    ("bull", "бык", [
        "a bull bellowing loudly",
        "a bull snorting and stamping its hoof",
        "a bull lowing deep inside a barn"]),
    ("bat_flutter", "летучая мышь", [
        "bats fluttering and squeaking in a cave",
        "a single bat squeaking high",
        "a bat flitting past close to the ear"]),
    ("woodpecker", "дятел", [
        "a woodpecker drumming fast on a tree trunk",
        "a woodpecker tapping slowly at bark",
        "a woodpecker drumming far off in a forest"]),
    ("cuckoo_bird", "кукушка", [
        "a cuckoo calling several times in a forest",
        "a cuckoo calling far away across a lake",
        "a cuckoo calling close by in a garden"]),
    ("nightingale", "соловей", [
        "a nightingale singing a long trilling song at night",
        "a nightingale singing in a garden at dusk",
        "a nightingale singing far off in bushes by a river"]),
    ("hedgehog", "ёжик", [
        "a hedgehog snuffling through dry leaves",
        "a hedgehog huffing and puffing",
        "a hedgehog rustling under a bush at night"]),
    ("beetle_buzz", "жук жужжит", [
        "a big beetle buzzing past and bumping into a window",
        "a bumblebee hovering around a flower",
        "a cockchafer droning past at dusk"]),
    ("rain_umbrella", "дождь по зонту", [
        "rain pattering on an open umbrella",
        "heavy rain drumming on an umbrella",
        "an umbrella shaken off, drops falling on a floor"]),
    ("rain_leaves", "дождь по листве", [
        "rain falling on leaves in a forest",
        "drops dripping from trees after a shower",
        "a light rain on a garden and a wooden bench"]),
    ("puddle_car", "машина по луже", [
        "a car driving through a deep puddle with a big splash",
        "a car passing slowly on a wet road",
        "a bus splashing water against a kerb"]),
    ("ice_river", "ломается лёд на реке", [
        "ice cracking loudly on a frozen river",
        "ice floes grinding against each other on a river",
        "a sheet of ice breaking with a deep crack"]),
    ("earthquake", "землетрясение", [
        "an earthquake rumble with dishes rattling in a cupboard",
        "the ground shaking and objects falling off shelves",
        "windows rattling in a short tremor"]),
    ("avalanche", "лавина", [
        "an avalanche roaring down a mountain",
        "a mass of snow sliding off a roof with a heavy thump",
        "a distant avalanche rumbling across a valley"]),
    ("rockslide", "камнепад", [
        "rocks tumbling and bouncing down a slope",
        "a rockslide crashing into a gorge",
        "small stones rattling down a cliff face"]),

    # --- оружие современное ---
    ("machine_gun", "очередь из автомата", [
        "an assault rifle firing a long burst outdoors",
        "distant automatic gunfire across a city",
        "a submachine gun firing a short burst indoors"]),
    ("pistol_silenced", "выстрел с глушителем", [
        "a single silenced pistol shot in a room",
        "two quick silenced shots",
        "a silenced shot and a brass casing bouncing on a floor"]),
    ("grenade", "взрыв гранаты", [
        "a hand grenade exploding nearby, dirt raining down",
        "a distant grenade blast",
        "a grenade pin pulled, a short pause, then an explosion"]),
    ("handcuffs", "наручники", [
        "handcuffs ratcheting shut on a wrist",
        "handcuffs unlocked with a small key",
        "handcuffs rattling against a metal bar"]),

    # --- фэнтези и сказка ---
    ("magic_spell", "заклинание", [
        "a magic spell cast with a bright shimmering whoosh",
        "a soft magical chime rising into a whoosh",
        "a powerful spell crackling with energy and a deep boom"]),
    ("magic_sparkle", "волшебные искры", [
        "magic sparkles twinkling like tiny bells",
        "fairy dust tinkling as it falls",
        "a glittering shimmer fading away"]),
    ("portal_open", "открывается портал", [
        "a magic portal tearing open with a deep whoosh",
        "a swirling vortex opening and humming",
        "a portal snapping shut with a sudden thump"]),
    ("dragon_roar", "рёв дракона", [
        "a huge dragon roaring close by",
        "a dragon breathing a jet of fire",
        "a dragon roaring far away over mountains"]),
    ("monster_growl", "рычит чудовище", [
        "a large monster growling deep in a cave",
        "a creature snarling and snapping its jaws",
        "a beast roaring in a dark forest"]),
    ("potion_bubble", "кипит зелье", [
        "a thick potion bubbling in an iron cauldron",
        "a cauldron boiling over a fire",
        "a potion poured into a small glass vial"]),
    ("crystal_ring", "звон кристалла", [
        "a crystal struck and ringing clearly",
        "a wet finger rubbing a crystal glass rim",
        "a crystal shattering into tiny pieces"]),
    ("wings_dragon", "огромные крылья", [
        "huge leathery wings beating overhead",
        "giant wings flapping and a heavy creature landing",
        "great wings beating far away"]),

    # --- хоррор ---
    ("ghost_wail", "вой призрака", [
        "a ghostly wail drifting through an empty house",
        "a low ghostly moan in a cellar",
        "an eerie wail far away in the night"]),
    ("coffin_lid", "крышка гроба", [
        "a wooden coffin lid creaking slowly open",
        "a stone sarcophagus lid scraping aside",
        "a wooden coffin lid slammed shut"]),
    ("scratching_wall", "скребутся в стене", [
        "something scratching inside a wall",
        "fingernails scratching slowly down a wooden door",
        "claws scratching on floorboards"]),
    ("chains_drag", "волокут цепи", [
        "heavy chains dragged across a stone floor",
        "chains rattling in a dungeon",
        "a chain dragged slowly over wooden boards"]),
    ("heartbeat_slow", "медленное сердцебиение", [
        "a slow heavy heartbeat",
        "a heartbeat slowing down to silence",
        "a muffled heartbeat heard from inside"]),
    ("music_box_slow", "заедающая музыкальная шкатулка", [
        "an old music box winding down, its tune slowing",
        "a broken music box skipping notes",
        "a music box playing slowly in an empty room"]),

    # --- фантастика ---
    ("laser_shot", "лазерный выстрел", [
        "a sci-fi laser pistol firing a single shot",
        "rapid laser blasts in a firefight",
        "a laser beam charging up and firing"]),
    ("airlock", "шлюз", [
        "a spaceship airlock door opening with a hiss of air",
        "an airlock pressurising with a long hiss and a clunk",
        "a sci-fi door sliding open with a pneumatic swish"]),
    ("robot_servo", "сервоприводы робота", [
        "robot servos whirring as a mechanical arm moves",
        "a heavy robot walking with clanking metal steps",
        "a small robot turning its head with a whirr"]),
    ("computer_beeps", "компьютер пищит", [
        "a retro computer beeping and chirping",
        "a computer terminal beeping as data loads",
        "a single error buzz from a control panel"]),
    ("teleport", "телепортация", [
        "a sci-fi teleport whoosh with a rising shimmer",
        "a teleport effect with a fizzing sparkle",
        "a teleport arrival with a sudden pop"]),
    ("spaceship_flyby", "пролетает корабль", [
        "a spaceship flying by fast overhead",
        "a spaceship landing with thrusters roaring",
        "a spaceship taking off and fading into the sky"]),
]
