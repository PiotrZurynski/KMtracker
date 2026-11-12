# TrackerCar (iOS, SwiftUI, iOS 17+)

Start/Stop → liczy km z GPS-a tylko w trakcie przejazdu. Działa offline. Dzień z kilkoma
przejazdami = jedna rozwijana karta. Koszt = km × spalanie/100 × cena ÷ 2. Dynamic Island + ekran blokady.

## Jak zbudować (Windows nie zbuduje apki iOS – potrzebny macOS + Xcode)
1. Na Macu (lub w chmurze: GitHub Actions `macos-latest`, Codemagic, MacInCloud): `brew install xcodegen`
2. W tym folderze: `xcodegen generate` → powstaje `TrackerCar.xcodeproj`
3. Otwórz w Xcode, w obu targetach ustaw swój Team i unikalny Bundle ID
   (widget musi mieć prefiks apki, np. `pl.twoje.trackercar` i `pl.twoje.trackercar.widgets`).
4. Podłącz iPhone'a → Run. Darmowe konto Apple ID działa (podpis ważny 7 dni).

## Bateria
- GPS tylko między Start a Stop, `distanceFilter` 15 m, tryb automotive, brak „Always".
- Zapis do bazy co ~100 m, odświeżanie Dynamic Island max co 3 s.

## Bezpieczeństwo / prywatność
- Brak kont, backendu i analityki; dane tylko lokalnie (SwiftData, szyfrowane przez iOS gdy telefon zablokowany).
- Uprawnienie tylko „Podczas używania" (+ tryb tła zaczęty z pierwszego planu).
- Sieć wyłącznie HTTPS, sesja bez cache/cookies, limit rozmiaru odpowiedzi i walidacja ceny (2–20 zł).

## Cena paliwa z Moya Olsztyn
Moya nie ma publicznego API. W Ustawieniach podajesz adres HTTPS strony z ceną + regex
(np. `(\d+[,.]\d{2})`). Bez tego wpisujesz cenę ręcznie – działa też offline (zapamiętuje ostatnią).
