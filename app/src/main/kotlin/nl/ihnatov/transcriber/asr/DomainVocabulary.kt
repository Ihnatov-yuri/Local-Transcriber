package nl.ihnatov.transcriber.asr

/**
 * One-tap domain vocabulary packs. Tap a button in Settings → Quick-fill
 * vocabulary, the chosen domain's terms get appended to the user's
 * [PromptStore.vocabulary] (which Gemma sees as a "spell these correctly
 * when heard" hint in the system prompt). Biases ASR away from common-
 * word homophones for industry-specific jargon Gemma's base model
 * doesn't know — drug INNs, Kubernetes-class coined names, Latin legal
 * phrases, etc.
 *
 * License posture: every term here is a name/factual reference drawn
 * from public terminologies. Names are not copyrightable; no definitions
 * are bundled. Sources (per [Pack.sourceCredit]):
 *   - Medical: US NLM MeSH (public domain), WHO ICD-10 chapter headers,
 *     RxNorm dispensed-drug names.
 *   - IT: ACM CCS 2012 taxonomy, CNCF Landscape (Apache 2.0), Wikipedia
 *     "List of …" pages (CC-BY-SA — term names only).
 *   - Construction: CSI MasterFormat 2020 division titles, ASTM material
 *     designators, NEN/QCS/ДБН public term lists.
 *   - Legal: Cornell LII Wex term headings (CC-BY-NC-SA — names only,
 *     NO definitions), US Courts glossary, Al-Meezan (Qatar), rada.gov.ua,
 *     rechtspraak.nl.
 *   - Finance: SEC/Investor.gov (public domain), BIS, AFM, NBU, QFMA.
 *
 * UI implication (per research): for IT/finance/legal in non-English
 * languages, speakers heavily code-switch to English. We deliberately
 * mix English and local-script terms in those packs because matching
 * what people *actually say* gives more lift than strict per-language
 * separation.
 */
object DomainVocabulary {

    enum class Domain(val id: String, val displayName: String, val description: String) {
        Medical("medical", "Medical",
            "Drug INNs, anatomy, conditions, procedures. Biggest ASR lift — most drug names are out-of-vocab."),
        It("it", "IT / Software",
            "Frameworks, protocols, tool names (Kubernetes, PostgreSQL, GraphQL…)."),
        Construction("construction", "Construction",
            "Materials, equipment, building codes (rebar, shotcrete, HVAC…)."),
        Legal("legal", "Legal",
            "Latin terms, court terminology, doctrines (res judicata, voir dire…)."),
        Finance("finance", "Finance",
            "Instruments, regulations, market terms (EBITDA, SOFR, swaption…)."),
    }

    data class Pack(
        val domain: Domain,
        val sourceCredit: String,
        /** Map of ISO-639-1 language code → list of terms. */
        val terms: Map<String, List<String>>,
    ) {
        /**
         * Resolve terms to inject for a given language set. Always includes
         * English: per the research above, speakers in NL/UK/AR overwhelmingly
         * code-switch to English for IT/finance/legal jargon, so omitting
         * English would underbias the recognizer.
         */
        fun resolve(languages: Set<String>): List<String> {
            val want = languages + "en"
            val out = LinkedHashSet<String>()
            for (lang in want) terms[lang]?.let(out::addAll)
            return out.toList()
        }
    }

