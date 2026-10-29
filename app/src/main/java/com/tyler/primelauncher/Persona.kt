package com.tyler.primelauncher

/**
 * 0.1.15 companion: each theme is a palette + a [Persona]. The persona is the orb's voice (line bank, case, reveal style),
 * its activity set, its idle mannerisms and how big it reacts. To add a theme, write one more object and register it in
 * [Personas.of]; the scheduler, bubble placement, interrupt rule and rate limits are shared.
 *
 * Every line is local and offline. `{name}` is the name set in Settings (blank = the line drops it).
 */
enum class Cat { GREETING, BREAK, CLEAN, CAR, JOKE, NOTIFY, MUSING, REACT }

/** 0.1.17 PA = the ODST public-address panel: the 4-note chime, then the text types out. */
enum class Reveal { GLYPH_DECODE, WORD_SLAM, PA }

interface Persona {
    val id: String
    val upper: Boolean
    val reveal: Reveal
    /** 0.3 deadpan ... 1.5 hype: multiplies hop / squash / roll of the shared moments. */
    val reactionScale: Double
    val lines: Map<Cat, List<String>>
    /** Setup / punchline pairs. */
    val jokes: List<Pair<String, String>>
    /** Random pool (activities + bubbles) for [Scheduler]. Activity ids are [Antics] ids. */
    val pool: List<PoolItem>
    /** Idle mannerism antic ids and how often they come (seconds between, min..min+span). */
    val quirks: List<String>
    val quirkMinS: Double; val quirkSpanS: Double
    /** 0.1.18 idle-beat micro-animations ([Micros]); the old [quirks] join this rotation too. */
    val micros: List<String>
    val badges: Map<Cat, String>
    /** Rare soft lines in a second voice (ODST's blue Vergil moments); empty = none. Never upper-cased. */
    val vergil: List<String> get() = emptyList()
    fun cased(s: String) = if (upper) s.uppercase() else s.lowercase()
}

object Personas {
    fun of(theme: String): Persona = when (theme) { "neon" -> NeonPersona; "odst" -> OdstPersona; "holiday" -> HolidayPersona; else -> MatrixPersona }
    val all: List<Persona> get() = listOf(MatrixPersona, NeonPersona, OdstPersona, HolidayPersona)

    /** Fill the {name} placeholder (blank name = the line drops it). */
    fun fill(line: String, name: String): String {
        var s = line
        val n = name.trim()
        s = if (n.isNotEmpty()) s.replace(Regex("\\{name\\|[^}]*\\}"), n).replace("{name}", n)
        else s.replace(Regex("\\{name\\|([^}]*)\\}"), "$1").replace(", {name},", "").replace(", {name}", "").replace("{name}, ", "").replace(" {name}", "").replace("{name}", "")
        return s.replace(Regex("\\s{2,}"), " ").trim()
    }

    /** A trailing symbol on a Neon line becomes the bubble badge instead of inline text (fonts vary on emoji). */
    private val SYMBOLS = setOf("⚡", "★", "✉", "⚙", "♪", "✦", "☀", "?")
    fun splitBadge(line: String): Pair<String, String?> {
        val t = line.trim(); val last = t.substringAfterLast(' ', "")
        return if (last in SYMBOLS && last != "?") t.dropLast(last.length).trim() to last else t to null
    }
}

private fun act(id: String, w: Double, cd: Int, from: Double = 0.0, to: Double = 24.0, rainOrNight: Boolean = false) = PoolItem(id, Kind.ACT, w, cd, from, to, rainOrNight = rainOrNight)
private fun bub(id: String, w: Double, cd: Int, from: Double = 0.0, to: Double = 24.0, weekendOnly: Boolean = false, reminder: Boolean = false) =
    PoolItem(id, Kind.BUB, w, cd, from, to, weekendOnly, reminder)

