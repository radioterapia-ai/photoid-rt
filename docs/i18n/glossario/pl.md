# Glosariusz PhotoID RT — polski (Polska)

Rejestr: kliniczny, rzeczowy, bez ozdobników. Komunikaty w formie bezosobowej
(„Nie można odczytać pliku", „Zapisano kartę"), przyciski i pozycje menu w
krótkim trybie rozkazującym, zgodnie z polską konwencją Androida („Zapisz",
„Anuluj", „Usuń", „Zakończ"). Żadnego marketingu i żadnego stopniowania: to
oprogramowanie działa obok pacjenta leżącego na stole.

Placeholdery: zdanie ma być poprawne po wstawieniu wartości. Reguła — etykieta,
dwukropek, placeholder („Pacjent: %1$s", „Data symulacji: %1$s"), zamiast
wplatania %1$s w przypadek zależny. Nigdy nie budować zdania, które wymusza
odmianę wstawianej nazwy. Liczniki idą przez `<plurals>` (one/few/many/other),
bo „%1$d zdjęcie / zdjęcia / zdjęć" to trzy różne formy, a nawias „(s)" z
oryginału po polsku nie działa.

Jedno tłumaczenie na termin. Jeśli wiersz mówi X, wszędzie pisze się X.

    termin po portugalsku | stałe tłumaczenie | uwaga

simulação | symulacja | Sesja TK do planowania leczenia (tomografia symulacyjna), nie próba ani udawanie. Moduł „Simulação" w aplikacji to „Symulacja".
posicionamento | ułożenie | Sposób ułożenia pacjenta na stole (set-up). Także nazwa kategorii zdjęć. Nigdy „pozycjonowanie" — po polsku to pozycjonowanie stron w wyszukiwarce.
paciente | pacjent | Na wydruku wersalikami: „PACJENT". Licznik ma trzy formy: 1 pacjent, 2–4 pacjenci, 5+ pacjentów.
prontuário | numer historii choroby | Numer, pod którym szpital prowadzi pacjenta. W wąskich polach skrót „Nr historii choroby". Nie „karta pacjenta", nie „numer rejestru".
registro | numer historii choroby | Ten sam numer co „prontuário" — brief produktu traktuje oba słowa jako jedno, więc po polsku brzmią tak samo. Uwaga: „registro" w wykazie paraf znaczy co innego i tam nigdy tak nie brzmi — to numer uprawnień zawodowych (u lekarzy i pielęgniarek numer PWZ).
ficha de posicionamento | karta ułożenia | Wydruk PDF ze zdjęciami set-upu. Rodzina nazw jak „karta napromieniania". Samo „ficha" w dowolnym stringu to „karta".
Time-Out | Time-Out | Bez zmian, wielka litera i dywiz. Międzynarodowy termin z listy kontrolnej WHO, przyjęty w polskich ośrodkach; przetłumaczony przestałby być rozpoznawalny. Nie odmieniać — dostawić polski rzeczownik: „strona Time-Out", „przed Time-Out".
rubricário | wykaz paraf | Karta, na której każdy z zespołu ma stanowisko, numer uprawnień i własną parafę, żeby po latach było wiadomo, kto podpisał kartę ułożenia. Polski nie ma terminu konsekrowanego — wybrany najbardziej przejrzysty. W PDF tytuł wersalikami: „WYKAZ PARAF".
rubrica | parafa | Krótki podpis (inicjały) stawiany na potwierdzenie. Czasownik „parafować"; ramka to miejsce, w którym „składa się parafę". Nigdy „rubryka" — to fałszywy przyjaciel, po polsku kolumna tabeli albo dział.
cargo | stanowisko | Nazwa funkcji zawodowej w wykazie paraf: Lekarz, Fizyk medyczny, Technik elektroradiologii, Pielęgniarstwo. Nie „rola", nie „funkcja".
etiqueta | etykieta | Etykieta identyfikacyjna pacjenta: ta, którą odczytuje ML Kit, i ta, którą wkleja się w wyznaczone pole karty.
acessório | akcesorium unieruchamiające | Urządzenie do unieruchomienia i ułożenia: maska termoplastyczna, podpórka pod kolana, płyta pochylona. Pełna forma także w wąskich miejscach — samo „akcesorium" nie mówi, o co chodzi.
acessórios | akcesoria unieruchamiające | Kategoria zdjęć. Dopełniacz: „akcesoriów unieruchamiających". Nie zastępować słowem „unieruchomienie", które nazywa czynność, a nie sprzęt.
rosto | twarz | Kategoria zdjęć; w zdaniach „zdjęcie twarzy". Nie „oblicze", nie „portret".
foto | zdjęcie | Rodzaj nijaki, więc uzgodnienie brzmi „zdjęcie zarchiwizowane", „nowe zdjęcie". W interfejsie zawsze „zdjęcie", nigdy „fotografia" — nie mieści się w przyciskach.
fotos | zdjęcia | Trzy formy przy liczniku: 1 zdjęcie, 2–4 zdjęcia, 5+ zdjęć. Każdy string typu „%1$d foto(s)" wymaga `<plurals>`.
tratamento | leczenie | Etap i moduł, w którym zdjęcia z symulacji ogląda się dzień po dniu przy akceleratorze. Pojedyncza sesja to „seans" albo „frakcja"; nazwą modułu zostaje „Leczenie".
radioterapia | radioterapia | Nazwa dziedziny. Polska specjalność lekarska: „radioterapia onkologiczna".
acelerador | akcelerator liniowy | Przy powtórzeniu w tym samym tekście i w wąskich etykietach skraca się do „akcelerator". Nie „przyspieszacz", nie „LINAC" — skrót nie wchodzi do interfejsu.
equipamento | aparat | Aparat terapeutyczny wybierany w Time-Out, a nie sprzęt w ogóle. Pole: „Aparat". Nie „sprzęt", nie „wyposażenie", nie „urządzenie".
sítio | lokalizacja | Napromieniana okolica anatomiczna. Długa etykieta: „Lokalizacja anatomiczna / topografia"; wąska kolumna Time-Out: „Lokalizacja / strona" (strona = lateralność). Nie „miejsce", nie „witryna".
médico | lekarz | „Médico responsável" = „Lekarz odpowiedzialny". Zostaje ogólny: kartę podpisuje lekarz prowadzący pacjenta tego dnia, niekoniecznie specjalista radioterapii.
físico | fizyk medyczny | Zawsze z przymiotnikiem: samo „fizyk" to inny zawód.
tecnólogo | technik elektroradiologii | Zawód osoby, która układa pacjenta i obsługuje aparat. W wąskiej kolumnie Time-Out skraca się do „Technik". Ustawowa nazwa „elektroradiolog" jest nowa — w interfejsie zostaje „technik elektroradiologii", bo tak mówi się w zakładach.
dosimetrista | dozymetrysta | W wielu polskich ośrodkach plany przygotowuje fizyk medyczny, więc to stanowisko trafia do wykazu paraf tylko tam, gdzie ośrodek je ma.
enfermagem | pielęgniarstwo | W wykazie paraf to nazwa stanowiska, nie oddziału. Forma bezrodzajowa jest celowa: „pielęgniarka/pielęgniarz" wymusza wybór płci w rubryce, która dotyczy funkcji. Numer uprawnień: PWZ z okręgowej izby pielęgniarek i położnych.
clínica | ośrodek | Obejmuje szpital, zakład publiczny i centrum prywatne. Unikać „klinika": po polsku to jednostka uniwersytecka albo prywatny gabinet, a aplikacja jest uniwersalna. „Nome da clínica" = „Nazwa ośrodka".
unidade | placówka | Filia tej samej instytucji: konfigurację importuje się „z innej placówki", etykiety pacjentów bywają różne „w różnych placówkach". Nie „jednostka" (czyta się jak jednostka miary), nie „oddział" (to oddział szpitalny).
cadastro | dane pacjenta | Dane identyfikacyjne w kartotece aplikacji. „Editar cadastro" = „Edytuj dane pacjenta". Nie „rejestracja" — po polsku to okienko przyjęć.
histórico | lista pacjentów | Spis pacjentów już zapisanych na tym tablecie. Nie „historia": „historia choroby" to dokumentacja medyczna, a samo „historia" w interfejsie czyta się jak historia przeglądania.
configurações | ustawienia | Konwencja Androida.
arquivar | zarchiwizować | Zdejmuje zdjęcie z karty ułożenia i zostawia je w folderze pacjenta. Przeciwieństwo usunięcia, i na tej różnicy stoi cała funkcja — nigdy nie używać obu słów zamiennie. „Excluir" to zawsze „usunąć": plik znika z dysku bezpowrotnie.
arquivada | zarchiwizowane | O zdjęciu, rodzaj nijaki: „zdjęcia zarchiwizowane" to te, które wyszły z karty, ale nadal leżą na dysku.
descartar | odrzucić | Wyrzuca to, czego jeszcze nie zapisano: wersję roboczą symulacji, nowe zdjęcia z aparatu. Inaczej niż „usunąć", które kasuje zapisany plik, i „anuluj", które tylko zamyka okno.
finalizar | zakończyć | Zamyka symulację i generuje PDF. Przycisk: „Zakończ". Nie „sfinalizować" (kalka), nie „zapisz".
carrossel | karuzela | Przesuwany palcem przegląd zdjęć pacjenta w module Leczenie. Odmienia się normalnie: „dodaj do karuzeli", „w karuzeli".
impressora | drukarka | Także w statusach: „Drukarka dostępna / niedostępna".
pen-drive | pendrive | Podłączany kablem OTG. Tak mówi się w Polsce; „pamięć USB" brzmi urzędowo, „pamięć przenośna" jest niejasna. Odmiana: „na pendrivie", „z pendrive'a".
galeria | galeria | Galeria zdjęć tabletu, z której importuje się gotowe pliki.
rolo da câmera | zdjęcia z aparatu | Zdjęcia właśnie zrobione aparatem tabletu, jeszcze nieprzypisane do karty. Nie „rolka aparatu" — to kalka z iOS-a, a w zakładach pracują tablety z Androidem.
nascimento | data urodzenia | W polach zawsze pełne „Data urodzenia", na wydruku wersalikami „DATA URODZENIA". Samo „urodzenie" nie jest etykietą.
sexo | płeć | Pole K/M w danych pacjenta. Nie „gender", nie „rodzaj".
idade | wiek | Na wydruku: „WIEK". Przy liczniku: 1 rok, 2–4 lata, 5+ lat.
data da simulação | data symulacji | Na karcie: „DATA SYMULACJI:". Pojawia się jeden raz. Z placeholderem zawsze po dwukropku, żeby nie odmieniać wstawianej wartości.
nova simulação | nowa symulacja | Drugi kurs leczenia u tego samego pacjenta: daje przyrostek folderowi i plikom. Na karcie wersalikami: „NOWA SYMULACJA".
reirradiação | reirradiacja | Bez dywizu; termin używany przez polskich radioterapeutów. Odmienia się i liczy: „reirradiacje", „reirradiacji". Opis „powtórne napromienianie" tylko w tekście kierowanym do pacjenta.
rascunho | wersja robocza | Rozpoczęta i niezakończona symulacja, którą można podjąć później. Standardowy polski odpowiednik „draft"; nie „szkic" (to rysunek), nie „brudnopis".
observações | uwagi | Uwagi do symulacji, które trafiają do PDF. Na wydruku: „UWAGI". Nie „obserwacje" — po polsku to obserwacja kliniczna pacjenta.
conferência | sprawdzenie | Kontrola wykonywana przez człowieka: tożsamości pacjenta i każdego pola podpowiedzianego przez odczyt etykiety. Czasownik „sprawdzić". Nie „konferencja" (fałszywy przyjaciel); w interfejsie jedno słowo, także tam, gdzie dokumenty jakości mówią „weryfikacja".
sala de simulação | pracownia symulacji | Pomieszczenie z tomografem do planowania, w którym powstają zdjęcia. W polskich szpitalach takie pomieszczenia to „pracownie" (pracownia TK), nie „sale".