    val packs: Map<Domain, Pack> = mapOf(
        Domain.Medical to Pack(
            domain = Domain.Medical,
            sourceCredit = "MeSH (US NLM, public domain), WHO ICD-10, RxNorm.",
            terms = mapOf(
                "en" to MEDICAL_EN,
                "ar" to MEDICAL_AR,
                "uk" to MEDICAL_UK,
                "nl" to MEDICAL_NL,
            ),
        ),
        Domain.It to Pack(
            domain = Domain.It,
            sourceCredit = "ACM CCS 2012, CNCF Landscape (Apache 2.0), Wikipedia (CC-BY-SA, names only).",
            terms = mapOf(
                "en" to IT_EN,
                "ar" to IT_AR,
                "uk" to IT_UK,
                "nl" to IT_NL,
            ),
        ),
        Domain.Construction to Pack(
            domain = Domain.Construction,
            sourceCredit = "CSI MasterFormat 2020 division titles, ASTM, NEN, QCS 2014, ДБН.",
            terms = mapOf(
                "en" to CONSTRUCTION_EN,
                "ar" to CONSTRUCTION_AR,
                "uk" to CONSTRUCTION_UK,
                "nl" to CONSTRUCTION_NL,
            ),
        ),
        Domain.Legal to Pack(
            domain = Domain.Legal,
            sourceCredit = "Cornell LII Wex headings (CC-BY-NC-SA, names only), US Courts, Al-Meezan, rada.gov.ua, rechtspraak.nl.",
            terms = mapOf(
                "en" to LEGAL_EN,
                "ar" to LEGAL_AR,
                "uk" to LEGAL_UK,
                "nl" to LEGAL_NL,
            ),
        ),
        Domain.Finance to Pack(
            domain = Domain.Finance,
            sourceCredit = "SEC/Investor.gov (public domain), BIS, AFM, NBU, QFMA glossaries.",
            terms = mapOf(
                "en" to FINANCE_EN,
                "ar" to FINANCE_AR,
                "uk" to FINANCE_UK,
                "nl" to FINANCE_NL,
            ),
        ),
    )

    /**
     * Apply [domain]'s pack to the user's vocabulary. Reads the current
     * vocabulary, parses it into a set, unions the pack terms (so re-
     * applying is idempotent), writes back. The order is "existing first,
     * new terms appended" so the user's hand-curated entries stay near
     * the top when they open the editor.
     */
    fun apply(store: PromptStore, domain: Domain, languages: Set<String>) {
        val pack = packs[domain] ?: return
        val toAdd = pack.resolve(languages)
        val existing = store.vocabulary.value.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val combined = LinkedHashSet<String>().apply {
            addAll(existing)
            addAll(toAdd)
        }
        store.setVocabulary(combined.joinToString("\n"))
    }

    /** Count of *new* terms a domain would add given the current vocabulary. */
    fun pendingCount(store: PromptStore, domain: Domain, languages: Set<String>): Int {
        val pack = packs[domain] ?: return 0
        val toAdd = pack.resolve(languages)
        val existing = store.vocabulary.value.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return toAdd.count { it !in existing }
    }

    /**
     * Remove [domain]'s pack terms from the user's vocabulary. Reads the
     * current vocabulary, parses into a list (preserving order), drops
     * every entry that appears in the pack for the given [languages],
     * writes back. The user's hand-curated entries that DON'T appear in
     * any pack are kept intact — we only remove terms that came from
     * the domain pack itself.
     *
     * Note: if the user manually added a word that happens to also be
     * in the pack (e.g. "metformin"), this will also remove it. The
     * pack is treated as authoritative-for-its-terms; rebuild via
     * re-applying or by adding the word back manually.
     */
    fun remove(store: PromptStore, domain: Domain, languages: Set<String>) {
        val pack = packs[domain] ?: return
        val toRemove = pack.resolve(languages).toSet()
        val kept = store.vocabulary.value.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in toRemove }
            .distinct()
        store.setVocabulary(kept.joinToString("\n"))
    }
}

// ─── Medical ─────────────────────────────────────────────────────────────
// Drug INNs (top US/EU dispensed list), procedures + condition acronyms.
// Sources: NLM MeSH, WHO ICD-10, RxNorm. Public domain.
private val MEDICAL_EN = listOf(
    "metformin", "amoxicillin", "azithromycin", "amlodipine", "atorvastatin",
    "lisinopril", "levothyroxine", "hydrochlorothiazide", "omeprazole", "pantoprazole",
    "gabapentin", "sertraline", "escitalopram", "duloxetine", "clopidogrel",
    "warfarin", "apixaban", "rivaroxaban", "furosemide", "spironolactone",
    "hydroxychloroquine", "methotrexate", "prednisone", "albuterol", "salbutamol",
    "montelukast", "tamsulosin", "finasteride", "allopurinol", "ondansetron",
    "ibuprofen", "acetaminophen", "naproxen", "ceftriaxone", "vancomycin",
    "piperacillin", "tazobactam", "meropenem", "insulin glargine", "semaglutide",
    "tirzepatide", "dulaglutide", "empagliflozin", "dapagliflozin", "sitagliptin",
    "losartan", "valsartan", "carvedilol", "metoprolol", "bisoprolol",
    "atrial fibrillation", "myocardial infarction", "cholecystectomy", "appendectomy",
    "laparoscopy", "endoscopy", "colonoscopy", "echocardiogram", "electrocardiogram",
    "hemoglobin A1c", "creatinine", "bilirubin", "troponin", "sepsis",
    "pneumonia", "pyelonephritis", "cellulitis", "diverticulitis", "pancreatitis",
    "hyperlipidemia", "hypothyroidism", "hyperthyroidism", "GERD", "COPD",
    "CKD", "DVT", "PE", "NSTEMI", "STEMI",
)