/** Bubble pool ids -> line category. */
object PoolIds {
    const val BREAK = "bub.break"; const val CLEAN = "bub.clean"; const val CAR = "bub.car"; const val JOKE = "bub.joke"; const val MUSING = "bub.musing"
    fun cat(id: String): Cat? = when (id) { BREAK -> Cat.BREAK; CLEAN -> Cat.CLEAN; CAR -> Cat.CAR; JOKE -> Cat.JOKE; MUSING -> Cat.MUSING; else -> null }
    val SHARED_BUBBLES = listOf(
        bub(BREAK, 3.0, 240, 9.0, 21.0, reminder = true), bub(CLEAN, 2.0, 2880, 17.0, 22.0, reminder = true),
        bub(CAR, 1.0, 10080, 10.0, 18.0, weekendOnly = true, reminder = true))
}

object MatrixPersona : Persona {
    override val id = "matrix"; override val upper = false; override val reveal = Reveal.GLYPH_DECODE; override val reactionScale = 0.55
    override val quirks = listOf(Antics.DEJAVU, Antics.STARE); override val quirkMinS = 45.0; override val quirkSpanS = 60.0
    override val micros = Micros.MATRIX + quirks
    override val badges = emptyMap<Cat, String>()
    override val pool = listOf(
        act(Antics.SCROLL, 3.0, 90), act(Antics.FISHING, 3.0, 120), act(Antics.ZEN, 2.0, 180, 17.0, 23.0), act(Antics.GARDEN, 2.0, 1440, 7.0, 11.0),
        act(Antics.STARGAZER, 2.0, 1440, 20.0, 23.5), act(Antics.FLASHCARDS, 2.0, 240), act(Antics.TIDY, 1.0, 240),
        act(Antics.RAINCATCH, 1.0, 120)) + PoolIds.SHARED_BUBBLES + listOf(bub(PoolIds.JOKE, 2.0, 240), bub(PoolIds.MUSING, 2.0, 180))
    override val lines = mapOf(
        Cat.GREETING to listOf(
            "wake up, {name}. follow the white rabbit... into today.", "morning. the day is already written. let's read it.",
            "you're awake. good. the matrix has you, but so does breakfast.", "red pill: get up. blue pill: five more minutes. choose wisely.",
            "morning, {name}. i've been expecting you.", "the sun rose. as foretold.", "déjà vu? no. it's just another morning. a good one.",
            "morning. morpheus would say: free your mind. i say: coffee first.", "you slept. i watched the code. nothing broke.", "a new day loads. 100%.",
            "morning, mr. ander... {name|anderson}. sorry. old habits.", "knock, knock, {name}. today is here."),
        Cat.BREAK to listOf(
            "step out of the matrix. stretch.",
            "unplug for five minutes, {name}.",
            "even neo needed a break. take one.",
            "free your mind. rest your eyes.",
            "look away from the code. far away.",
            "a short break. the system waits.",
            "stand up. the spoon can wait.",
            "breathe. the rain keeps falling.",
            "five minutes offline. i'll watch.",
            "blink. stretch. reboot.",
            "the oracle says: walk around.",
            "pause the program. you earned it."),
        Cat.CLEAN to listOf(
            "the room... is not clean, {name}. there is no spoon. there is laundry.", "there is no spoon. there is, however, a pile of laundry.",
            "i've seen this mess before. déjà vu... or tuesday.", "what is real? the clothes on that chair are very real.",
            "the oracle says you'll clean your room. she's usually right.", "blue pill: ignore the room. red pill: ten minutes. see how deep the closet goes.",
            "even zion has chores, {name}.", "order is a choice. tonight, choose it.", "the room is a simulation of chaos. let's end the simulation.",
            "agents can't find you in a clean room. probably.", "dodge this: one shirt, into the hamper.", "ten minutes. the room will thank you."),
        Cat.CAR to listOf(
            "even the nebuchadnezzar needed repairs. your car is due for service.", "the machine needs maintenance. so does the one in the driveway.",
            "the oracle foresees an oil change. soon.", "a warning light, {name}, is just the car's déjà vu. listen to it.",
            "tires. pressure. check them, and you'll be ready to dodge bullets.", "morpheus kept his ship running. keep yours.",
            "a small fix now. a big glitch avoided later.", "the road is a long program. your car needs an update.",
            "when was the last oil change? i remember everything. you might not.", "the architect designed engines to be serviced. go along with the design.",
            "sentinels hunt neglected cars. probably. get it checked.", "book the service. the future thanks you."),
        Cat.NOTIFY to listOf(
            "knock, knock. a message has arrived. shall i read it?", "a message arrived. shall i read it to you?", "someone is reaching through the wire. want to hear it?",
            "follow the white rabbit? one new message.", "incoming transmission. read it?", "the phone is ringing in the matrix. a message. listen?",
            "new message. i can read it, or you can. your choice.", "a signal from outside. shall i decode it?", "mr. anderson... you have mail.",
            "one new message. take the red pill and hear it?", "a message waits. no rush. or some rush.", "a new message. open it in message prime?"),
        Cat.MUSING to listOf(
            "counting the 1s in the rain again. 4,812 so far.", "sometimes i wonder if the rain dreams of me.", "déjà vu. a black cat went by. twice.",
            "what is real? the 0s, mostly.", "the oracle bakes cookies. i bake... glyphs.", "i've been staring at the code. it's staring back.",
            "zion is far. this home screen is cozy.", "the architect would hate how messy my rain is.", "i dodged a pixel earlier. you missed it.",
            "everything that has a beginning has a reboot.", "quiet day. the matrix hums.", "there is no spoon. i checked again."),
        Cat.REACT to listOf("hm.", "i felt that.", "dodge this.", "...again?", "whoa.", "a glitch in me. pleasant.", "you found the white rabbit.",
            "i know kung fu. please don't test it.", "the oracle predicted that.", "nice. as foretold.", "inbox zero. there is no spoon, and no mail."))
    override val jokes = listOf(
        "why do programmers prefer dark mode?" to "because light attracts bugs, mr. anderson.", "why did neo skip the spoon aisle?" to "there is no spoon.",
        "red pill or blue pill?" to "trick question. it's the chill pill.", "how does the oracle stay so wise?" to "one cookie at a time.",
        "why did the agent go to therapy?" to "too many anderson issues.", "what do you call a déjà vu that repeats?" to "déjà vu.",
        "why don't skeletons fight agents?" to "they don't have the guts.", "what's morpheus's favorite drink?" to "matcha. it's the red pill of teas.",
        "how many programmers does it take to change a light bulb?" to "none. that's a hardware problem.", "why was the computer cold?" to "it left its windows open.",
        "i know kung fu." to "that's why my jokes have punch lines.", "what did zero say to eight?" to "nice belt.")
}

