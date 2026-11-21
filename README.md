# Offer Reader (Android)

Reads Lalamove offers off your screen, scores each one against your S$/hr target using live GPS and the orders you already have, and shows the verdict in a floating bubble. **Read-only: it never taps Accept for you.**

## Build & install
1. Install **Android Studio** (free, Windows or Mac).
2. Unzip this folder → Android Studio → **Open** → pick the `OfferReader` folder. Let Gradle sync (first time downloads ~1 GB).
3. On the Android phone: Settings → About phone → tap **Build number** 7× → back to Settings → Developer options → turn on **USB debugging**.
4. Plug the phone in, pick it in the device dropdown, press **Run ▶**.

## Use
1. Open Offer Reader → **Start reading offers**. Allow location, notifications, and **Display over other apps**.
2. When Android asks what to share, choose **Entire screen**.
3. Open Lalamove → Pickup. The bubble shows each offer:
   - ✅ TAKE (≥ 25% above target) / 🟡 OK (meets target) / ⛔ SKIP
   - `/hr` for the extra time, cash you keep, pickup km vs max, minimum fare, extra km/min
   - Good offers get a frame drawn right on the Lalamove card (green = TAKE, amber = OK) and the phone buzzes once, so you know where to tap. It never taps for you.
   - Phone buzzes once when a new TAKE/OK appears. Tap the bubble to shrink it, drag to move it.
4. After accepting, open **Record → Ongoing** for a second. The bubble shows "📋 Synced N ongoing orders" and those become your orders on board, so the next offers are scored as stacks. (The Record screen is never scored as offers.)
   - GPS ticks jobs off: within ~500 m of a pickup → picked up; reach the drop then drive >1.5 km away → delivered.
   - Place points are area-level, so if it gets one wrong, fix it with **Picked** / **Done** in the app.
5. **Need to be somewhere by a time?** In the app, under "Be somewhere by a time", type the place → **Pick time & set**.
   - **Route plan** lists the best order for your jobs ending at that place, with arrival times and minutes to spare.
   - The bubble's top line shows `🎯 Paya Lebar by 8:00 PM · 23 min spare`.
   - Offers are scored on the route that ends at your goal, so jobs heading that way score higher (the km you'd drive anyway are free). Any offer that would make you late shows **⏰ LATE**.
   - The goal clears itself 30 min after the deadline, or tap **Clear**.
6. Stop from the app or the notification's **Stop** button.

## Maths (same as the web tracker)
- Take-home = 78.5% × fare + 95% × priority fee
- Petrol S$0.25/km, other costs S$1/hr, 25 km/h + 5 min per stop
- Rule at S$15/hr: fare ≥ S$3.40 + S$1.13 × (km to pickup + job km)
Change the constants at the top of `Scorer.kt`.

## If offers aren't detected
The parser was built from the Ongoing/Record screen layout (distance → pickup → drop → S$ fare).
The Pickup screen may differ. To see what it reads: Android Studio → **Logcat** → filter `OfferReader`.
Each line shows the raw screen text and what was parsed — send that (or a screenshot of the Pickup screen) to tune `OfferParser.kt`.

## Files
- `OfferParser.kt` – screen text → offers
- `Scorer.kt` – take-home, S$/hr, min fare, max pickup km, best stacking route
- `Geo.kt` – built-in Singapore areas + distance
- `PlaceResolver.kt` – unknown place names → coordinates (Android Geocoder, cached)
- `ReaderService.kt` – screen capture, text recognition, GPS, scoring loop
- `Overlay.kt` – floating bubble
- `MainActivity.kt` – start/stop, target, orders on board
- `Store.kt` – saved settings and orders