private val MEDICAL_AR = listOf(
    "ميتفورمين", "أموكسيسيلين", "أزيثروميسين", "أملوديبين", "أتورفاستاتين",
    "ليسينوبريل", "ليفوثيروكسين", "أوميبرازول", "بانتوبرازول", "غابابنتين",
    "سيرترالين", "كلوبيدوغريل", "وارفارين", "فوروسيميد", "سبيرونولاكتون",
    "هيدروكسي كلوروكين", "ميثوتركسات", "بريدنيزون", "سالبوتامول", "مونتيلوكاست",
    "أنسولين", "سيماغلوتيد", "لوسارتان", "فالسارتان", "ميتوبرولول",
    "الرجفان الأذيني", "احتشاء عضلة القلب", "استئصال المرارة", "استئصال الزائدة الدودية",
    "تنظير القولون", "تخطيط صدى القلب", "الهيموغلوبين السكري", "الكرياتينين",
    "التروبونين", "الإنتان", "الالتهاب الرئوي", "التهاب البنكرياس",
    "قصور الغدة الدرقية", "فرط شحميات الدم", "الجلطة الوريدية العميقة",
)

private val MEDICAL_UK = listOf(
    "метформін", "амоксицилін", "азитроміцин", "амлодипін", "аторвастатин",
    "лізиноприл", "левотироксин", "омепразол", "пантопразол", "габапентин",
    "сертралін", "клопідогрель", "варфарин", "апіксабан", "ривароксабан",
    "фуросемід", "спіронолактон", "гідроксихлорохін", "метотрексат", "преднізолон",
    "сальбутамол", "монтелукаст", "інсулін", "семаглутид", "лозартан",
    "валсартан", "метопролол", "карведилол", "фібриляція передсердь", "інфаркт міокарда",
    "холецистектомія", "апендектомія", "колоноскопія", "ехокардіографія",
    "креатинін", "тропонін", "сепсис", "пневмонія", "панкреатит", "гіпотиреоз",
)

private val MEDICAL_NL = listOf(
    "metformine", "amoxicilline", "azitromycine", "amlodipine", "atorvastatine",
    "lisinopril", "levothyroxine", "hydrochloorthiazide", "omeprazol", "pantoprazol",
    "gabapentine", "sertraline", "escitalopram", "duloxetine", "clopidogrel",
    "acenocoumarol", "fenprocoumon", "apixaban", "rivaroxaban", "furosemide",
    "spironolacton", "hydroxychloroquine", "methotrexaat", "prednison", "salbutamol",
    "montelukast", "tamsulosine", "finasteride", "allopurinol", "ondansetron",
    "ibuprofen", "paracetamol", "naproxen", "ceftriaxon", "vancomycine",
    "insuline glargine", "semaglutide", "empagliflozine", "losartan", "metoprolol",
    "atriumfibrilleren", "myocardinfarct", "cholecystectomie", "appendectomie",
    "coloscopie", "echocardiogram", "creatinine", "troponine", "sepsis",
    "longontsteking", "pancreatitis", "hypothyreoïdie",
)