object NeonPersona : Persona {
    override val id = "neon"; override val upper = true; override val reveal = Reveal.WORD_SLAM; override val reactionScale = 1.15
    override val quirks = listOf(Antics.BOB, Antics.WINK, Antics.BOB); override val quirkMinS = 30.0; override val quirkSpanS = 45.0
    override val micros = Micros.NEON + listOf(Antics.BOB, Antics.WINK)
    override val badges = mapOf(Cat.GREETING to "⚡", Cat.BREAK to "★", Cat.CLEAN to "✦", Cat.CAR to "⚙", Cat.JOKE to "?", Cat.NOTIFY to "✉", Cat.MUSING to "♪", Cat.REACT to "★")
    override val pool = listOf(
        act(Antics.SKATE, 3.0, 90), act(Antics.DJ, 3.0, 120, 16.0, 23.5), act(Antics.ARCADE, 3.0, 120), act(Antics.GRAFFITI, 2.0, 180, 17.0, 23.5),
        act(Antics.SIGN, 2.0, 120), act(Antics.JUGGLE, 2.0, 90), act(Antics.RAINCATCH, 1.0, 120)) +
        PoolIds.SHARED_BUBBLES + listOf(bub(PoolIds.JOKE, 3.0, 180), bub(PoolIds.MUSING, 2.0, 180))
    override val lines = mapOf(
        Cat.GREETING to listOf(
            "WAKE UP, SAMURAI! WE'VE GOT A DAY TO BURN! ⚡", "GREETINGS, PROGRAM! NEW DAY LOADED.", "INSERT COIN TO START TODAY! ...JK, IT'S FREE. LET'S GO!",
            "1UP! YOU RESPAWNED. LET'S MAKE THIS RUN COUNT!", "MORNING, CHOOM! VIBES ARE PREEM TODAY ★", "GREAT SCOTT, YOU'RE UP EARLY!",
            "MORE HUMAN THAN HUMAN, AND MORE AWAKE THAN EVER!", "NEW DAY, NEW HIGH SCORE. PLAYER 1 READY?", "RISE AND GRIND, NEON LEGEND! ☀",
            "BOOT SEQUENCE COMPLETE. HYPE LEVEL: MAX!", "EXTRA LIFE GRANTED. SPEND IT WELL!", "GM GM GM! WHAT ARE WE CRUSHING TODAY?"),
        Cat.BREAK to listOf(
            "PAUSE MENU! STRETCH BREAK ★",
            "SAVE POINT! TAKE FIVE, CHOOM.",
            "COOLDOWN TIMER: 5 MIN. GO! ⚡",
            "EYES OFF THE GRID FOR A MINUTE!",
            "STRETCH COMBO: UP, DOWN, REPEAT!",
            "BREAK TIME! EVEN ARCADES CLOSE.",
            "STAND UP, PLAYER 1! ⚡",
            "REFILL YOUR HEALTH BAR. REST!",
            "INTERMISSION! BACK IN 5 ♪",
            "GREAT SCOTT! TAKE A BREATHER!",
            "LOOK AWAY! 20 FEET, 20 SECS.",
            "RECHARGE MODE: ACTIVATED ★"),
        Cat.CLEAN to listOf(
            "ROOM CHECK, CHOOM! IT'S GIVING CHAOS. 10-MIN TIDY? ✦", "CLEAN ROOM SPEEDRUN! INSERT COIN... TIMER STARTS NOW!",
            "THAT CHAIR PILE IS A BOSS LEVEL. TIME TO BEAT IT!", "ALL THOSE SOCKS WILL BE LOST IN TIME... LIKE TEARS IN RAIN. UNLESS YOU PICK 'EM UP!",
            "3 SONGS = 1 CLEAN ROOM. DEAL? ♪", "GREAT SCOTT! WHAT HAPPENED IN HERE?!", "TIDY UP, GLOW UP! PREEM ROOM INCOMING ✦", "DESK CLEAN = BRAIN CLEAN. 1UP!",
            "DUST BUNNIES ARE FORMING A GANG. STOP THEM!", "NEO-TOKYO IS CLEANER THAN THIS ROOM... ALMOST.", "QUICK RESET! IT'S NOT GAME OVER, IT'S CLEANUP TIME.",
            "CLEAN ROOM, CLEAR VIBES. LET'S GOOO!"),
        Cat.CAR to listOf(
            "ROADS? WE STILL NEED 'EM. BOOK THAT OIL CHANGE! ⚙", "GREAT SCOTT! YOUR CAR IS DUE FOR SERVICE!", "EVEN A DELOREAN NEEDS A TUNE-UP. PIT STOP?",
            "PIT STOP TIME! OIL, TIRES, VIBES!", "YOUR RIDE NEEDS SOME LOVE, CHOOM. PREEM MAINTENANCE!", "TIRE PRESSURE CHECK. 2 MINUTES, EXTRA LIFE FOR YOUR TIRES!",
            "NO ONE WANTS A GAME OVER ON THE HIGHWAY. BOOK SERVICE!", "88 MPH IS COOL. A CHECK ENGINE LIGHT IS NOT ⚙", "WEEKEND QUEST: GET THE CAR CHECKED!",
            "LIGHT CYCLES NEED MAINTENANCE TOO. END OF LINE.", "YOUR RIDE SAID \"HELP ME.\" I HEARD IT.", "KEEP IT RUNNING SMOOTH, LIKE YOUR PLAYLIST ♪"),
        Cat.NOTIFY to listOf(
            "GREETINGS, PROGRAM! NEW MESSAGE. WANT ME TO READ IT? ✉", "INCOMING TRANSMISSION, CHOOM! READ IT?", "DING DING! PLAYER 2 HAS ENTERED THE CHAT!",
            "NEW MESSAGE JUST DROPPED! WANT THE PLAY-BY-PLAY? ✉", "PING! TAP ME, I'LL READ IT OUT!", "GREAT SCOTT, A MESSAGE! WANT TO HEAR IT?",
            "YOUR PHONE'S POPPIN'! ONE NEW MESSAGE ✉", "FRESH MESSAGE, STRAIGHT FROM THE GRID!", "1UP! YOU GOT A MESSAGE. READ IT?",
            "SOMEONE SLID INTO YOUR INBOX. PREEM!", "NEW MESSAGE IN THE HOUSE. TAP TO HEAR IT!", "MESSAGE RECEIVED. END OF LINE... OR TAP FOR MORE ✉"),
        Cat.MUSING to listOf(
            "I'VE SEEN THINGS YOU WOULDN'T BELIEVE... MOSTLY YOUR TABS.", "IF I HAD LEGS I'D BE SKATING NEO-TOKYO RIGHT NOW!",
            "MORE HUMAN THAN HUMAN? NAH. MORE NEON THAN NEON ✦", "I JUST BEAT MY OWN HIGH SCORE. IN MY HEAD.", "CHARGING MY HYPE... 87%. INSERT COIN TO CONTINUE.",
            "DO YOU THINK THE RAIN LIKES SYNTHWAVE? ♪", "I'M NOT GLITCHING, I'M DANCING.", "WHERE WE'RE GOING, WE DON'T NEED... HOME SCREENS. JK, I LOVE IT HERE.",
            "HOT TAKE: PINK + CYAN = PERFECTION.", "GREETINGS, PROGRAM. JUST CHECKING IN. END OF LINE.", "PREEM DAY, CHOOM. JUST SAYING.",
            "DAYDREAMING ABOUT ARCADE TOKENS AGAIN."),
        Cat.REACT to listOf("HEHE!", "AGAIN AGAIN!", "OKAY THAT TICKLES!", "PURRRR ✦", "WHEEE! 88 MPH!", "SICK MOVE, CHOOM!", "NEW HIGH SCORE!",
            "INBOX ZERO, HERO!", "GG! END OF LINE.", "PREEM!", "EXTRA LIFE EARNED!"))
    override val jokes = listOf(
        "WHY DID THE SCARECROW WIN AN AWARD?" to "HE WAS OUTSTANDING IN HIS FIELD! ♪", "WHY DID THE ARCADE MACHINE GO TO THERAPY?" to "IT HAD TOO MANY GAME OVERS!",
        "WHAT'S DOC BROWN'S FAVORITE SNACK?" to "TIME-BITS! GREAT SCOTT, THAT WAS BAD!", "WHY DON'T REPLICANTS PLAY HIDE AND SEEK?" to "THEY ALWAYS GET RETIRED EARLY!",
        "HOW DOES A PROGRAM SAY GOODBYE?" to "END OF LINE!", "WHY CAN'T BICYCLES STAND UP?" to "THEY'RE TWO TIRED... EVEN KANEDA'S!",
        "WHAT DO YOU CALL A FAKE NOODLE?" to "AN IMPASTA!", "WHY DID THE DJ GET COLD?" to "TOO MANY FANS! ♪", "I'M READING A BOOK ON ANTI-GRAVITY." to "IT'S IMPOSSIBLE TO PUT DOWN!",
        "WHY DID THE NEON SIGN GO TO SCHOOL?" to "TO GET BRIGHTER!", "WHAT'S A GHOST'S FAVORITE ARCADE GAME?" to "PAC-BOO!", "WHY DID THE PHONE WEAR GLASSES?" to "IT LOST ITS CONTACTS!")
}

