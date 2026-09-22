-- V15 — the collection city becomes REFERENCE DATA: a table of the 75 first-order administrative
-- units, and listings.city becomes a foreign key to it.
--
-- THE PRODUCT DECISION THIS ENCODES. V12 stored where a buyer can collect an item as FREE TEXT,
-- twice: `city` (Names.clean — the display form) and `normalized_city` (Names.normalize — the
-- filter key and the index). The fold was the right answer for free text and it worked: "Bakı",
-- "baki" and "BAKI" collapsed into one filter key. What no fold can do is stop "28 May metro",
-- "şəhər mərkəzi" or "razılaşma ilə" from BEING a city — so the filter silently missed rows a
-- buyer wanted, and no aggregate over the column meant anything to the operator. The list is now
-- closed: the 11 cities of republic significance AND the 64 rayons, which is both levels of
-- Azerbaijan's first-order division and therefore names where anybody in the country actually is.
--
-- A TABLE, AND NOT varchar + CHECK MATCHING A JAVA ENUM — WHICH BREAKS THIS SCHEMA'S CONVENTION,
-- DELIBERATELY. Every other enum-ish column here is a CHECK pinned to a Java enum (ck_users_plan,
-- ck_listings_status, ck_listings_hidden_reason, ck_blocked_sellers_reason, and V12's currency),
-- and that convention is right for values THE CODE BRANCHES ON: ListingStatus decides visibility
-- (isTerminal, isPubliclyVisible), SubscriptionState decides entitlement, Plan's DECLARATION ORDER
-- IS the ladder. Nothing anywhere does `if (city == BAKU)`: the city is a filter key and a label —
-- reference data — and three things settle it:
--
--   * RETIREMENT IS IMPOSSIBLE WITH AN ENUM. Removing a constant either breaks every existing row
--     that holds it or keeps a dead constant forever. `active` here takes an entry out of the
--     picker while every listing that already named it keeps its value, which is the only
--     behaviour that is honest about a seller's past statement.
--   * A FOREIGN KEY CANNOT DRIFT. A CHECK can disagree with the Java enum — which is exactly why
--     ListingEnumsTest exists to replay ADDs and DROPs across every migration for the other five.
--     fk_listings_city needs no test: PostgreSQL enforces it on every INSERT, forever.
--   * 75 × 3 LOCALISED NAMES ARE DATA, NOT CODE. As an enum they would live in three Android
--     string files, edited independently, with 225 chances to mis-type a diacritic. Here they are
--     one seeded table, served by one cacheable endpoint, and a wording fix is an UPDATE.
--
-- WHAT WOULD HAVE HAPPENED TO EXISTING ROWS. Nothing, because there are none — the marketplace has
-- never carried a listing in production — so this migration is deliberately STRICT and contains no
-- UPDATE, no mapping table and no backfill. Had rows existed, ADD CONSTRAINT fk_listings_city
-- would have ABORTED on the first row whose `city` was not one of these 75 codes, i.e. on EVERY
-- row: 'Bakı' is not 'BAKU'. (The narrowing ALTER COLUMN ... TYPE varchar(32) would have failed
-- first on any value longer than 32 characters.) That abort is the correct outcome and both
-- lenient alternatives were rejected: a best-guess mapping of free text onto codes ("Baki" -> BAKU,
-- "Xirdalan" -> ABSHERON) would silently MIS-FILE everything it did not recognise, and a wrong
-- collection city is worse than an absent one because a buyer travels for it; making `city`
-- nullable would leave listings on the board whose one location fact is blank and keep the
-- nullable column forever for the sake of rows that do not exist. If this ever has to run against
-- real rows, the repair is a data migration written AFTER a human has read the distinct values.
--
-- No char(N) anywhere (Hibernate 6.6's validate treats bpchar as a type mismatch and the
-- application refuses to boot), and no PostgreSQL native enum.

-- ---------------------------------------------------------------------------------------------
-- 1. market_cities — the reference table.
-- ---------------------------------------------------------------------------------------------
-- NO uuid surrogate key: the CODE is the identity, it is stable, it is what listings.city stores,
-- and a join through a surrogate would put an integer nobody can read into the one column an
-- operator reads by eye. NO created_at/updated_at: this is seeded reference data, not something a
-- user writes, and an audit trail of "when did Ağdərə appear" belongs in the migration that adds
-- it.
CREATE TABLE market_cities (
    -- The frozen half. Upper-snake ASCII, enforced below, because a code is an IDENTIFIER and not
    -- a label: a diacritic in a code would put Azerbaijani's locale hazard (İ/ı) inside the value
    -- itself rather than only in the parsing of it. varchar(32) is this schema's house width for a
    -- coded column (hidden_reason, blocked_sellers.reason, listing_reports.reason); the longest
    -- code is MINGACHEVIR at 11 characters.
    code       varchar(32) PRIMARY KEY,

    -- THE LABELS, ALL THREE, ON THE ROW. The picker endpoint serves every language and the client
    -- reads its own — the server is NOT in the localisation business and never negotiates a locale
    -- for this. One response, cacheable for a decade, and no Accept-Language parsing anywhere.
    -- 64 is roomy: the longest label today is 'Khirdalan (Absheron)' at 20 characters.
    name_az    varchar(64) NOT NULL,
    name_en    varchar(64) NOT NULL,
    name_ru    varchar(64) NOT NULL,

    -- THE PICKER ORDER, SET BY DATA RATHER THAN BY A RUNTIME SORT, AND THIS MATTERS MORE THAN IT
    -- LOOKS. Azerbaijani collates ə, ğ, ı, ö, ş and ü OUTSIDE the Latin order (A B C Ç D E Ə F G Ğ
    -- H X I İ J K Q L M N O Ö P R S Ş T U Ü V Y Z), so a naive sort — in SQL, in Java or in the
    -- client — scatters Ağdam, Gədəbəy and Şəki, and puts Xankəndi after Yevlax instead of between
    -- Gəncə and Lənkəran. Pre-computing it here means no server needs a collator and no client
    -- needs to agree with another client.
    --
    -- THE ORDER: the 11 cities of republic significance FIRST (100..200), Bakı at the head because
    -- roughly a quarter of the country is there, the other ten in Azerbaijani alphabetical order;
    -- then the 64 rayons (1000..1630) in Azerbaijani alphabetical order, with the seven rayons of
    -- the Nakhchivan AR mixed in by NAME rather than held back as a third group — a seller looking
    -- for Şərur looks under Ş, not under a heading about autonomous republics.
    --
    -- STEPS OF TEN, and two bands left free on purpose. 1..99 and 9000+ are reserved for the
    -- product gap this list genuinely has and which is NOT filled here: a seller who will meet
    -- anywhere, or who ships nationwide, has no truthful value among the 75. When that is built it
    -- is a separate nullable field or a distinct NON-GEOGRAPHIC value — never a 76th "place" — and
    -- it will want to sort either first or last. Renumbering an existing row is a data migration;
    -- slotting one between two is an INSERT.
    sort_order integer     NOT NULL,

    -- RETIREMENT, which is the whole reason this is a table. `false` removes an entry from the
    -- picker and from what a new listing may name, while every listing that already named it keeps
    -- its value and stays on the board — a seller's statement about the past is not ours to
    -- rewrite. A DELETE is refused by fk_listings_city instead, which is the correct answer:
    -- nothing about an administrative change makes it true that the seller never said this.
    active     boolean     NOT NULL DEFAULT true,

    -- A code is an identifier. This is what stops a label, a diacritic or a lower-case spelling
    -- ever becoming one — from a migration, an operator's INSERT or an import.
    CONSTRAINT ck_market_cities_code_shape
        CHECK (code ~ '^[A-Z][A-Z_]*[A-Z]$'),

    -- All three languages are mandatory: a client that falls back to a blank label would show an
    -- empty row in the picker, which is worse than showing another language's name.
    CONSTRAINT ck_market_cities_names_present
        CHECK (char_length(btrim(name_az)) >= 2
           AND char_length(btrim(name_en)) >= 2
           AND char_length(btrim(name_ru)) >= 2),

    -- "Deterministic" means NO TIES. Without this a duplicate sort_order makes the picker's order
    -- depend on the plan, which is the failure this column exists to remove — and it would show up
    -- as two clients listing the same 75 entries in two different orders, which nobody would ever
    -- file as a bug.
    CONSTRAINT ux_market_cities_sort_order UNIQUE (sort_order)
);

-- NO INDEX beyond the primary key and that UNIQUE, and both reads are named here so the next
-- person does not have to guess: the picker is "every active row, in sort_order" (75 rows — the
-- UNIQUE index can supply the ordering and a sequential scan plus sort is cheaper than any partial
-- index at this size) and publishing is "this code, is it active" (a primary-key probe). The same
-- reasoning as the play_notifications janitor's deliberate seq scan.

-- ---------------------------------------------------------------------------------------------
-- 2. The list. 11 cities of republic significance, then 64 rayons.
-- ---------------------------------------------------------------------------------------------
-- RECONCILED AGAINST THREE INDEPENDENT SOURCES and it is 75, not "about 70": every entry is a
-- first-order unit, and the only way to reach a rounder number is to delete somebody's only
-- truthful option. Four things a reader may think are mistakes, and are not:
--
--   * ABSHERON is the one code that was a JUDGEMENT CALL. The label leads with Xırdalan because
--     that is the name the ~196,000 people there write and search for, and without it much of the
--     country's fourth-largest city picks Bakı. The CODE names the administrative unit, because
--     the unit IS Abşeron rayonu (Xırdalan is a rayon-subordinate city) and because ISO 3166-2
--     already calls it AZ-ABS. A code keyed to a settlement inside the unit would be the wrong
--     name for the unit if Xırdalan is ever elevated. This was reversible only until the first row
--     was written; it is written now.
--   * 'Qobustan (Mərəzə)' is in Dağlıq Şirvan and is NOT the Qobustan rock-art park, which sits
--     inside Bakı's Qaradağ district. Without the tag it is the likeliest misfile in the list.
--   * 'Naxçıvan (şəhər)' carries its tag so an Ordubad or Şərur seller does not pick the city.
--   * AGHDARA exists, and it is the single entry any list assembled before 2024 is missing: it was
--     created by Law No. 1043-VIQ of 5 December 2023 out of parts of Ağdam, Kəlbəcər and Tərtər,
--     and it is absent from ISO 3166-2 entirely. Şəki, Lənkəran and Yevlax appear ONCE each rather
--     than as a city and a rayon, because the current division has one unit in each of those three
--     cases and only the out-of-date ISO list still splits them.
--
-- Bakı's 12 city rayons are deliberately NOT here: they are a SECOND administrative level, adding
-- them takes the list to 87 and mixes levels, and the classifieds sites that offer that granularity
-- put it in a dependent second field. That is the pattern to copy if it is ever wanted.
INSERT INTO market_cities (code, name_az, name_en, name_ru, sort_order) VALUES
    -- 11 cities of republic significance, Bakı first.
    ('BAKU', 'Bakı', 'Baku', 'Баку', 100),
    ('GANJA', 'Gəncə', 'Ganja', 'Гянджа', 110),
    ('KHANKENDI', 'Xankəndi', 'Khankendi', 'Ханкенди', 120),
    ('LANKARAN', 'Lənkəran', 'Lankaran', 'Лянкяран', 130),
    ('MINGACHEVIR', 'Mingəçevir', 'Mingachevir', 'Мингячевир', 140),
    ('NAFTALAN', 'Naftalan', 'Naftalan', 'Нафталан', 150),
    ('NAKHCHIVAN', 'Naxçıvan (şəhər)', 'Nakhchivan (city)', 'Нахчыван (город)', 160),
    ('SUMQAYIT', 'Sumqayıt', 'Sumqayit', 'Сумгаит', 170),
    ('SHAKI', 'Şəki', 'Shaki', 'Шеки', 180),
    ('SHIRVAN', 'Şirvan', 'Shirvan', 'Ширван', 190),
    ('YEVLAKH', 'Yevlax', 'Yevlakh', 'Евлах', 200),
    -- 64 rayons, in Azerbaijani alphabetical order.
    ('AGHJABADI', 'Ağcabədi', 'Aghjabadi', 'Агджабеди', 1000),
    ('AGHDAM', 'Ağdam', 'Aghdam', 'Агдам', 1010),
    ('AGHDASH', 'Ağdaş', 'Agdash', 'Агдаш', 1020),
    ('AGHDARA', 'Ağdərə', 'Aghdara', 'Агдере', 1030),
    ('AGHSTAFA', 'Ağstafa', 'Aghstafa', 'Агстафа', 1040),
    ('AGHSU', 'Ağsu', 'Agsu', 'Ахсу', 1050),
    ('ASTARA', 'Astara', 'Astara', 'Астара', 1060),
    ('BABEK', 'Babək', 'Babek', 'Бабек', 1070),
    ('BALAKAN', 'Balakən', 'Balakan', 'Балакен', 1080),
    ('BEYLAGAN', 'Beyləqan', 'Beylagan', 'Бейлаган', 1090),
    ('BARDA', 'Bərdə', 'Barda', 'Барда', 1100),
    ('BILASUVAR', 'Biləsuvar', 'Bilasuvar', 'Билясувар', 1110),
    ('JABRAYIL', 'Cəbrayıl', 'Jabrayil', 'Джебраил', 1120),
    ('JALILABAD', 'Cəlilabad', 'Jalilabad', 'Джалилабад', 1130),
    ('JULFA', 'Culfa', 'Julfa', 'Джульфа', 1140),
    ('DASHKASAN', 'Daşkəsən', 'Dashkasan', 'Дашкесан', 1150),
    ('FUZULI', 'Füzuli', 'Fuzuli', 'Физули', 1160),
    ('GADABAY', 'Gədəbəy', 'Gadabay', 'Кедабек', 1170),
    ('GORANBOY', 'Goranboy', 'Goranboy', 'Геранбой', 1180),
    ('GOYCHAY', 'Göyçay', 'Goychay', 'Гёйчай', 1190),
    ('GOYGOL', 'Göygöl', 'Goygol', 'Гёйгёль', 1200),
    ('HAJIGABUL', 'Hacıqabul', 'Hajigabul', 'Гаджигабул', 1210),
    ('KHACHMAZ', 'Xaçmaz', 'Khachmaz', 'Хачмаз', 1220),
    ('ABSHERON', 'Xırdalan (Abşeron)', 'Khirdalan (Absheron)', 'Хырдалан (Абшерон)', 1230),
    ('KHIZI', 'Xızı', 'Khizi', 'Хызы', 1240),
    ('KHOJALY', 'Xocalı', 'Khojaly', 'Ходжалы', 1250),
    ('KHOJAVEND', 'Xocavənd', 'Khojavend', 'Ходжавенд', 1260),
    ('IMISHLI', 'İmişli', 'Imishli', 'Имишли', 1270),
    ('ISMAYILLI', 'İsmayıllı', 'Ismayilli', 'Исмаиллы', 1280),
    ('KALBAJAR', 'Kəlbəcər', 'Kalbajar', 'Кельбаджар', 1290),
    ('KANGARLI', 'Kəngərli', 'Kangarli', 'Кенгерли', 1300),
    ('KURDAMIR', 'Kürdəmir', 'Kurdamir', 'Кюрдамир', 1310),
    ('QAKH', 'Qax', 'Qakh', 'Гах', 1320),
    ('QAZAKH', 'Qazax', 'Qazakh', 'Газах', 1330),
    ('QABALA', 'Qəbələ', 'Qabala', 'Габала', 1340),
    ('QOBUSTAN', 'Qobustan (Mərəzə)', 'Gobustan (Maraza)', 'Гобустан (Мараза)', 1350),
    ('QUBA', 'Quba', 'Quba', 'Губа', 1360),
    ('QUBADLI', 'Qubadlı', 'Qubadli', 'Губадлы', 1370),
    ('QUSAR', 'Qusar', 'Qusar', 'Гусар', 1380),
    ('LACHIN', 'Laçın', 'Lachin', 'Лачин', 1390),
    ('LERIK', 'Lerik', 'Lerik', 'Лерик', 1400),
    ('MASALLI', 'Masallı', 'Masally', 'Масаллы', 1410),
    ('NEFTCHALA', 'Neftçala', 'Neftchala', 'Нефтчала', 1420),
    ('OGHUZ', 'Oğuz', 'Oghuz', 'Огуз', 1430),
    ('ORDUBAD', 'Ordubad', 'Ordubad', 'Ордубад', 1440),
    ('SAATLI', 'Saatlı', 'Saatly', 'Саатлы', 1450),
    ('SABIRABAD', 'Sabirabad', 'Sabirabad', 'Сабирабад', 1460),
    ('SALYAN', 'Salyan', 'Salyan', 'Сальян', 1470),
    ('SAMUKH', 'Samux', 'Samukh', 'Самух', 1480),
    ('SADARAK', 'Sədərək', 'Sadarak', 'Садарак', 1490),
    ('SIYAZAN', 'Siyəzən', 'Siyazan', 'Сиязань', 1500),
    ('SHABRAN', 'Şabran', 'Shabran', 'Шабран', 1510),
    ('SHAHBUZ', 'Şahbuz', 'Shahbuz', 'Шахбуз', 1520),
    ('SHAMAKHI', 'Şamaxı', 'Shamakhi', 'Шемаха', 1530),
    ('SHAMKIR', 'Şəmkir', 'Shamkir', 'Шамкир', 1540),
    ('SHARUR', 'Şərur', 'Sharur', 'Шарур', 1550),
    ('SHUSHA', 'Şuşa', 'Shusha', 'Шуша', 1560),
    ('TARTAR', 'Tərtər', 'Tartar', 'Тертер', 1570),
    ('TOVUZ', 'Tovuz', 'Tovuz', 'Товуз', 1580),
    ('UJAR', 'Ucar', 'Ujar', 'Уджар', 1590),
    ('YARDIMLI', 'Yardımlı', 'Yardimli', 'Ярдымлы', 1600),
    ('ZAGATALA', 'Zaqatala', 'Zagatala', 'Загатала', 1610),
    ('ZANGILAN', 'Zəngilan', 'Zangilan', 'Зангилан', 1620),
    ('ZARDAB', 'Zərdab', 'Zardab', 'Зардаб', 1630);

-- ---------------------------------------------------------------------------------------------
-- 3. listings.city stops being free text and becomes a foreign key.
-- ---------------------------------------------------------------------------------------------
-- Everything that existed only to serve the free-text PAIR is dropped EXPLICITLY. PostgreSQL would
-- drop the index and the CHECK implicitly with the column, but the enum-vs-CHECK guards in this
-- project replay ADDs and DROPs by constraint NAME across every migration, so a constraint that
-- disappears implicitly disappears from that replay too — and a reader should not have to know
-- PostgreSQL's dependency rules to know what is left standing.
DROP INDEX ix_listings_browse_city;

-- `char_length(btrim(city)) >= 2 AND char_length(btrim(normalized_city)) >= 2` — the backstop that
-- stopped Names.clean turning whitespace into a published empty string. A foreign key into a
-- 75-row table is a strictly stronger statement about the same column, so nothing replaces it.
ALTER TABLE listings
    DROP CONSTRAINT ck_listings_city_length;

-- MEANINGLESS ONCE THE VALUE IS A CODE: a diacritic fold of 'BAKU' is 'baku', which is the same
-- fact twice. This migration therefore leaves the marketplace with ONE FEWER column than it
-- started with, and the browse index keeps its exact shape on the code instead.
ALTER TABLE listings
    DROP COLUMN normalized_city;

ALTER TABLE listings
    ALTER COLUMN city TYPE varchar(32);

-- THE CONSTRAINT THAT CANNOT DRIFT. NO ACTION on both DELETE and UPDATE, i.e. the default, and
-- both halves are deliberate:
--   * DELETE — a city row is NEVER deleted while a listing names it. Retirement is `active =
--     false`, which takes the entry out of the picker and leaves the listing intact; a DELETE
--     would ask this migration to decide what a seller's statement about the past should become,
--     and there is no correct answer to that.
--   * UPDATE — a code is never renamed. A stored code IS what the seller said; rewriting it
--     rewrites their statement. Administrative change is rare but real (one new first-order unit
--     in thirty years, in December 2023), so the cheap path must be "INSERT a row", and it is.
--
-- No index is added on the referencing side. PostgreSQL only needs one for the parent-row DELETE
-- and UPDATE paths, which the paragraph above rules out; ix_listings_browse_city below is partial
-- and serves the board rather than the constraint.
ALTER TABLE listings
    ADD CONSTRAINT fk_listings_city
        FOREIGN KEY (city) REFERENCES market_cities (code);

-- The city-filtered board, rebuilt IDENTICAL IN SHAPE to V12's: same partial predicate, same
-- filter-column-then-created_at DESC so the index supplies the ORDER BY as well as the filter. A
-- city-leading index still cannot serve the UNFILTERED board (V11's ix_user_subscriptions_reconcile
-- lesson), which is why this stays a SECOND index rather than a widening of ix_listings_browse.
--
-- What changed is not the shape but what equality MEANS. `normalized_city = 'baki'` made two
-- SPELLINGS of one city equal and left two different STATEMENTS about it ("baki", "baki seher
-- merkezi", "28 may metro") unequal, so one place was many keys and the filter silently missed rows
-- a buyer wanted. Equality on a code cannot: both sides now come from this table.
CREATE INDEX ix_listings_browse_city
    ON listings (city, created_at DESC) WHERE status = 'ACTIVE' AND hidden_at IS NULL;