// ─── IT / Software ───────────────────────────────────────────────────────
private val IT_EN = listOf(
    "Kubernetes", "Docker", "PostgreSQL", "MySQL", "MongoDB", "Redis",
    "Cassandra", "Elasticsearch", "Kafka", "RabbitMQ", "NGINX", "Apache",
    "Tomcat", "Jenkins", "GitLab", "GitHub", "Bitbucket", "Terraform",
    "Ansible", "Helm", "Istio", "Envoy", "Prometheus", "Grafana",
    "OpenTelemetry", "Jaeger", "Vault", "Consul", "Nomad", "Kotlin",
    "Rust", "Golang", "TypeScript", "JavaScript", "Python", "Ruby",
    "Scala", "Clojure", "Haskell", "Elixir", "WebAssembly", "GraphQL",
    "gRPC", "WebSocket", "OAuth", "OpenID Connect", "JWT", "SAML",
    "TLS", "mTLS", "CORS", "XSS", "CSRF", "SQL injection", "DDoS",
    "CDN", "DNS", "CIDR", "BGP", "IPv6", "TCP", "UDP", "QUIC",
    "HTTP/2", "HTTP/3", "serverless", "microservices", "Lambda", "DynamoDB",
    "S3", "EC2", "EKS", "GKE", "AKS", "CloudFront", "CloudFormation",
    "Pulumi", "Argo CD", "Tekton", "ESLint", "Webpack", "Vite",
    "React", "Vue", "Svelte", "Next.js", "Nuxt", "TensorFlow", "PyTorch",
    "Hugging Face", "LangChain",
)

private val IT_AR = listOf(
    "كوبرنيتس", "دوكر", "بوستجرس", "مونغو دي بي", "كافكا",
    "جينكنز", "تيرافورم", "أنسبل", "جرافكيو إل", "تايب سكريبت",
    "بايثون", "كوتلن", "راست", "خدمات مصغرة", "حوسبة سحابية",
    "ذكاء اصطناعي", "تعلم آلي", "شبكة عصبية", "واجهة برمجة تطبيقات", "قاعدة بيانات",
)

private val IT_UK = listOf(
    "Кубернетес", "Докер", "Постгрес", "Монго", "Редіс",
    "Кафка", "Дженкінс", "Терраформ", "Ансібл", "Графкуел",
    "Тайпскрипт", "Пайтон", "Котлін", "Раст", "Голанг",
    "мікросервіси", "безсерверні обчислення", "контейнеризація", "оркестрація",
    "розгортання", "конвеєр CI/CD",
)

private val IT_NL = listOf(
    "containerisatie", "orkestratie", "ontwerppatroon", "gegevensbank",
    "ontwikkelstraat", "versiebeheer", "hoofdtak", "afhankelijkheidsbeheer",
    "authenticatie", "autorisatie", "versleuteling", "sleutelbeheer",
    "toegangscontrole", "foutopsporing", "prestatiemeting", "schaalbaarheid",
    "veerkracht",
)

// ─── Construction ────────────────────────────────────────────────────────
private val CONSTRUCTION_EN = listOf(
    "rebar", "formwork", "shotcrete", "gunite", "screed", "parapet", "soffit",
    "fascia", "flashing", "joist", "purlin", "girder", "gusset", "truss",
    "mullion", "transom", "spandrel", "plinth", "pilaster", "architrave",
    "cornice", "ashlar", "HVAC", "ductwork", "plenum", "diffuser", "chiller",
    "condenser", "evaporator", "sheetrock", "gypsum board", "plasterboard",
    "oriented strand board", "plywood", "laminated veneer lumber", "glulam",
    "post-tensioned concrete", "prestressed concrete", "slump test", "aggregate",
    "admixture", "fly ash", "slag cement", "Portland cement", "masonry",
    "mortar", "grout", "caulking", "sealant", "waterproofing", "membrane",
    "geotextile", "geogrid", "piling", "caisson", "shoring", "underpinning",
    "cantilever", "load-bearing", "LEED", "ASHRAE", "NEC", "IBC", "ASTM",
    "ANSI", "OSHA", "MEP", "BIM", "RFI", "punch list", "critical path",
    "scaffolding", "formliner", "falsework",
)

private val CONSTRUCTION_AR = listOf(
    "خرسانة", "حديد تسليح", "أسمنت بورتلاندي", "خلطة خرسانية", "ركام",
    "طوب", "بلاط", "عزل مائي", "عزل حراري", "سقالة",
    "شدة خشبية", "صب", "صبة نظافة", "قواعد", "أعمدة",
    "كمرات", "بلاطة", "سقف", "جدار حامل", "جدار قاطع",
    "أساسات", "خوازيق", "لياسة", "دهان", "سباكة",
    "كهرباء", "تكييف", "تهوية", "صرف صحي", "مصعد",
)