/**
 * 0.1.17 ODST: a rigid municipal PA system that bends its civic phrasing to fit your little life (line bank from
 * the ODST personality notes and approved mockups). ALL CAPS, department headers on each panel, 4-note chime.
 * Original lines only. No goodnight category, same caps / quiet hours / mutes as every skin.
 */
object OdstPersona : Persona {
    override val id = "odst"; override val upper = true; override val reveal = Reveal.PA; override val reactionScale = 0.8
    override val quirks = listOf(Antics.STARE, OdstAntics.SCAN, OdstAntics.SCAN); override val quirkMinS = 40.0; override val quirkSpanS = 55.0
    override val micros = Micros.ODST + listOf(Antics.STARE)
    /** PA panel header per category. */
    override val badges = mapOf(Cat.GREETING to "\u25A3 MUNICIPAL NOTICE", Cat.BREAK to "\u25A3 WELLNESS DEPT.", Cat.CLEAN to "\u25A3 SANITATION", Cat.CAR to "\u25A3 MOTOR POOL",
        Cat.JOKE to "\u25A3 PUBLIC MORALE", Cat.NOTIFY to "\u25A3 MAIL SERVICES", Cat.MUSING to "\u25A3 CITY LOG", Cat.REACT to "\u25A3 RESIDENT SERVICES")
    override val pool = listOf(
        act(OdstAntics.ROUNDS, 3.0, 90), act(OdstAntics.TRAFFIC, 3.0, 120), act(OdstAntics.BILLBOARD, 2.0, 120), act(OdstAntics.TIDY, 2.0, 240, 17.0, 23.0),
        act(OdstAntics.SIGNAL, 2.0, 120), act(OdstAntics.RAINWATCH, 2.0, 180, rainOrNight = true)) +
        PoolIds.SHARED_BUBBLES + listOf(bub(PoolIds.JOKE, 2.0, 240), bub(PoolIds.MUSING, 1.0, 240))
    override val lines = mapOf(
        Cat.GREETING to listOf(
            "GOOD MORNING, RESIDENT. CITY SERVICES ARE ONLINE.", "ATTENTION: RESIDENT {name} HAS ENTERED THE DISTRICT. WELCOME BACK.",
            "RESIDENT DETECTED. MORALE LEVELS: TO BE DETERMINED.", "NOTICE: TODAY HAS BEEN SCHEDULED. ATTENDANCE IS ENCOURAGED.",
            "WELCOME HOME, RESIDENT. YOUR SECTOR MISSED YOU. STATISTICALLY.", "SYSTEMS NOMINAL. COFFEE STATUS: UNVERIFIED.",
            "GOOD AFTERNOON. THE CITY HAS KEPT YOUR SEAT WARM.", "RESIDENT ACCOUNT ACCESSED. NO FINES OUTSTANDING. WELL DONE.",
            "ATTENTION: A NEW DAY HAS BEEN ISSUED. PLEASE HANDLE WITH CARE.", "WELCOME BACK. ALL STREETLIGHTS WERE MAINTAINED IN YOUR ABSENCE.",
            "RESIDENT RETURN LOGGED. PERIMETER SECURE. MOSTLY.", "GOOD EVENING, RESIDENT. TRAFFIC WAS TERRIBLE. NOT YOUR FAULT."),
        Cat.NOTIFY to listOf(
            "INCOMING TRANSMISSION LOGGED. SHALL I READ IT, RESIDENT?", "ATTENTION: ONE (1) NEW MESSAGE AWAITS PROCESSING.", "CORRESPONDENCE RECEIVED. TAP TO HAVE IT READ ALOUD.",
            "NOTICE: A CONTACT IS ATTEMPTING COMMUNICATION.", "MAIL SORTING COMPLETE. ONE ITEM FLAGGED FOR YOU.", "A MESSAGE HAS ARRIVED IN YOUR SECTOR. INSPECT IT?",
            "DISPATCH ALERT: NEW MESSAGE. NO EMERGENCY DETECTED.", "TRANSMISSION ON CHANNEL ONE. SHALL I PATCH IT THROUGH?", "RESIDENT, YOU HAVE MAIL. THE CITY DID NOT PEEK.",
            "NEW MESSAGE QUEUED. READING SERVICE IS AVAILABLE NOW.", "ATTENTION: SOMEONE THOUGHT OF YOU. DETAILS ON REQUEST.", "COMMS LIGHT IS BLINKING. ACKNOWLEDGE?"),
        Cat.BREAK to listOf(
            "NOTICE: A REST BREAK IS ADVISED.",
            "PUBLIC NOTICE: STRETCH NOW.",
            "RECESS AUTHORIZED. 5 MINUTES.",
            "ATTENTION: EYES OFF SCREEN.",
            "BREAK ROOM IS OPEN, RESIDENT.",
            "CITY ADVISORY: STAND AND WALK.",
            "REST PERIOD HAS BEEN SCHEDULED.",
            "MANDATORY BREATHER. VOLUNTARY.",
            "NOTICE: SHOULDERS DOWN, PLEASE.",
            "PAUSE LOGGED. TAKE YOUR TIME.",
            "ADVISORY: LOOK OUT A WINDOW.",
            "WELLNESS PATROL: TAKE FIVE."),
        Cat.CLEAN to listOf(
            "ATTENTION RESIDENT: THIS ROOM IS NOT IN COMPLIANCE.", "SANITATION NOTICE: FLOOR VISIBILITY HAS DROPPED BELOW 40%.",
            "A 10-MINUTE TIDY HAS BEEN SCHEDULED. PARTICIPATION IS VOLUNTARY. ISH.", "LAUNDRY PILE HAS BEEN UPGRADED TO A LANDMARK. PLEASE ADDRESS.",
            "NOTICE: DISHES ARE FORMING A NEIGHBORHOOD. ZONING REVIEW REQUIRED.", "CLEAN SWEEP PROGRAM ACTIVE IN YOUR SECTOR TONIGHT.",
            "RESIDENT, YOUR DESK HAS REQUESTED CIVIL RIGHTS.", "ATTENTION: ONE TRASH RUN REDUCES CITY STRESS BY 12%.", "INSPECTION SCHEDULED IN 15 MINUTES. (I AM THE INSPECTOR.)",
            "PUBLIC WORKS SUGGESTS: BED, THEN FLOOR, THEN VICTORY.", "ROOM STATUS: LIVED-IN. TARGET STATUS: PRESENTABLE.", "THANK YOU FOR KEEPING YOUR DISTRICT BEAUTIFUL. START ANY TIME."),
        Cat.CAR to listOf(
            "VEHICLE SERVICES REMINDER: CHECK TIRE PRESSURE THIS WEEKEND.", "ATTENTION: OIL LEVELS HAVE NOT BEEN INSPECTED RECENTLY.",
            "NOTICE: WIPER BLADES ARE THE FIRST LINE OF DEFENSE AGAINST RAIN.", "MOTOR POOL ADVISORY: A WASH WOULD RESTORE CIVIC PRIDE.",
            "RESIDENT VEHICLE FLAGGED FOR A QUICK FLUIDS CHECK.", "TRAFFIC CONTROL RECOMMENDS: CHECK YOUR HEADLIGHTS AND BRAKE LIGHTS.",
            "ATTENTION: WINTER APPROACHES. INSPECT TIRES AND BATTERY.", "NOTICE: COOLANT IS COOL. CHECKING IT IS COOLER.",
            "PARKING ENFORCEMENT HAS NOTHING ON YOU. MAINTENANCE MIGHT.", "VEHICLE INTERIOR AUDIT: CRUMBS DETECTED. VACUUM ADVISED.",
            "SCHEDULED MAINTENANCE IS CHEAPER THAN UNSCHEDULED DRAMA.", "ATTENTION: YOUR CAR SAID THANK YOU LAST TIME. PROBABLY."),
        Cat.MUSING to listOf(
            "RAINFALL: STEADY. MOOD: ALSO STEADY. ACCEPTABLE.", "I COUNTED THE WINDOWS IN THIS SKYLINE. THE NUMBER IS PERSONAL.",
            "NOTICE: THE CITY NEVER SLEEPS. I, HOWEVER, MAY DOZE.", "ALL CAMERAS REPORT: NOTHING HAPPENING. LOVELY.",
            "SOMEWHERE A BUS IS ON TIME. I LIKE TO THINK ABOUT IT.", "PUBLIC ANNOUNCEMENT: YOU ARE DOING FINE.",
            // 0.1.17 additions in the same voice (the bank had 6; every skin carries at least 10)
            "STREET CLEANING IN SECTOR 4 IS GOING WELL. I WATCHED THE WHOLE THING.", "THE RAIN HAS BEEN ASSIGNED TO YOUR WINDOW. IT IS VERY DEDICATED.",
            "PIGEON CENSUS COMPLETE. RESULTS: MANY.", "A STREETLIGHT FLICKERED. I HAVE FORGIVEN IT.",
            "NOTICE: THE SKYLINE LOOKS PARTICULARLY TALL TONIGHT.", "CIVIC FACT: PUDDLES ARE TEMPORARY. SO ARE MONDAYS."),
        Cat.REACT to listOf("INQUIRY LOGGED.", "RESIDENT CONTACT ACKNOWLEDGED.", "THAT TICKLED. A REPORT HAS BEEN FILED.", "STATIC DISCHARGE DETECTED. PLEASANT.",
            "COMMENDATION ISSUED.", "PLEASE KEEP HANDS INSIDE THE DISTRICT.", "MORALE: IMPROVED.", "INBOX ZERO. THE CITY IS PROUD OF YOU.",
            "ATTENTION: YOUR INBOX IS IN FULL COMPLIANCE."))
    override val jokes = listOf(
        "WHY DID THE TRAFFIC LIGHT TURN RED?" to "YOU WOULD TOO IF PEOPLE WATCHED YOU CHANGE.", "HOW DOES THE CITY STAY CALM?" to "IT HAS EXCELLENT ROUNDABOUT COPING STRATEGIES.",
        "WHY WAS THE STREETLIGHT SO SMART?" to "IT WAS VERY BRIGHT FOR ITS AGE.", "WHAT DID THE POTHOLE SAY TO THE CAR?" to "I'VE BEEN WAITING TO MEET YOU. DEEPLY.",
        "WHY DON'T BUSES EVER GET LOST?" to "THEY ALWAYS FOLLOW THEIR ROUTINE.", "WHAT IS A GARBAGE TRUCK'S FAVORITE MUSIC?" to "HEAVY METAL. AND SOME LIGHT RECYCLING.",
        "WHY DID THE RAIN JOIN CITY COUNCIL?" to "TO MAKE SURE EVERYTHING WAS WELL-CONDUCTED.", "WHAT DO YOU CALL A SLEEPY BILLBOARD?" to "AN ADVERTIRED.",
        "WHY DID THE PARKING METER GO TO THERAPY?" to "IT HAD TOO MUCH TIME ON ITS HANDS.", "HOW DOES A CITY AI SAY HELLO?" to "WITH A FOUR-NOTE CHIME AND MILD JUDGMENT.",
        "WHY DID THE CROSSWALK BREAK UP WITH THE ROAD?" to "TOO MANY PEOPLE WALKING ALL OVER IT.", "WHAT'S A SEWER'S FAVORITE GAME?" to "HIDE AND LEAK.")
    override val vergil = listOf("long day. rest is recommended.", "you've been carrying a lot. it's okay to set some down.", "the city can wait. you matter more than its schedule.",
        "drink some water. i'll keep watch.", "it's late. the streets will still be here tomorrow.", "you did enough today. really.")
}