private val CONSTRUCTION_UK = listOf(
    "арматура", "опалубка", "торкретбетон", "стяжка", "парапет",
    "карниз", "ферма", "балка", "ригель", "прогін",
    "пілястра", "цоколь", "гіпсокартон", "фанера", "ОСП",
    "клеєний брус", "портландцемент", "розчин", "заповнювач", "домішка",
    "зольний пил", "шлакоцемент", "гідроізоляція", "теплоізоляція",
    "геотекстиль", "геогратка", "паля", "кесон", "кантилевер", "несучий",
    "ДБН", "ДСТУ", "ОВК", "вентиляція", "кондиціонування", "електромонтаж",
    "сантехніка", "риштування",
)

private val CONSTRUCTION_NL = listOf(
    "betonijzer", "wapening", "bekisting", "spuitbeton", "dekvloer",
    "borstwering", "dakgoot", "gootklos", "gording", "spant", "makelaar",
    "penant", "plint", "kalkzandsteen", "gipsplaat", "multiplex", "OSB",
    "gelamineerd hout", "portlandcement", "mortel", "specie", "toeslagstof",
    "vliegas", "hoogovencement", "metselwerk", "voegwerk", "waterdichting",
    "dampscherm", "isolatie", "geotextiel", "heipaal", "damwand",
    "onderschoeiing", "draagmuur", "vakwerk", "NEN", "Bouwbesluit",
    "EPC", "BENG", "EPBD",
)

// ─── Legal ───────────────────────────────────────────────────────────────
private val LEGAL_EN = listOf(
    "amicus curiae", "habeas corpus", "mens rea", "actus reus", "res judicata",
    "res ipsa loquitur", "stare decisis", "prima facie", "voir dire",
    "subpoena duces tecum", "subpoena ad testificandum", "ex parte", "in camera",
    "in limine", "sub judice", "pro se", "pro bono", "pro hac vice",
    "nunc pro tunc", "de novo", "de minimis", "de jure", "de facto",
    "inter alia", "ipso facto", "quid pro quo", "sine qua non", "ultra vires",
    "intra vires", "caveat emptor", "certiorari", "mandamus", "quo warranto",
    "estoppel", "laches", "indemnification", "specific performance", "quantum meruit",
    "unjust enrichment", "promissory estoppel", "respondeat superior",
    "joint and several liability", "tortfeasor", "negligence per se",
    "strict liability", "proximate cause", "forum non conveniens",
    "diversity jurisdiction", "supplemental jurisdiction", "personal jurisdiction",
    "in rem", "in personam", "quasi in rem", "discovery", "deposition",
    "interrogatories", "preponderance of the evidence", "beyond a reasonable doubt",
    "clear and convincing", "adjudication", "arbitration", "mediation",
    "injunction", "restraining order", "plaintiff", "defendant", "appellant",
    "appellee", "indictment", "arraignment", "plea bargain", "nolo contendere",
)

private val LEGAL_AR = listOf(
    "دعوى", "مدعي", "مدعى عليه", "محكمة ابتدائية", "محكمة استئناف",
    "محكمة تمييز", "قاضي", "وكيل نيابة", "محامي", "شهادة",
    "بينة", "حكم", "استئناف", "نقض", "طعن", "تنفيذ",
    "حجز", "رهن", "عقد", "التزام", "مسؤولية تقصيرية",
    "مسؤولية عقدية", "تعويض", "غرامة", "عقوبة", "جنحة",
    "جناية", "مخالفة", "شركة ذات مسؤولية محدودة", "شركة مساهمة",
    "سجل تجاري", "وكالة تجارية", "تحكيم", "وساطة", "تسوية",
    "بطلان", "فسخ", "إقرار", "يمين", "محضر",
)

private val LEGAL_UK = listOf(
    "позов", "позивач", "відповідач", "суд першої інстанції", "апеляційний суд",
    "касаційний суд", "Верховний Суд", "суддя", "прокурор", "адвокат",
    "свідок", "доказ", "рішення", "ухвала", "постанова",
    "вирок", "апеляція", "касація", "оскарження", "виконання",
    "арешт", "застава", "іпотека", "договір", "зобов'язання",
    "делікт", "відшкодування", "неустойка", "санкція",
    "кримінальне правопорушення", "проступок", "злочин",
    "товариство з обмеженою відповідальністю", "акціонерне товариство",
    "ЄДРПОУ", "господарський суд", "третейський суд", "арбітраж",
    "медіація", "мирова угода", "нікчемний правочин", "оспорюваний правочин",
    "презумпція невинуватості",
)

private val LEGAL_NL = listOf(
    "eiser", "gedaagde", "verweerder", "appellant", "geïntimeerde",
    "rechtbank", "gerechtshof", "Hoge Raad", "kantonrechter", "officier van justitie",
    "advocaat", "getuige", "bewijs", "vonnis", "arrest", "beschikking",
    "hoger beroep", "cassatie", "verzet", "executie", "beslag", "hypotheek",
    "pandrecht", "overeenkomst", "verbintenis", "onrechtmatige daad",
    "wanprestatie", "schadevergoeding", "boete", "strafbaar feit", "misdrijf",
    "overtreding", "besloten vennootschap", "naamloze vennootschap", "KvK",
    "arbitrage", "mediation", "schikking", "nietigheid", "vernietigbaarheid",
    "dwaling", "bedrog", "ontbinding", "opzegging", "opzet", "schuld",
    "aansprakelijkheid", "hoofdelijke aansprakelijkheid", "verjaring",
)

// ─── Finance ─────────────────────────────────────────────────────────────
private val FINANCE_EN = listOf(
    "EBITDA", "EBIT", "ROIC", "ROCE", "WACC", "CAPM", "DCF", "NPV", "IRR",
    "LBO", "M&A", "IPO", "SPAC", "PIPE", "ETF", "REIT", "CDO", "CDS", "CLO",
    "MBS", "ABS", "LIBOR", "SOFR", "EURIBOR", "SONIA", "ESTR", "yield curve",
    "basis point", "duration", "convexity", "delta", "gamma", "vega", "theta",
    "rho", "Black-Scholes", "Monte Carlo", "VaR", "CVaR", "Sharpe ratio",
    "Sortino ratio", "Treynor ratio", "alpha", "beta", "arbitrage",
    "backwardation", "contango", "hedging", "short selling", "naked short",
    "margin call", "collateral", "repo", "reverse repo", "quantitative easing",
    "quantitative tightening", "forward guidance", "yield to maturity",
    "coupon", "zero-coupon", "junk bond", "investment grade", "credit spread",
    "swap", "swaption", "forward", "futures", "option", "warrant",
    "convertible", "preferred stock", "common stock", "Basel III", "Dodd-Frank",
    "MiFID II", "EMIR", "FATCA", "KYC", "AML",
)

private val FINANCE_AR = listOf(
    "صكوك", "مرابحة", "مضاربة", "مشاركة", "إجارة", "استصناع", "تورق",
    "وكالة", "زكاة", "ربا", "ضريبة القيمة المضافة", "بورصة", "سهم", "سند",
    "صندوق استثمار", "طرح عام أولي", "اكتتاب", "أرباح", "توزيعات",
    "رسملة سوقية", "هامش الربح", "سيولة", "ملاءة", "تحوط",
    "مشتقات", "خيارات", "عقود آجلة", "مقايضة", "بازل", "المركزي القطري",
)

private val FINANCE_UK = listOf(
    "облігація", "акція", "дивіденд", "капіталізація", "ліквідність",
    "платоспроможність", "рентабельність", "хеджування", "деривативи",
    "опціон", "ф'ючерс", "своп", "форвард", "маржа", "кредитне плече",
    "короткий продаж", "арбітраж", "дисконтування", "дохідність до погашення",
    "купон", "кредитний спред", "базисний пункт", "крива дохідності",
    "кількісне пом'якшення", "рефінансування", "облікова ставка",
    "монетарна політика", "Базель III", "МСФЗ",
)

private val FINANCE_NL = listOf(
    "beursgang", "overname", "fusie", "vastgoedfonds", "obligatie", "aandeel",
    "dividend", "beurskapitalisatie", "liquiditeit", "solvabiliteit",
    "rentabiliteit", "hefboom", "shortpositie", "dekking", "onderpand",
    "rentecurve", "basispunt", "Sharpe-ratio", "alfa", "bèta", "arbitrage",
    "contango", "backwardation", "hedging", "margin call",
    "kwantitatieve verruiming", "kwantitatieve verkrapping", "swap",
    "swaption", "forward", "future", "optie", "warrant",
    "converteerbare obligatie", "preferent aandeel", "gewoon aandeel",
    "Bazel III", "MiFID II", "EMIR", "FATCA", "KYC", "AML", "witwasrichtlijn",
)
